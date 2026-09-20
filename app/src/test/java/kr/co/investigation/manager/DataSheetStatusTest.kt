package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class DataSheetStatusTest {
    @Test
    fun cancelledStatusStaysCancelledInDataSheet() {
        assertEquals("의뢰취소", normalizedStatusV31("의뢰취소"))
    }

    @Test
    fun statusCountsIncludeCancelledSeparately() {
        val rows = listOf(
            InvestigationCase(id = 1, year = 2026, status = "신규"),
            InvestigationCase(id = 2, year = 2026, status = "진행중"),
            InvestigationCase(id = 3, year = 2026, status = "의뢰취소"),
            InvestigationCase(id = 4, year = 2026, status = "완료"),
            InvestigationCase(id = 5, year = 2026, status = "신규")
        )
        val counts = dataSheetStatusCountsV36(rows)
        assertEquals(5, counts["전체"])
        assertEquals(2, counts["신규"])
        assertEquals(1, counts["진행중"])
        assertEquals(1, counts["의뢰취소"])
        assertEquals(1, counts["완료"])
    }

    @Test
    fun cancelledCaseIsNotMarkedDelayed() {
        val c = InvestigationCase(
            id = 1,
            year = 2026,
            status = "의뢰취소",
            plannedDate = "2026-09-01",
            dueDate = "2026-09-05"
        )
        assertFalse(isDelayedV31(c, LocalDate.of(2026, 9, 20)))
    }
}
