package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegressionV358Test {
    @Test
    fun spatialRescueResultWithCrossFieldLeaksTriggersFinalRepair() {
        val broken = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            phone = "02-609-0012",
            mobile = "010-5312-6436",
            propertyAddress = "12345 충남 예시시 샘플길 33 201호 연락처 [01033334444]",
            ownerPhone = "041-547-7953",
            branchPhone = "041-547-7953",
            branchFax = "041-546-3883"
        )
        val result = OcrService.OcrResult(
            rawText = "",
            parsed = broken,
            normalized = true,
            preprocessMessage = "라벨 좌표 최종 복구 v0.35.7"
        )

        assertTrue(SpatialLeakRepairV358.needsRepair(result))
    }

    @Test
    fun managementAndHeaderPhonesAreRejectedAndOwnerContactWins() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            phone = "02-609-0012",
            mobile = "010-5312-6436",
            propertyAddress = "12345 충남 예시시 샘플길 33 201호 연락처 [01033334444]",
            ownerPhone = "041-547-7953",
            ownerAddress = "54321 경기 예시시 중앙로 20 202호",
            branchPhone = "041-547-7953",
            branchFax = "041-546-3883"
        )

        val fixed = SpatialLeakRepairV358.repairFields(
            current = current,
            explicitPhone = "",
            explicitMobile = "010-7777-8888",
            explicitOwnerPhone = "010-3333-4444",
            explicitPropertyAddress = "12345 충남 예시시 샘플길 33 201호 연락처 [01033334444]",
            explicitOwnerAddress = "54321 경기 예시시 중앙로 20 202호",
            headerPhones = setOf("010-5312-6436", "031-241-2298")
        )

        assertEquals("", fixed.phone)
        assertEquals("010-7777-8888", fixed.mobile)
        assertEquals("12345 충남 예시시 샘플길 33 201호", fixed.propertyAddress)
        assertEquals("010-3333-4444", fixed.ownerPhone)
        assertEquals("54321 경기 예시시 중앙로 20 202호", fixed.ownerAddress)
    }

    @Test
    fun branchPhoneIsNeverKeptAsOwnerPhoneWhenNoExplicitOwnerContactExists() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            ownerPhone = "041-547-7953",
            branchPhone = "041-547-7953",
            branchFax = "041-546-3883"
        )

        val fixed = SpatialLeakRepairV358.repairFields(
            current = current,
            explicitPhone = "",
            explicitMobile = "",
            explicitOwnerPhone = "",
            explicitPropertyAddress = "",
            explicitOwnerAddress = "",
            headerPhones = emptySet()
        )

        assertEquals("", fixed.ownerPhone)
    }

    @Test
    fun weakInitialConsonantInMojongRoadIsCorrected() {
        assertEquals(
            "12345 충남 예시시 모종로22번길 33 201호",
            SpatialLeakRepairV358.cleanAddressLeak("12345 충남 예시시 오종로22번길 33 201호")
        )
    }
}
