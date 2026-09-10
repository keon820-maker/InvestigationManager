package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSheetSortTest {
    private fun row(id: Long, number: String = "", date: String = "", name: String = "") =
        InvestigationCase(id = id, year = 2026, managementNo = number, plannedDate = date, debtorName = name)

    @Test fun embeddedNumbersAndEmptyCellsSortInBothDirections() {
        val rows = listOf(row(1, "검사-10"), row(2, "검사-2"), row(3, " "), row(4, "검사-100"))
        assertEquals(listOf(2L, 1L, 4L, 3L), sortDataSheetRows(rows, true) { it.managementNo }.map { it.id })
        assertEquals(listOf(4L, 1L, 2L, 3L), sortDataSheetRows(rows, false) { it.managementNo }.map { it.id })
    }

    @Test fun datesSortChronologicallyAndMissingDatesStayLast() {
        val rows = listOf(row(1, date = "2026-10-01"), row(2, date = "2025-12-31"), row(3))
        assertEquals(listOf(2L, 1L, 3L), sortDataSheetRows(rows, true) { it.plannedDate }.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), sortDataSheetRows(rows, false) { it.plannedDate }.map { it.id })
    }

    @Test fun koreanNamesSortAndEqualValuesKeepDeterministicOrder() {
        val rows = listOf(row(3, name = "나나"), row(2, name = "가가"), row(1, name = "가가"))
        assertEquals(listOf(1L, 2L, 3L), sortDataSheetRows(rows, true) { it.debtorName }.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), sortDataSheetRows(rows, false) { it.debtorName }.map { it.id })
    }

    @Test fun longNumericIdentifiersDoNotOverflow() {
        val rows = listOf(row(1, "A9999999999999999999999999"), row(2, "A10000000000000000000000000"))
        assertEquals(listOf(1L, 2L), sortDataSheetRows(rows, true) { it.managementNo }.map { it.id })
    }
}
