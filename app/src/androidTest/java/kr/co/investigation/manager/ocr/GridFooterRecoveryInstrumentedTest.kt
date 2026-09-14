package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fully synthetic small photo, including an excluded header contact above the notes box. */
class GridFooterRecoveryInstrumentedTest {
    @Test fun smallPhotoReadsFinanceCenterAndFooterContactsOnly() = runBlocking {
        val image = Bitmap.createBitmap(921, 2048, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.DKGRAY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRect(10f, 630f, 911f, 2020f, paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 25f
                typeface = Typeface.DEFAULT
            }
            canvas.drawText("조사의뢰서", 350f, 760f, text)
            canvas.drawText("조사담당자 : 제외대상 Tel 010-9999-9999", 50f, 870f, text)
            canvas.drawText("Fax 031-999-9999", 50f, 910f, text)
            canvas.drawText("기타요청사항", 50f, 1510f, text)
            paint.apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
            canvas.drawRect(50f, 1530f, 880f, 1690f, paint)
            canvas.drawText("농협영업점 : 가상금융센터", 370f, 1770f, text)
            canvas.drawText("조사의뢰자 : 가나다", 370f, 1820f, text)
            canvas.drawText("전화번호 : 031-000-0000", 300f, 1870f, text)
            canvas.drawText("팩스 : 031-000-0001", 630f, 1870f, text)
            canvas.drawText("신청인 : 가상회사", 370f, 1920f, text)

            val result = GridFooterRecovery.read(image, 1690)
            assertEquals("가상금융센터", result.values.branch)
            assertEquals("가나다", result.values.requester)
            assertEquals("031-000-0000", result.values.phone)
            assertEquals("031-000-0001", result.values.fax)
            assertFalse(result.sourceText.contains("9999"))
            assertFalse(image.isRecycled)
        } finally { image.recycle() }
    }
}
