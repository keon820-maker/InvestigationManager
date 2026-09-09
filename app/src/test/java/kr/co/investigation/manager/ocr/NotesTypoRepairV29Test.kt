package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class NotesTypoRepairV29Test {
    @Test
    fun dropsSecondOcrBlockAfterBrokenSectionHeaderAndRemovesBorderGlyphs() {
        val input = """
            |8/14 대출실행 당일 임대차현장조사 부탁드립니다.
            |보증금:0
            월임차료:0
            3.7 E-요청항
            8/14 C출실행 당일 임다츠현장조사 부탁드립니 드.
            보증금:0
            월임초료:0
        """.trimIndent()

        assertEquals(
            "8/14 대출실행 당일 임대차현장조사 부탁드립니다.\n보증금:0\n월임차료:0",
            NotesTypoRepairV29.clean(input)
        )
    }
}
