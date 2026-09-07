package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRegressionV354Test {
    @Test
    fun corruptedRepeatedNotesHeaderDropsTheSecondOcrBlock() {
        val duplicated = """
            9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.
            보증금:0
            월임차료:0
            기타요최시환
            9/7 대출 실행 후 본인 입주 사실 흑인 요청드립니 다
            보증금:0
            울은치료:0
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = duplicated), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals("9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.\n보증금:0\n월임차료:0", fixed)
    }

    @Test
    fun latinCharacterCorruptedHeaderAlsoDropsTheRepeatedBlock() {
        val duplicated = """
            9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.
            보증금:0
            월임차료:0
            기e요최시환
            9/7 대출 실행 후 본인 입주 사실 흑인 요청드립니 다
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = duplicated), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals("9/7 대출 실행 후 본인 입주 사실 확인 요청드립니다.\n보증금:0\n월임차료:0", fixed)
    }

    @Test
    fun leadingNotesHeaderIsRemovedButFollowingContentIsKept() {
        val notes = """
            기타요청사항
            방문 전 연락 부탁드립니다.
            보증금:0
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = notes), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals("방문 전 연락 부탁드립니다.\n보증금:0", fixed)
    }

    @Test
    fun ePrefixedCorruptedHeaderDropsRepeatedBlock() {
        val duplicated = """
            기 대출건으로 임대차조사 부탁드립니다.
            보증금:0
            월임차료:0
            E. 기요침 사항
            기 대출건으로 임데치조시 부탁드립니다.
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = duplicated), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals("기 대출건으로 임대차조사 부탁드립니다.\n보증금:0\n월임차료:0", fixed)
    }

    @Test
    fun hangulSectionMarkerAndOneCharacterHeaderTypoDropsRepeatedBlock() {
        val duplicated = """
            임대인께 방문 전 연락부탁드립니다.
            보증금:100000000
            월임차료:600000
            임대차시작일자:20261002
            임대차종료일자:20281002
            다. 기타요추 사항
            임대인께 방문 전 연락부탁드립 니다.
            보증금:00000000.
            울임차료:600000
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = duplicated), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals(
            "임대인께 방문 전 연락부탁드립니다.\n보증금:100000000\n월임차료:600000\n임대차시작일자:20261002\n임대차종료일자:20281002",
            fixed
        )
    }

    @Test
    fun inlineRepeatedHeaderKeepsPrefixAndDropsSecondOcrBlock() {
        val duplicated = """
            다가구주택 9/29일 2년 계약 임대차확인요청드립니다
            보증금: 150000000
            월임차료:0
            임대차시작일자:20260929
            임대차종료일자:20280928. 기타요청 사항
            다가구주택 9/29일 2년 기의 은데치흑인요청드립니다
        """.trimIndent()

        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = duplicated), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals(
            "다가구주택 9/29일 2년 계약 임대차확인요청드립니다\n보증금: 150000000\n월임차료:0\n임대차시작일자:20260929\n임대차종료일자:20280928",
            fixed
        )
    }

    @Test
    fun leaseInvestigationTypoIsCorrectedWithoutHeader() {
        val notes = "기 대출건으로 임데치조시 부탁드립니다."
        val base = OcrService.OcrResult("", InvestigationCase(year = 2026, requestNotes = notes), true, "")
        val fixed = NotesTypoRepairV29.repair(base).parsed.requestNotes
        assertEquals("기 대출건으로 임대차조사 부탁드립니다.", fixed)
    }
}
