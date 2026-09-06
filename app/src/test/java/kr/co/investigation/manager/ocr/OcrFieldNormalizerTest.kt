package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFieldNormalizerTest {
    @Test
    fun debtorIdentityKeepsBirthDateAndMask() {
        assertEquals("가나다(900101-*)", OcrFieldNormalizer.debtorIdentity("채무자명 가나다 (900101-*)"))
        assertEquals("가나다(900101)", OcrFieldNormalizer.debtorIdentity("가나다(900101)"))
    }

    @Test
    fun completeRealEstateLoanWinsOverShorterSubstring() {
        assertEquals("부동산 담보대출", OcrFieldNormalizer.loanType("대출 종류 | 부동산담보대출"))
        assertEquals("부동산 담보대출", OcrFieldNormalizer.loanType("부동산남보대출"))
        assertEquals(
            "부동산 담보대출",
            OcrFieldNormalizer.preferLoan("담보대출", "부동산 담보대출")
        )
    }

    @Test
    fun investigatorHeaderIsRemovedFromDiagnosticText() {
        val redacted = OcrFieldNormalizer.redactInvestigatorSection(
            "관리번호 예시-001\n조사담당자\n테스트담당\nTel 010-0000-0000\nFax 02-000-0000\n채무자명 가나다(900101-*)"
        )
        assertTrue(redacted.contains("조사담당자 : [OCR 제외]"))
        assertTrue(redacted.contains("채무자명"))
        assertFalse(redacted.contains("테스트담당"))
        assertFalse(redacted.contains("010-0000-0000"))
    }
}
