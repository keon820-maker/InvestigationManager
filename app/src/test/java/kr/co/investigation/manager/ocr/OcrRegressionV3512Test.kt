package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegressionV3512Test {
    @Test
    fun managementNumberDropsOneTrailingOcrDigit() {
        assertEquals(
            "경기202609-00124",
            FinalResultConsistencyV3512.normalizeManagement("경기202609-001240")
        )
    }

    @Test
    fun noisyBranchKeepsOnlyActualBranchName() {
        assertEquals(
            "판교역지점",
            FinalResultConsistencyV3512.normalizeBranch("D : 판교역지점적")
        )
    }

    @Test
    fun centerAndOutpostBranchNamesArePreserved() {
        assertEquals(
            "강원디지털여신센터",
            FinalResultConsistencyV3512.normalizeBranch("강원디지털여신센터")
        )
        assertEquals(
            "아산시청<출>",
            FinalResultConsistencyV3512.normalizeBranch("아산시청<출>")
        )
    }

    @Test
    fun missingOwnerOrFooterFieldsTriggerFocusedRecovery() {
        val c = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00124",
            ownerName = "",
            ownerResidentNo = "",
            branch = "",
            branchPhone = "",
            branchFax = ""
        )
        assertTrue(MissingCoreFieldRecoveryV3512.needsRecovery(c))
    }

    @Test
    fun completeValidCoreFieldsDoNotTriggerExtraRecovery() {
        val c = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00124",
            ownerName = "홍길동",
            ownerResidentNo = "900101-*",
            branch = "모란지점",
            branchPhone = "031-123-4567",
            branchFax = "0503-1234-5678"
        )
        assertFalse(MissingCoreFieldRecoveryV3512.needsRecovery(c))
    }
}
