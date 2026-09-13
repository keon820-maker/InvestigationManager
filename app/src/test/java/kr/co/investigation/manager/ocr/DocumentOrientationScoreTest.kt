package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentOrientationScoreTest {
    private val labels = listOf(
        DocumentOrientationScore.Line("조 사 의 뢰 서", .10f),
        DocumentOrientationScore.Line("관리번호", .20f),
        DocumentOrientationScore.Line("의뢰일", .25f),
        DocumentOrientationScore.Line("채무자", .35f),
        DocumentOrientationScore.Line("완료요청일", .45f),
        DocumentOrientationScore.Line("조사구분", .50f),
        DocumentOrientationScore.Line("물건소재지", .60f),
        DocumentOrientationScore.Line("기타요청사항", .75f),
        DocumentOrientationScore.Line("조사의뢰자", .90f)
    )

    @Test fun choosesReadableDirectionInsteadOfSidewaysOrUpsideDownLabels() {
        for (correct in listOf(0, 90, 180, 270)) {
            val candidates = listOf(0, 90, 180, 270).map { degrees ->
                val angle = ((degrees - correct + 540) % 360 - 180).toFloat()
                DocumentOrientationScore.score(degrees, labels.map { it.copy(angle = angle) })
            }
            assertEquals(correct, DocumentOrientationScore.choose(candidates)?.clockwiseDegrees)
        }
    }

    @Test fun blankUnrelatedAndAmbiguousImagesAreNotRotated() {
        assertNull(DocumentOrientationScore.choose(listOf(DocumentOrientationScore.score(90, emptyList()))))
        assertNull(DocumentOrientationScore.choose(listOf(DocumentOrientationScore.score(90,
            listOf(DocumentOrientationScore.Line("2026 1234567890 임의의 사진", .2f))))))
        assertNull(DocumentOrientationScore.choose(listOf(
            DocumentOrientationScore.score(90, labels), DocumentOrientationScore.score(270, labels)
        )))
    }

    @Test fun repeatedSingleHeadingCannotMakeAnImageConfident() {
        val duplicates = List(30) { DocumentOrientationScore.Line("관리번호", .2f) }
        assertNull(DocumentOrientationScore.choose(listOf(DocumentOrientationScore.score(90, duplicates))))
    }

    @Test fun aTitleAtTheBottomCannotUseTheUprightFastPath() {
        val invertedPositions = labels.map { it.copy(centerY = 1f - it.centerY) }
        assertFalse(DocumentOrientationScore.isClearlyUpright(DocumentOrientationScore.score(0, invertedPositions)))
        assertEquals(180, DocumentOrientationScore.choose(listOf(
            DocumentOrientationScore.score(0, invertedPositions), DocumentOrientationScore.score(180, labels)
        ))?.clockwiseDegrees)
    }
}
