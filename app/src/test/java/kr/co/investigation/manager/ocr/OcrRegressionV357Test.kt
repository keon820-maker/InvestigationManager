package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegressionV357Test {
    @Test
    fun structurallyShiftedFieldsTriggerSpatialRescue() {
        val broken = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            requestDate = "2026-09-04",
            debtorName = "완료요청일",
            phone = "02-609-0012",
            mobile = "010-9999-8888",
            dueDate = "2026-09-10",
            investigationType = "2026-0910비고",
            propertyAddress = "경기 테스트시 샘플로 1",
            ownerName = "자조사",
            ownerAddress = "충남 테스트시 예시길 2"
        )
        assertTrue(SpatialRescueRepairV357.needsRescue(broken))
    }

    @Test
    fun labelBasedCandidateReplacesShiftedRowsAndClearsManagementLeakPhone() {
        val current = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            requestDate = "2026-09-04",
            debtorName = "완료요청일",
            phone = "02-609-0012",
            mobile = "010-9999-8888",
            dueDate = "2026-09-10",
            investigationType = "2026-0910비고",
            propertyAddress = "경기 테스트시 잘못로 9",
            ownerName = "자조사",
            ownerPhone = "041-111-2222",
            ownerAddress = "충남 테스트시 잘못길 8",
            branchPhone = "041-333-4444",
            branchFax = "041-333-4444"
        )
        val candidate = InvestigationCase(
            year = 2026,
            managementNo = "경기202609-00123",
            requestDate = "2026-09-04",
            debtorName = "홍길동(900101-*)",
            phone = "",
            mobile = "010-1111-2222",
            dueDate = "2026-09-10",
            investigationType = "열람조사+임대차조사",
            loanType = "전세자금(보증서)",
            propertyType = "아파트",
            propertyAddress = "12345 충남 예시시 새길 10 101호",
            ownerName = "김철수",
            ownerResidentNo = "800101-*",
            ownerPhone = "010-3333-4444",
            ownerAddress = "54321 경기 예시시 중앙로 20 202호",
            branch = "예시출장소",
            branchPhone = "041-555-6666",
            branchFax = "041-777-8888",
            requester = "이영희"
        )

        val fixed = SpatialRescueRepairV357.mergeRescue(current, candidate)
        assertEquals("홍길동(900101-*)", fixed.debtorName)
        assertEquals("", fixed.phone)
        assertEquals("010-1111-2222", fixed.mobile)
        assertEquals("열람조사+임대차조사", fixed.investigationType)
        assertEquals("전세자금(보증서)", fixed.loanType)
        assertEquals("아파트", fixed.propertyType)
        assertEquals("12345 충남 예시시 새길 10 101호", fixed.propertyAddress)
        assertEquals("김철수", fixed.ownerName)
        assertEquals("800101-*", fixed.ownerResidentNo)
        assertEquals("010-3333-4444", fixed.ownerPhone)
        assertEquals("54321 경기 예시시 중앙로 20 202호", fixed.ownerAddress)
        assertEquals("예시출장소", fixed.branch)
        assertEquals("041-555-6666", fixed.branchPhone)
        assertEquals("041-777-8888", fixed.branchFax)
        assertEquals("이영희", fixed.requester)
    }
}
