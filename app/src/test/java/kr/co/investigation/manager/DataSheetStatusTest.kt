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
