package kr.co.investigation.manager

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickScheduleUpdateTest {
    @Test
    fun statusAndDateQuickUpdatesDoNotOverwriteNewerFields() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        db.clearAllTables()
        val id = db.cases().insert(
            InvestigationCase(
                year = 2026,
                managementNo = "fresh-row-fixture",
                status = "신규",
                plannedDate = "2026-09-20",
                investigationMemo = "처음 메모"
            )
        )
        val stale = db.cases().get(id)!!
        val newer = stale.copy(
            investigationMemo = "다른 기기에서 갱신된 메모",
            updatedAt = stale.updatedAt + 100
        )
        db.cases().update(newer)

        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            AppViewModel(app) { null }.also { store.put("quick-update-test", it) }
        }

        try {
            withContext(Dispatchers.Main) {
                vm.changeStatus(stale, "진행중")
            }
            withTimeout(10_000) {
                while (db.cases().get(id)!!.status != "진행중") delay(10)
            }
            assertEquals("다른 기기에서 갱신된 메모", db.cases().get(id)!!.investigationMemo)

            val staleAgain = stale.copy(status = "진행중")
            withContext(Dispatchers.Main) {
                vm.changePlannedDate(staleAgain, "2026-09-25")
            }
            withTimeout(10_000) {
                while (db.cases().get(id)!!.plannedDate != "2026-09-25") delay(10)
            }
            assertEquals("다른 기기에서 갱신된 메모", db.cases().get(id)!!.investigationMemo)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.clearAllTables()
        }
    }
}
