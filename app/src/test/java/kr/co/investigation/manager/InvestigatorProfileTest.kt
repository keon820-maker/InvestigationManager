package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigatorProfileTest {
    @Test
    fun configuredProfileOverridesOcrFieldsAndClearsFax() {
        val profile = InvestigatorProfile("테스트담당", "01000000000")
        val value = profile.applyTo(
            InvestigationCase(
                year = 2026,
                investigator = "OCR값",
                investigatorPhone = "02-000-0000",
                investigatorFax = "02-111-1111"
            )
        )

        assertTrue(profile.isConfigured)
        assertEquals("테스트담당", value.investigator)
        assertEquals("010-0000-0000", value.investigatorPhone)
        assertEquals("", value.investigatorFax)
    }
}
