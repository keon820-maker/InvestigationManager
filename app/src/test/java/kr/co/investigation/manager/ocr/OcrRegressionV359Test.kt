package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegressionV359Test {
    @Test
    fun v358ResultWithMissingContactsTriggersRecovery() {
        val result = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(
                year = 2026,
                managementNo = "경기202609-00123",
                debtorName = "홍길동(900101-*)",
                phone = "",
                mobile = "",
                ownerName = "김철수",
                ownerPhone = ""
            ),
            normalized = true,
            preprocessMessage = "필드 누수 최종 정리 v0.35.8"
        )
        assertTrue(ContactRecoveryRepairV359.needsRepair(result))
    }

    @Test
    fun missingContactsTriggerRecoveryEvenWithoutV358Marker() {
        val result = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(year = 2026, mobile = ""),
            normalized = true,
            preprocessMessage = "일반 OCR"
        )
        assertTrue(ContactRecoveryRepairV359.needsRepair(result))
    }

    @Test
    fun completeContactsDoNotTriggerRecovery() {
        val result = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(
                year = 2026,
                phone = "031-123-4567",
                mobile = "010-2222-3333",
                ownerPhone = "010-4444-5555"
            ),
            normalized = true,
            preprocessMessage = "일반 OCR"
        )
        assertFalse(ContactRecoveryRepairV359.needsRepair(result))
    }

    @Test
    fun recoveredMobileAndOwnerContactFillOnlyEmptyFields() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            phone = "",
            mobile = "",
            ownerPhone = "",
            branchPhone = "041-555-6666",
            branchFax = "041-777-8888"
        )

        val fixed = ContactRecoveryRepairV359.mergeRecoveredContacts(
            current = current,
            debtorPhone = "",
            debtorMobile = "010-1234-5678",
            ownerPhone = "010-9876-5432",
            exclusions = setOf("010-1111-2222", "041-555-6666", "041-777-8888")
        )

        assertEquals("", fixed.phone)
        assertEquals("010-1234-5678", fixed.mobile)
        assertEquals("010-9876-5432", fixed.ownerPhone)
    }

    @Test
    fun headerBranchAndManagementLeakNumbersAreNeverRecovered() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            phone = "",
            mobile = "",
            ownerPhone = ""
        )

        val fixed = ContactRecoveryRepairV359.mergeRecoveredContacts(
            current = current,
            debtorPhone = "02-609-0012",
            debtorMobile = "010-1111-2222",
            ownerPhone = "041-555-6666",
            exclusions = setOf("010-1111-2222", "041-555-6666")
        )

        assertEquals("", fixed.phone)
        assertEquals("", fixed.mobile)
        assertEquals("", fixed.ownerPhone)
    }

    @Test
    fun existingValidContactsArePreserved() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            phone = "031-123-4567",
            mobile = "010-2222-3333",
            ownerPhone = "010-4444-5555"
        )

        val fixed = ContactRecoveryRepairV359.mergeRecoveredContacts(
            current = current,
            debtorPhone = "031-999-8888",
            debtorMobile = "010-9999-8888",
            ownerPhone = "010-7777-6666",
            exclusions = emptySet()
        )

        assertEquals("031-123-4567", fixed.phone)
        assertEquals("010-2222-3333", fixed.mobile)
        assertEquals("010-4444-5555", fixed.ownerPhone)
    }
}
