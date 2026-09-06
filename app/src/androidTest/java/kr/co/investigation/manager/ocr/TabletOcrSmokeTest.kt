package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** 공개 CI에는 실제 의뢰서를 올리지 않고, 기기 안에서 만든 비식별 합성 문서로 ML Kit 경로를 검증한다. */
@RunWith(AndroidJUnit4::class)
class TabletOcrSmokeTest {
    @Test
    fun syntheticRequestRunsThroughRealTabletOcr() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = syntheticRequest()
        val file = File(context.cacheDir, "synthetic-request.jpg")
        try {
            FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            val result = OcrService.recognizeCase(context, Uri.fromFile(file))

            assertTrue(result.rawText.isNotBlank())
            assertEquals("", result.parsed.investigator)
            assertEquals("", result.parsed.investigatorPhone)
            assertFalse(result.rawText.contains("제외대상"))
            assertTrue(result.rawText.contains("[OCR 제외]"))
            assertEquals("부동산 담보대출", result.parsed.loanType)
            assertTrue(result.parsed.debtorName.contains("900101"))
        } finally {
            image.recycle()
            file.delete()
        }
    }

    private fun syntheticRequest(): Bitmap {
        val bitmap = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val title = Paint(text).apply {
            textSize = 92f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawRect(18f, 18f, 2462f, 3490f, line)
        canvas.drawText("조 사 의 뢰 서", 865f, 145f, title)
        canvas.drawText("의뢰일", 720f, 245f, text)
        canvas.drawText("2026-03-04", 920f, 245f, text)
        canvas.drawText("관리번호", 230f, 430f, text)
        canvas.drawText("경기202603-00001", 520f, 430f, text)
        canvas.drawText("조사담당자", 230f, 525f, text)
        canvas.drawText("제외대상", 520f, 525f, text)
        canvas.drawText("Tel 010-0000-0000", 930f, 525f, text)

        canvas.drawText("대상자", 210f, 740f, title)
        canvas.drawText("채무자명", 230f, 880f, text)
        canvas.drawText("가나다(900101-*)", 520f, 880f, text)
        canvas.drawText("전화번호", 930f, 880f, text)
        canvas.drawText("031-000-0000", 1180f, 880f, text)
        canvas.drawText("핸드폰번호", 1620f, 880f, text)
        canvas.drawText("010-1111-2222", 1890f, 880f, text)
        canvas.drawText("완료요청일", 230f, 985f, text)
        canvas.drawText("2026-03-09", 520f, 985f, text)

        canvas.drawRect(200f, 1170f, 2370f, 2240f, line)
        canvas.drawText("조사구분", 230f, 1218f, text)
        canvas.drawText("임대차조사(현장조사)", 520f, 1218f, text)
        canvas.drawText("대출종류", 1470f, 1218f, text)
        canvas.drawText("부동산담보대출", 1760f, 1218f, text)
        canvas.drawText("물건종류", 230f, 1320f, text)
        canvas.drawText("아파트", 520f, 1320f, text)
        canvas.drawText("물건소재지", 230f, 1425f, text)
        canvas.drawText("12345 테스트시 임차동 1", 520f, 1425f, text)
        canvas.drawText("성명", 520f, 1525f, text)
        canvas.drawText("라마바(800101-*)", 800f, 1525f, text)
        canvas.drawText("연락처", 1470f, 1525f, text)
        canvas.drawText("010-3333-4444", 1740f, 1525f, text)
        canvas.drawText("소유자주소", 230f, 1640f, text)
        canvas.drawText("12345 테스트시 소유동 2", 520f, 1640f, text)

        canvas.drawText("기타요청사항", 230f, 2430f, text)
        canvas.drawText("비식별 합성 OCR 검사 문서", 250f, 2520f, text)
        canvas.drawText("농협영업점", 930f, 2880f, text)
        canvas.drawText("테스트지점", 1250f, 2880f, text)
        canvas.drawText("조사의뢰자", 930f, 2990f, text)
        canvas.drawText("사아자", 1250f, 2990f, text)
        return bitmap
    }
}
