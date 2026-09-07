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

    @Test
    fun investigatorPhoneIsRemovedFromDebtorAndOwnerContactFields() {
        val profile = InvestigatorProfile("테스트담당", "01053126436")
        val value = profile.applyTo(
            InvestigationCase(
                year = 2026,
                phone = "010-5312-6436",
                mobile = "01053126436",
                ownerPhone = "010 5312 6436"
            )
        )

        assertEquals("", value.phone)
        assertEquals("", value.mobile)
        assertEquals("", value.ownerPhone)
        assertEquals("010-5312-6436", value.investigatorPhone)
    }

    @Test
    fun unrelatedContactNumbersArePreserved() {
        val profile = InvestigatorProfile("테스트담당", "01053126436")
        val value = profile.applyTo(
            InvestigationCase(
                year = 2026,
                phone = "031-123-4567",
                mobile = "010-1111-2222",
                ownerPhone = "010-3333-4444"
            )
        )

        assertEquals("031-123-4567", value.phone)
        assertEquals("010-1111-2222", value.mobile)
        assertEquals("010-3333-4444", value.ownerPhone)
    }
}
