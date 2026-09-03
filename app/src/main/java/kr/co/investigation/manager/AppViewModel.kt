package kr.co.investigation.manager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.co.investigation.manager.data.*
import kr.co.investigation.manager.location.GeocoderService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.io.File

class AppViewModel(app:Application):AndroidViewModel(app){
    val db=AppDb.get(app)
    private val _year=MutableStateFlow(LocalDate.now().year); val year=_year.asStateFlow()
    val cases=_year.flatMapLatest{db.cases().observeYear(it)}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _selected=MutableStateFlow<InvestigationCase?>(null); val selected=_selected.asStateFlow()
    private val geocodeAttempted = mutableSetOf<String>()

    init {
        // 이전 버전에서 주소는 저장됐지만 좌표 변환이 실패한 조사건도 앱을 열면 자동 재시도한다.
        viewModelScope.launch {
            cases.collect { list ->
                list.filter {
                    it.propertyAddress.isNotBlank() &&
                        (it.propertyLatitude == null || it.propertyLongitude == null)
                }.forEach { c ->
                    val key = "${c.id}|${c.propertyAddress}"
                    if (!geocodeAttempted.add(key)) return@forEach
                    val xy = GeocoderService.resolve(getApplication(), c.propertyAddress)
                    if (xy != null) {
                        val updated = c.copy(
                            propertyLatitude = xy.first,
                            propertyLongitude = xy.second,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.cases().update(updated)
                        if (_selected.value?.id == c.id) _selected.value = updated
                    }
                }
            }
        }
    }

    fun setYear(y:Int){_year.value=y;_selected.value=null}
    fun select(c:InvestigationCase?){_selected.value=c}

    suspend fun create(c:InvestigationCase):Long {
        val xy=GeocoderService.resolve(getApplication(),c.propertyAddress)
        return db.cases().insert(c.copy(propertyLatitude=xy?.first,propertyLongitude=xy?.second))
    }

    fun update(c:InvestigationCase){
        viewModelScope.launch{
            val xy=if(c.propertyAddress.isNotBlank()) GeocoderService.resolve(getApplication(),c.propertyAddress) else null
            val updated=c.copy(
                propertyLatitude=xy?.first?:c.propertyLatitude,
                propertyLongitude=xy?.second?:c.propertyLongitude,
                updatedAt=System.currentTimeMillis()
            )
            db.cases().update(updated)
            _selected.value=updated
        }
    }

    suspend fun deleteCase(c: InvestigationCase) {
        val attachments = db.attachments().getForCase(c.id)
        attachments.forEach { runCatching { File(it.localPath).delete() } }
        db.attachments().deleteForCase(c.id)
        db.cases().delete(c)
        if (_selected.value?.id == c.id) _selected.value = null
    }
}
