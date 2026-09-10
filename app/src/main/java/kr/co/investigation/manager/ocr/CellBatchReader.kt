package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.tasks.await

/** Stack isolated crops with whitespace; never assign a line spanning two cells. */
internal object CellBatchReader {
    data class Input(val key: String, val cell: GridFormLayout.Cell)
    private data class Crop(val key: String, val rect: Rect)
    private data class Placement(val crop: Crop, val rect: Rect)

    suspend fun read(client: TextRecognizer, source: Bitmap, inputs: List<Input>, enhanced: Boolean = false): Map<String, String> {
        val result = inputs.associate { it.key to "" }.toMutableMap()
        val margin = (source.width / 1000).coerceIn(2, 4)
        val crops = inputs.mapNotNull { input ->
            val c = input.cell
            val r = Rect((c.left + margin).coerceAtLeast(0), (c.top + margin).coerceAtLeast(0),
                (c.right - margin).coerceAtMost(source.width), (c.bottom - margin).coerceAtMost(source.height))
            if (r.width() > 0 && r.height() > 0) Crop(input.key, r) else null
        }
        // Short fields must not be shrunk by sharing a panel with a page-wide address.
        val groups = crops.groupBy { when { it.rect.width() <= 800 -> 0; it.rect.width() <= 1400 -> 1; else -> 2 } }
        for (group in groups.values) {
            var panel = mutableListOf<Crop>()
            var height = 24
            suspend fun flush() {
                if (panel.isEmpty()) return
                val width = panel.maxOf { it.rect.width() } + 48
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
                    val placements = mutableListOf<Placement>()
                    var y = 24
                    for (crop in panel) {
                        val r = crop.rect
                        val cut = Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height())
                        var prepared = cut
                        try {
                            if (enhanced) prepared = CellImageProcessing.contrast(cut)
                            canvas.drawBitmap(prepared, 24f, y.toFloat(), null)
                            placements += Placement(crop, Rect(24, y, 24 + r.width(), y + r.height()))
                        } finally {
                            if (prepared !== cut && prepared !== source) prepared.recycle()
                            if (cut !== source) cut.recycle()
                        }
                        y += r.height() + 48
                    }
                    val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
                    val assigned = placements.associate { it.crop.key to mutableListOf<Text.Line>() }
                    for (line in text.textBlocks.flatMap { it.lines }) {
                        val box = line.boundingBox ?: continue
                        val owner = placements.singleOrNull { p ->
                            box.left >= p.rect.left - 2 && box.right <= p.rect.right + 2 &&
                                box.top >= p.rect.top - 2 && box.bottom <= p.rect.bottom + 2
                        } ?: continue
                        assigned.getValue(owner.crop.key) += line
                    }
                    assigned.forEach { (key, lines) -> result[key] = readingOrder(lines) }
                } finally { bitmap.recycle() }
                panel = mutableListOf()
                height = 24
            }
            for (crop in group) {
                if (panel.isNotEmpty() && height + crop.rect.height() + 48 > 1024) flush()
                panel += crop
                height += crop.rect.height() + 48
            }
            flush()
        }
        return result
    }

    private fun readingOrder(lines: List<Text.Line>): String {
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in lines.sortedBy { it.boundingBox!!.centerY() }) {
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf {
                kotlin.math.abs(it.first().boundingBox!!.centerY() - box.centerY()) < box.height() * 0.5
            }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } }
    }
}
