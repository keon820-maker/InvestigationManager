package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Uses only generated headings. No real case data or attachment is put in CI. */
@RunWith(AndroidJUnit4::class)
class DocumentOrientationTest {
    @Test fun recognizesAllFourDirectionsWithTheBundledKoreanModel() = runBlocking {
        val upright = document()
        try {
            for (angle in listOf(0, 90, 180, 270)) {
                val input = if (angle == 0) upright else DocumentOrientation.rotate(upright, angle)
                var output: Bitmap? = null
                try {
                    val result = DocumentOrientation.correct(input)
                    output = result.bitmap
                    assertTrue("Expected confident correction for $angle degrees", result.confident)
                    assertEquals((360 - angle) % 360, result.clockwiseDegrees)
                    assertEquals(upright.width, result.bitmap.width)
                    assertEquals(upright.height, result.bitmap.height)
                    assertEquals(Color.RED, result.bitmap.getPixel(45, 45))
                    assertFalse("The caller still owns its input bitmap", input.isRecycled)
                } finally {
                    output?.let { if (it !== input && !it.isRecycled) it.recycle() }
                    if (input !== upright) input.recycle()
                }
            }
        } finally { upright.recycle() }
    }

    @Test fun uprightLandscapeAndBlankPhotosAreNotForcedIntoPortrait() = runBlocking {
        val landscape = document(1800, 1240)
        val blank = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val readable = DocumentOrientation.correct(landscape)
            try {
                assertTrue(readable.confident)
                assertEquals(0, readable.clockwiseDegrees)
                assertEquals(1800, readable.bitmap.width)
            } finally { if (readable.bitmap !== landscape) readable.bitmap.recycle() }
            val empty = DocumentOrientation.correct(blank)
            try {
                assertFalse(empty.confident)
                assertEquals(0, empty.clockwiseDegrees)
                assertEquals(1000, empty.bitmap.width)
            } finally { if (empty.bitmap !== blank) empty.bitmap.recycle() }
        } finally { landscape.recycle(); blank.recycle() }
    }

    @Test fun normalizationRotatesBeforePortraitWarpAndPreservesOriginalBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val upright = document()
        val sideways = DocumentOrientation.rotate(upright, 90)
        val file = File.createTempFile("orientation-only-headings", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            val before = digest(file)
            val result = DocumentNormalizer.normalize(context, Uri.fromFile(file))
            try {
                assertTrue(result.message.contains("270도 자동 회전"))
                assertTrue(result.documentDetected)
                assertEquals(2480, result.bitmap.width)
                assertEquals(3508, result.bitmap.height)
                assertEquals(before, digest(file))
            } finally { result.bitmap.recycle() }
        } finally { upright.recycle(); sideways.recycle(); file.delete() }
    }

    @Test fun exifRotationIsAppliedOnceBeforeContentOrientation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val upright = document()
        val sideways = DocumentOrientation.rotate(upright, 270)
        val file = File.createTempFile("orientation-exif-headings", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val before = digest(file)
            val result = DocumentNormalizer.normalize(context, Uri.fromFile(file))
            try {
                assertTrue(result.message.startsWith("문서 방향 확인"))
                assertEquals(2480, result.bitmap.width)
                assertEquals(3508, result.bitmap.height)
                assertEquals(before, digest(file))
            } finally { result.bitmap.recycle() }
        } finally { upright.recycle(); sideways.recycle(); file.delete() }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun document(width: Int = 1240, height: Int = 1754): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawRect(12f, 12f, width - 12f, height - 12f, border)
        canvas.drawRect(35f, 35f, 60f, 60f, Paint().apply { color = Color.RED })
        listOf(
            "조 사 의 뢰 서", "관리번호", "의뢰일", "채무자", "완료요청일", "조사구분",
            "대출종류", "물건종류", "물건소재지", "소유자주소", "기타요청사항", "영업점", "조사의뢰자"
        ).forEachIndexed { index, label ->
            canvas.drawText(label, 140f, height * (.09f + index * .067f), text)
        }
        return bitmap
    }
}
