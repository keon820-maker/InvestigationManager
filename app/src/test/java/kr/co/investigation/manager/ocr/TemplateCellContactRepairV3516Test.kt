package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCellContactRepairV3516Test {
    @Test
    fun compactMobileNumberIsRecoveredAndFormatted() {
        assertEquals(
            listOf("010-7636-5823"),
            TemplateCellContactRepairV3516.phones("01076365823")
        )
    }

    @Test
    fun bracketedCompactMobileNumberIsRecovered() {
        assertEquals(
            listOf("010-7636-5823"),
            TemplateCellContactRepairV3516.phones("[01076365823]")
        )
    }

    @Test
    fun ownerCompactMobileNumberIsRecoveredAndFormatted() {
        assertEquals(
            "010-3542-6724",
            TemplateCellContactRepairV3516.normalizePhone("01035426724")
        )
    }

    @Test
    fun realTenantNameRemainsValidWhileCutLabelFalsePositiveIsRejected() {
        assertTrue(TenantResultSanitizer.validTenantName("민경기"))
        assertFalse(TenantResultSanitizer.validTenantName("지인"))
        assertFalse(TenantResultSanitizer.validTenantName("임차인"))
    }

    @Test
    fun normalizedMobileIsValidTenantPhoneSoDebtorTenantDuplicateCanBePreserved() {
        val mobile = TemplateCellContactRepairV3516.normalizePhone("01076365823")
        assertTrue(TenantResultSanitizer.validTenantPhone(mobile))
        assertEquals("010-7636-5823", mobile)
    }
}
