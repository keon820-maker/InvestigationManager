package kr.co.investigation.manager.ocr

import org.junit.Assert.*
import org.junit.Test
import kr.co.investigation.manager.data.InvestigationCase

class FooterFieldsTest {
    @Test fun acceptsFinanceCenterAndSplitLabelsWithoutSwappingPhoneFax() {
        val parsed = FooterFields.parse("""
            ▷ 농 협 영 업 점 : 가상금융센터
            ▷ 조 사 의 뢰 자 : 가 나 다
            ▷ 전 화 번 호 : 0200000000   팩 스 : 0500-0000-0001
            ▷ 신 청 인 : 가상회사
        """.trimIndent())
        assertEquals("가상금융센터", parsed.branch)
        assertEquals("가나다", parsed.requester)
        assertEquals("02-0000-0000", parsed.phone)
        assertEquals("0500-0000-0001", parsed.fax)
        assertTrue(parsed.complete)
    }

    @Test fun readsLabelAndValueOnSeparateLines() {
        val parsed = FooterFields.parse("농협영업점\n가상지점\n조사의뢰자\n라마바\n전화번호\n031 000 0000\nFax\n031 000 0001")
        assertEquals("가상지점", parsed.branch)
        assertEquals("라마바", parsed.requester)
        assertEquals("031-000-0000", parsed.phone)
        assertEquals("031-000-0001", parsed.fax)
    }

    @Test fun headerAndUnlabelledNumbersCannotFillFooter() {
        val parsed = FooterFields.parse("조사담당자 : 제외대상 Tel 010-9999-9999 Fax 031-999-9999\n농협영업점 : 가상지점\n조사의뢰자 : 가나다")
        assertEquals("가상지점", parsed.branch)
        assertEquals("", parsed.phone)
        assertEquals("", parsed.fax)
        assertEquals(FooterFields.Values(), FooterFields.parse("전화번호 : 031-000-0000\nFax : 031-000-0001"))
    }

    @Test fun stopAtSignatureAndDoNotInventBranchFromKnownSuffix() {
        val parsed = FooterFields.parse("농협영업점 :\n조사의뢰자 : 가나다\n신청인 : 가상지점\n전화번호 : 031-999-9999")
        assertEquals("", parsed.branch)
        assertEquals("가나다", parsed.requester)
        assertEquals("", parsed.phone)
    }

    @Test fun conflictsRemainBlankAndAreMarkedForReview() {
        val first = FooterFields.parse("영업점 : 가상지점\n조사의뢰자 : 가나다\n전화번호 : 031-000-0000")
        val second = FooterFields.parse("영업점 : 가상지점\n조사의뢰자 : 가나라\n전화번호 : 031-000-0001\nFax : 031-000-0002")
        val chosen = FooterFields.reconcile(first, second)
        assertEquals("가상지점", chosen.branch)
        assertEquals("", chosen.requester)
        assertEquals("", chosen.phone)
        assertEquals("031-000-0002", chosen.fax)
        assertEquals(setOf("requester", "phone"), chosen.review)
    }

    @Test fun repeatedConflictingLabelsAreNotConcatenated() {
        val parsed = FooterFields.parse("영업점 : 가상지점\n조사의뢰자 : 가나다\n전화번호 : 031-000-0000\n전화번호 : 031-000-0001")
        assertEquals("", parsed.phone)
        assertTrue("phone" in parsed.review)
    }

    @Test fun samePrintedNumberMayBelongToPhoneAndFax() {
        val parsed = FooterFields.parse("농협영업점 : 가상센터\n전화번호 : 031-000-0000\n팩스 : 031-000-0000")
        assertEquals(parsed.phone, parsed.fax)
        assertEquals("031-000-0000", parsed.phone)
    }

    @Test fun legacyCommonPassPreservesRecoveredFinanceCenter() {
        val result = OcrService.OcrResult("", InvestigationCase(year = 2026, branch = "가상금융센터"), false, "")
        assertEquals("가상금융센터", CommonResultRepair.repair(result).parsed.branch)
        assertEquals("가상금융센터", FinalResultConsistencyV3512.repair(result).parsed.branch)
    }

    @Test fun legacyCommonPassOnlyUsesLabeledBranchFromSource() {
        val result = OcrService.OcrResult(
            "조사담당자 : 제외대상 Tel 010-9999-9999\n농협영업점 : 가상금융센터\n조사의뢰자 : 가나다",
            InvestigationCase(year = 2026), false, ""
        )
        val repaired = CommonResultRepair.repair(result).parsed
        assertEquals("가상금융센터", repaired.branch)
        assertEquals("", repaired.branchPhone)
    }
}
