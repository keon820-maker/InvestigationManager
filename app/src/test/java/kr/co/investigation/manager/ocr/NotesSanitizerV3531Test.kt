package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotesSanitizerV3531Test {
    @Test
    fun repeatedHeadingAndImperfectSecondReadingDoNotDuplicateNotes() {
        val first = "대출완료 후 본인 전입 임대차확인 요청\n보증금:0\n월임차료:0"
        val second = "기타요청사항\n때출완료 후 본인 전입 임미차확인 요청"
        assertEquals(first, NotesTypoRepairV29.clean("$first\n$second"))
    }

    @Test
    fun differentInstructionsAfterRepeatedHeadingAreKept() {
        val first = "방문 전 연락 부탁드립니다.\n보증금:0"
        val second = "출입구 사진을 함께 첨부해 주세요."
        assertEquals("$first\n$second", NotesTypoRepairV29.clean("$first\n기타요청사항\n$second"))
    }

    @Test
    fun sameLineHeadingRetainsItsValueAndTrailingUniqueRequest() {
        val source = "기타요청사항: 계약서를 확인해 주세요.\n기타요청사항 : 계약서를 확인해 주세요.\n사진도 첨부해 주세요."
        assertEquals("계약서를 확인해 주세요.\n사진도 첨부해 주세요.", NotesTypoRepairV29.clean(source))
    }

    @Test
    fun differentAmountsDatesAndTimesRemainReviewable() {
        val first = "보증금:100000000\n방문일:2026-10-01\n방문시간:10:30"
        val second = "보증금:200000000\n방문일:2026-10-02\n방문시간:10:40"
        assertEquals("$first\n$second", NotesTypoRepairV29.clean("$first\n기타요청사항\n$second"))
    }

    @Test
    fun decimalsAreNotCollapsedIntoDifferentNumbers() {
        val source = "금리:1.5%\n기타요청사항\n금리:15%"
        assertEquals("금리:1.5%\n금리:15%", NotesTypoRepairV29.clean(source))
    }

    @Test
    fun negationOrderAndRecipientChangesAreNotFuzzyDuplicates() {
        val first = "방문 전 연락 부탁드립니다.\n현장 진입 가능\n임차인에게 연락 부탁드립니다.\n외부 촬영 요청드립니다."
        val second = "방문 후 연락 부탁드립니다.\n현장 진입 불가\n소유자에게 연락 부탁드립니다.\n내부 촬영 요청드립니다."
        assertEquals("$first\n$second", NotesTypoRepairV29.clean("$first\n기타요청사항\n$second"))
    }

    @Test
    fun similarlyWordedInstructionsWithoutARepeatedHeadingAreNotCollapsed() {
        val value = "현장 출입문 확인\n현장 출입구 확인"
        assertEquals(value, NotesTypoRepairV29.clean(value))
    }

    @Test
    fun differentRegisterAndRegisterDateRequestsSurviveARepeatedHeading() {
        val value = "등기부 확인 요청\n기타요청사항\n등기일 확인 요청"
        val expected = "등기부 확인 요청\n등기일 확인 요청"
        assertEquals(expected, NotesTypoRepairV29.clean(value))
        assertEquals(expected, NotesTypoRepairV29.cleanRepeatedSections(value))
    }

    @Test
    fun differentEntranceAndDoorRequestsSurviveARepeatedHeading() {
        val value = "현장 출입문 확인\n기타요청사항\n현장 출입구 확인"
        val expected = "현장 출입문 확인\n현장 출입구 확인"
        assertEquals(expected, NotesTypoRepairV29.clean(value))
        assertEquals(expected, NotesTypoRepairV29.cleanRepeatedSections(value))
    }

    @Test
    fun unknownSimilarReadingIsRetainedInsteadOfGuessingItsMeaning() {
        val value = "계약서 내용을 확인해 주세요.\n기타요청사항\n계약시 내용을 확인해 주세요."
        val expected = "계약서 내용을 확인해 주세요.\n계약시 내용을 확인해 주세요."
        assertEquals(expected, NotesTypoRepairV29.cleanRepeatedSections(value))
        assertEquals(expected, NotesTypoRepairV29.cleanRepeatedSections(expected))
    }

    @Test
    fun mentioningNotesInASentenceDoesNotSplitTheSentence() {
        val value = "기타요청사항은 담당자에게 확인하세요."
        assertEquals(value, NotesTypoRepairV29.clean(value))
    }

    @Test
    fun cleaningIsIdempotentAndKeepsConflictingAmounts() {
        val source = "방문 전 연락\n보증금:10\n기타요청사항\n방문 전 연락\n보증금:20\n문서 추가 확인"
        val once = NotesTypoRepairV29.clean(source)
        assertEquals("방문 전 연락\n보증금:10\n보증금:20\n문서 추가 확인", once)
        assertEquals(once, NotesTypoRepairV29.clean(once))
    }

    @Test
    fun repairCopiesPreserveDocumentSourceWithoutAppendingDiagnosticText() {
        val source = "기타요청사항: 현장조시 후 확인"
        val base = OcrService.OcrResult(
            rawText = "--- OCR 진단 ---\n영업점 : 가상지점\n조사구분 : 임대차조사(현장조사)",
            parsed = InvestigationCase(year = 2026, requestNotes = "현장조시 후 확인", investigationType = "임대차조사"),
            normalized = true,
            preprocessMessage = "검증용 처리 단계",
            sourceText = source
        )
        val repaired = NotesTypoRepairV29.repair(base)
        assertEquals(source, repaired.sourceText)
        assertFalse(repaired.sourceText.contains("기타요청사항 오기 보정"))
        // Diagnostic labels must not fill blank fields or invent a missing qualifier.
        assertEquals("", CommonResultRepair.repair(repaired).parsed.branch)
        assertEquals("임대차조사", FinalResultConsistencyV3512.repair(repaired).parsed.investigationType)
    }

    @Test
    fun commonFinisherCleansVerifiedGridNotesAndLeavesOtherCellsAlone() {
        val row = InvestigationCase(year = 2026, requestNotes = "계약 확인\n기타요청사항\n계약 확인", branch = "", phone = "")
        val base = OcrService.OcrResult("원문", row, true, "실제 칸 경계 확인")
        val finished = OcrService.finish(base)
        assertEquals(row.copy(requestNotes = "계약 확인"), finished.parsed)
        assertEquals(finished, OcrService.finish(finished))
    }
}
