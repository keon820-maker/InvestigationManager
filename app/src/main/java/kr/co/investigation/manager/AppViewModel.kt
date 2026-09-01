package kr.co.investigation.manager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.co.investigation.manager.data.*
import kr.co.investigation.manager.location.GeocoderService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class AppViewModel(app:Application):AndroidViewModel(app){
    val db=AppDb.get(app)
    private val _year=MutableStateFlow(LocalDate.now().year); val year=_year.asStateFlow()
    val cases=_year.flatMapLatest{db.cases().observeYear(it)}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _selected=MutableStateFlow<InvestigationCase?>(null); val selected=_selected.asStateFlow()
    fun setYear(y:Int){_year.value=y;_selected.value=null}; fun select(c:InvestigationCase?){_selected.value=c}
    suspend fun create(c:InvestigationCase):Long { val xy=GeocoderService.resolve(getApplication(),c.propertyAddress); return db.cases().insert(c.copy(propertyLatitude=xy?.first,propertyLongitude=xy?.second)) }
    fun update(c:InvestigationCase){viewModelScope.launch{ val xy=if(c.propertyAddress.isNotBlank())GeocoderService.resolve(getApplication(),c.propertyAddress) else null; db.cases().update(c.copy(propertyLatitude=xy?.first?:c.propertyLatitude,propertyLongitude=xy?.second?:c.propertyLongitude,updatedAt=System.currentTimeMillis()));_selected.value=c }}
}
