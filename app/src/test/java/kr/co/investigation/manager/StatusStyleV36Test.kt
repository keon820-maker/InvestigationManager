package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatusStyleV36Test {
    private fun row() = InvestigationCase(id = 1, year = 2026, status = "신규")

    @Test
    fun normalizeKeepsKnownStatuses() {
        assertEquals("신규", normalizeCaseStatusV36(""))
        assertEquals("진행중", normalizeCaseStatusV36("진행중"))
        assertEquals("의뢰취소", normalizeCaseStatusV36("의뢰취소"))
        assertEquals("완료", normalizeCaseStatusV36("완료"))
    }

    @Test
    fun selectingDoneAgainPreservesOriginalCompletionTime() {
        val original = InvestigationCase(
            id = 1,
            year = 2026,
            status = "완료",
            startedAt = 100L,
            completedAt = 200L
        )
        val updated = changeDraftStatusV36(original, "완료", 999L)
        assertEquals(100L, updated.startedAt)
        assertEquals(200L, updated.completedAt)
    }

    @Test
    fun changingDraftStatusUpdatesTimestampsConsistently() {
        val now = 123456L
        val progress = changeDraftStatusV36(row(), "진행중", now)
        assertEquals("진행중", progress.status)
        assertEquals(now, progress.startedAt)
        assertNull(progress.completedAt)

        val done = changeDraftStatusV36(progress, "완료", now + 100)
        assertEquals("완료", done.status)
        assertNotNull(done.startedAt)
        assertEquals(now + 100, done.completedAt)

        val cancelled = changeDraftStatusV36(done, "의뢰취소", now + 200)
        assertEquals("의뢰취소", cancelled.status)
        assertNull(cancelled.startedAt)
        assertNull(cancelled.completedAt)
    }
}
