package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleStatusFilterTest {
    private fun row(
        id: Long,
        status: String,
        plannedDate: String
    ) = InvestigationCase(
        id = id,
        year = 2026,
        managementNo = "CASE-$id",
        status = status,
        plannedDate = plannedDate
    )

    @Test
    fun countsAreCalculatedInsideCurrentDateResultSet() {
        val today = LocalDate.of(2026, 9, 19)
        val rows = listOf(
            row(1, "신규", "2026-09-18"),
            row(2, "신규", "2026-09-20"),
            row(3, "진행중", "2026-09-20"),
            row(4, "완료", "2026-09-18"),
            row(5, "의뢰취소", "2026-09-18")
        )

        val counts = scheduleStatusCountsV36(rows, today)

        assertEquals(5, counts["전체보기"])
        assertEquals(2, counts["신규"])
        assertEquals(1, counts["진행중"])
        assertEquals(1, counts["지연"])
        assertEquals(1, counts["의뢰취소"])
        assertEquals(1, counts["완료"])
    }

    @Test
    fun delayedDoesNotIncludeDoneOrCancelledRows() {
        val today = LocalDate.of(2026, 9, 19)
        assertTrue(scheduleMatchesStatusV36(row(1, "신규", "2026-09-18"), "지연", today))
        assertFalse(scheduleMatchesStatusV36(row(2, "완료", "2026-09-18"), "지연", today))
        assertFalse(scheduleMatchesStatusV36(row(3, "의뢰취소", "2026-09-18"), "지연", today))
    }
}
