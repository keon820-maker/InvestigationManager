package kr.co.investigation.manager.archive

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ArchiveServiceTest {
    @Test
    fun monthlyExportUsesRequestDateThenPlannedDateThenCreatedDate() {
        val createdInMarch = LocalDateTime.of(2026, 3, 4, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val cases = listOf(
            InvestigationCase(id = 1, year = 2026, requestDate = "2026-03-01"),
            InvestigationCase(id = 2, year = 2026, requestDate = "", plannedDate = "2026-03-12"),
            InvestigationCase(id = 3, year = 2026, requestDate = "", plannedDate = "", createdAt = createdInMarch),
            InvestigationCase(id = 4, year = 2026, requestDate = "2026-04-01")
        )

        val selected = ArchiveService.selectCasesForMonth(cases, 2026, 3, ZoneOffset.UTC)

        assertEquals(listOf(1L, 2L, 3L), selected.map { it.id })
    }
}
