package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max

/** The caller supplies the verified bottom of the notes box; header contacts cannot enter. */
internal object GridFooterRecovery {
    data class Result(val values: FooterFields.Values, val sourceText: String)
    private val direct = Executor { it.run() }

    suspend fun read(source: Bitmap, footerTop: Int): Result {
        if (footerTop !in 1 until source.height - 1) return Result(FooterFields.Values(), "")
        val crop = Bitmap.createBitmap(source, 0, footerTop, source.width, source.height - footerTop)
        val client = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
        catch (error: Throwable) { crop.recycle(); throw error }
        var lastTask: Task<Text>? = null
        suspend fun recognize(input: Bitmap): Text {
            // A task owns its padded copy until native ML Kit processing actually completes.
            val padded = Bitmap.createBitmap(input.width + 40, input.height + 40, Bitmap.Config.ARGB_8888)
            Canvas(padded).apply { drawColor(Color.WHITE); drawBitmap(input, 20f, 20f, null) }
            val task = try { client.process(InputImage.fromBitmap(padded, 0)) }
            catch (error: Throwable) { padded.recycle(); throw error }
            lastTask = task
            task.addOnCompleteListener(direct) { padded.recycle() }
            return task.await()
        }
        var firstText = ""
        var first = FooterFields.Values()
        var scaled: Bitmap? = null
        var contrast: Bitmap? = null
        try {
            val recognized = recognize(crop)
            firstText = readingOrder(recognized)
            first = FooterFields.parse(firstText)
            if (first.complete) return Result(first, firstText)

            // Crop only within the footer to the actual detected ink. This gives small footer
            // text more pixels without enlarging the entire page or inventing missing glyphs.
            val bounds = recognized.textBlocks.flatMap { it.lines }.mapNotNull { it.boundingBox }
            // Keep the full vertical footer: a missing upper label must not disappear merely
            // because the first pass only recognized the bottom phone line.
            val margin = max(44, crop.width / 8)
            val region = if (bounds.isEmpty() || (first.branch.isBlank() && first.requester.isBlank()))
                Rect(0, 0, crop.width, crop.height) else Rect(
                (bounds.minOf { it.left } - 20 - margin).coerceIn(0, crop.width - 1),
                0,
                (bounds.maxOf { it.right } - 20 + margin).coerceIn(1, crop.width),
                crop.height
            )
            val focused = Bitmap.createBitmap(crop, region.left, region.top, region.width(), region.height())
            try {
                val factor = (2600f / focused.width).coerceIn(1f, 3f)
                    .coerceAtMost(1800f / focused.height)
                scaled = Bitmap.createScaledBitmap(focused,
                    (focused.width * factor).toInt().coerceAtLeast(1),
                    (focused.height * factor).toInt().coerceAtLeast(1), true)
                contrast = CellImageProcessing.contrast(scaled!!)
                val retryText = readingOrder(recognize(contrast!!))
                return Result(FooterFields.reconcile(first, FooterFields.parse(retryText)),
                    listOf(firstText, retryText).filter(String::isNotBlank).distinct().joinToString("\n"))
            } finally { if (focused !== crop && focused !== scaled) focused.recycle() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed optional retry must not discard values already read in this same region.
            return Result(first, firstText)
        } finally {
            lastTask?.addOnCompleteListener(direct) { client.close() } ?: client.close()
            contrast?.let { if (it !== scaled && it !== crop && !it.isRecycled) it.recycle() }
            scaled?.let { if (it !== crop && !it.isRecycled) it.recycle() }
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun readingOrder(text: Text): String {
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.centerY() }) {
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf { previous ->
                val other = previous.first().boundingBox!!
                abs(other.centerY() - box.centerY()) < max(other.height(), box.height()) * 0.55f
            }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } }
    }
}
