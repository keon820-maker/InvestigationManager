package kr.co.investigation.manager.sync

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {
    @Test
    fun newerTimestampWins() {
        val older = InvestigationCase(year = 2026, updatedAt = 100L, modifiedByDevice = "z")
        val newer = InvestigationCase(year = 2026, updatedAt = 101L, modifiedByDevice = "a")

        assertTrue(compareVersion(newer, older) > 0)
        assertTrue(compareVersion(older, newer) < 0)
    }

    @Test
    fun deviceIdBreaksTimestampTieDeterministically() {
        val left = InvestigationCase(year = 2026, updatedAt = 100L, modifiedByDevice = "device-b")
        val right = InvestigationCase(year = 2026, updatedAt = 100L, modifiedByDevice = "device-a")

        assertTrue(compareVersion(left, right) > 0)
        assertEquals(0, compareVersion(left, left))
    }
}
