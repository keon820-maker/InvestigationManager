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

    fun startInvestigation(c: InvestigationCase) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = c.copy(
                status = "진행중",
                startedAt = c.startedAt ?: now,
                completedAt = null,
                updatedAt = now
            )
            db.cases().update(updated)
            if (_selected.value?.id == c.id) _selected.value = updated
        }
    }

    fun completeInvestigation(c: InvestigationCase) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = c.copy(
                status = "완료",
                startedAt = c.startedAt ?: now,
                completedAt = now,
                updatedAt = now
            )
            db.cases().update(updated)
            if (_selected.value?.id == c.id) _selected.value = updated
        }
    }

    fun saveRouteOrder(ordered: List<InvestigationCase>) {
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updates = ordered.mapIndexed { index, c ->
                c.copy(routeOrder = index + 1, updatedAt = now)
            }
            db.cases().updateAll(updates)
            val selectedId = _selected.value?.id
            if (selectedId != null) updates.firstOrNull { it.id == selectedId }?.let { _selected.value = it }
        }
    }

    suspend fun findDuplicates(c: InvestigationCase): List<InvestigationCase> {
        val management = normalizeKey(c.managementNo)
        val address = normalizeKey(c.propertyAddress)
        val debtor = normalizeKey(c.debtorName)
        return db.cases().getYear(c.year).filter { old ->
            if (old.id == c.id) return@filter false
            val managementSame = management.isNotBlank() && normalizeKey(old.managementNo) == management
            val addressDebtorSame = address.isNotBlank() && debtor.isNotBlank() &&
                normalizeKey(old.propertyAddress) == address && normalizeKey(old.debtorName) == debtor
            managementSame || addressDebtorSame
        }
    }

    suspend fun deleteCase(c: InvestigationCase) {
        val attachments = db.attachments().getForCase(c.id)
        attachments.forEach { runCatching { File(it.localPath).delete() } }
        db.attachments().deleteForCase(c.id)
        db.cases().delete(c)
        if (_selected.value?.id == c.id) _selected.value = null
    }

    private fun normalizeKey(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[\\s\\-()\\[\\],.]"), "")
}
