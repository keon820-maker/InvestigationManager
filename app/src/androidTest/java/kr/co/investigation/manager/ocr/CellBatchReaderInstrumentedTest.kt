package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CellBatchReaderInstrumentedTest {
    @Test fun reorderedWidthsKeepDistinctContactsAndWrappedLines() = runBlocking {
        val bitmap = Bitmap.createBitmap(1500, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val font = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
        canvas.drawText("010-0000-0011", 20f, 60f, font)
        canvas.drawText("031-000-0022", 20f, 180f, font)
        canvas.drawText("010-0000-", 20f, 300f, font)
        canvas.drawText("0033", 20f, 345f, font)
        val inputs = listOf(
            CellBatchReader.Input("phone", GridFormLayout.Cell(0, 0, 450, 100)),
            CellBatchReader.Input("ownerPhone", GridFormLayout.Cell(0, 120, 1400, 220)),
            CellBatchReader.Input("tenantPhone", GridFormLayout.Cell(0, 240, 450, 390)),
            CellBatchReader.Input("empty", GridFormLayout.Cell(700, 240, 1200, 390))
        )
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            for (enhanced in listOf(false, true)) {
                val result = CellBatchReader.read(client, bitmap, inputs, enhanced)
                assertEquals("010-0000-0011", GridCellValues.phone(result.getValue("phone")))
                assertEquals("031-000-0022", GridCellValues.phone(result.getValue("ownerPhone")))
                assertEquals("010-0000-0033", GridCellValues.phone(result.getValue("tenantPhone")))
                assertEquals("", result.getValue("empty"))
            }
        } finally { client.close(); bitmap.recycle() }
    }
}
