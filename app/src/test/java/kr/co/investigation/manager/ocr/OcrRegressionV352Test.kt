package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRegressionV352Test {
    @Test
    fun duplicatedAndCorruptedRequestNotesAreCollapsed() {
        val duplicated = """
            9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.
            보증금:0
            월임차료:0
            기타요최시환
            9/7 대출 실행 후 본인 입주 사실 흑인 요청드립니 다
            보증금:0
            울은치료:0
        """.trimIndent()

        val base = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(year = 2026, requestNotes = duplicated),
            normalized = true,
            preprocessMessage = ""
        )

        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals(
            "9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.\n보증금:0\n월임차료:0",
            fixed
        )
    }

    @Test
    fun footerPhoneNormalizerSupportsFourDigitExchangeAndServiceFax() {
        assertEquals("031-8016-3382", OcrPhoneNormalizer.normalize("031-8016-3382"))
        assertEquals("0503-8956-0172", OcrPhoneNormalizer.normalize("(0503)-8956-0172"))
        assertEquals("", OcrPhoneNormalizer.normalize("038-016-3382"))
    }
}
