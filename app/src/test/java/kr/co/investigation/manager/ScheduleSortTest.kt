package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleSortTest {
    private fun row(id: Long, number: String = "", registered: Long = id,
                    date: String = "2026-09-13", route: Int = 0) =
        InvestigationCase(id = id, year = 2026, managementNo = number,
            createdAt = registered, plannedDate = date, routeOrder = route)

    @Test fun investigationNumbersUseNumericOrderWithMissingNumbersLast() {
        val rows = listOf(row(1, "검사-10"), row(2, "검사-2"), row(3, " "), row(4, "검사-100"))
        assertEquals(listOf(2L, 1L, 4L, 3L), sortScheduleRows(rows, ScheduleSort.NUMBER_ASC).map { it.id })
        assertEquals(listOf(4L, 1L, 2L, 3L), sortScheduleRows(rows, ScheduleSort.NUMBER_DESC).map { it.id })
    }

    @Test fun registrationUsesCreationTimeRatherThanRequestDateOrId() {
        val rows = listOf(row(1, registered = 300), row(2, registered = 100), row(3, registered = 200))
            .map { it.copy(requestDate = "2026-09-01", updatedAt = 999) }
        assertEquals(listOf(2L, 3L, 1L), sortScheduleRows(rows, ScheduleSort.REGISTERED_ASC).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), sortScheduleRows(rows, ScheduleSort.REGISTERED_DESC).map { it.id })
    }

    @Test fun everySortKeepsDatesChronologicalAndUnassignedLast() {
        val rows = listOf(row(1, "검사-1", date = ""), row(2, "검사-2", date = "2026-09-15"),
            row(3, "검사-3", date = "2026-09-13"))
        ScheduleSort.entries.forEach { sort ->
            assertEquals(listOf(3L, 2L, 1L), sortScheduleRows(rows, sort).map { it.id })
        }
    }

    @Test fun defaultRouteOrderIsPreservedAndSwitchingSortDoesNotWriteRoutes() {
        val rows = listOf(row(1, "검사-1", route = 2), row(2, "검사-2", route = 1),
            row(3, "검사-3", route = 0))
        assertEquals(listOf(2L, 1L, 3L), sortScheduleRows(rows, ScheduleSort.ROUTE).map { it.id })
        val byNumber = sortScheduleRows(rows, ScheduleSort.NUMBER_ASC)
        assertEquals(listOf(1L, 2L, 3L), byNumber.map { it.id })
        assertEquals(listOf(2, 1, 0), byNumber.map { it.routeOrder })
        assertEquals(listOf(2L, 1L, 3L), sortScheduleRows(byNumber, ScheduleSort.ROUTE).map { it.id })
    }

    @Test fun tiesHaveDeterministicOrder() {
        val rows = listOf(row(2, "검사-1", registered = 100), row(1, "검사-1", registered = 100))
        listOf(ScheduleSort.NUMBER_ASC, ScheduleSort.NUMBER_DESC,
            ScheduleSort.REGISTERED_ASC, ScheduleSort.REGISTERED_DESC).forEach { sort ->
            assertEquals(listOf(1L, 2L), sortScheduleRows(rows, sort).map { it.id })
        }
    }
}
