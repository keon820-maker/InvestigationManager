package kr.co.investigation.manager

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DetailSaveTest {
    @Test fun saveCompletesWhileGeocodingWaitsAndLateResultsCannotReplaceNewEdits() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        db.clearAllTables()
        val id = db.cases().insert(InvestigationCase(year=LocalDate.now().year,
            managementNo="save-fixture",propertyAddress="검증시 기존주소 1",propertyLatitude=1.0,propertyLongitude=2.0))
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<Pair<Double,Double>?>()
        val secondResult = CompletableDeferred<Pair<Double,Double>?>()
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            AppViewModel(app) { address ->
                if(address == "검증시 주소 A") { firstEntered.complete(Unit); firstResult.await() }
                else { secondEntered.complete(Unit); secondResult.await() }
            }.also { store.put("save-test",it) }
        }
        suspend fun awaitSaved() {
            withTimeout(10_000) { while(vm.detailSaveStatus.value.busy) delay(10) }
            assertFalse(vm.detailSaveStatus.value.failed)
            assertEquals("저장했습니다.",vm.detailSaveStatus.value.message)
        }
        try {
            val first = db.cases().get(id)!!.copy(defaultAddressType=DEFAULT_ADDRESS_CUSTOM,customMapAddress="검증시 주소 A")
            withContext(Dispatchers.Main) { vm.select(first); vm.saveDetail(first) }
            awaitSaved()
            withTimeout(10_000) { firstEntered.await() }
            assertFalse(firstResult.isCompleted)
            assertEquals("검증시 주소 A",db.cases().get(id)!!.customMapAddress)
            assertNull(db.cases().get(id)!!.propertyLatitude)

            val second = db.cases().get(id)!!.copy(customMapAddress="검증시 주소 B",investigationMemo="최신 메모")
            withContext(Dispatchers.Main) { vm.saveDetail(second) }
            awaitSaved()
            assertEquals("검증시 주소 B",db.cases().get(id)!!.customMapAddress)
            firstResult.complete(11.0 to 12.0)
            withTimeout(10_000) { secondEntered.await() }
            assertNull(db.cases().get(id)!!.propertyLatitude)
            secondResult.complete(31.0 to 32.0)
            withTimeout(10_000) { while(db.cases().get(id)!!.propertyLatitude != 31.0) delay(10) }
            val saved = db.cases().get(id)!!
            assertEquals("최신 메모",saved.investigationMemo)
            assertEquals("검증시 주소 B",saved.customMapAddress)
            assertEquals(32.0,saved.propertyLongitude!!,0.0)
        } finally { withContext(Dispatchers.Main) { store.clear() }; db.clearAllTables() }
    }

    @Test fun savingADeletedRecordShowsFailureWithoutResurrectingIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        db.clearAllTables()
        val id = db.cases().insert(InvestigationCase(year=2026,managementNo="deleted-save-fixture",deletedAt=1L))
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { AppViewModel(app) { null }.also { store.put("save-test",it) } }
        try {
            val staleDraft = db.cases().get(id)!!.copy(deletedAt=null)
            withContext(Dispatchers.Main) { vm.saveDetail(staleDraft) }
            withTimeout(10_000) { while(vm.detailSaveStatus.value.busy) delay(10) }
            assertTrue(vm.detailSaveStatus.value.failed)
            assertTrue(vm.detailSaveStatus.value.message.isNotBlank())
            assertNotNull(db.cases().get(id)!!.deletedAt)
        } finally { withContext(Dispatchers.Main) { store.clear() }; db.clearAllTables() }
    }
}
