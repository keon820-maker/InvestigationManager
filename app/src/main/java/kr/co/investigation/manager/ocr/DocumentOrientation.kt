package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.math.max

/** On-device, memory-only rotation. The caller owns the input and the returned bitmap. */
object DocumentOrientation {
    data class Result(val bitmap: Bitmap, val clockwiseDegrees: Int, val confident: Boolean)

    private val directExecutor = Executor { it.run() }

    suspend fun correct(source: Bitmap): Result {
        var output: Bitmap? = null
        try {
            return withContext(Dispatchers.Default) {
                val decision = detect(source)
                currentCoroutineContext().ensureActive()
                val degrees = decision?.clockwiseDegrees ?: 0
                val corrected = if (degrees == 0) source else rotate(source, degrees)
                output = corrected
                Result(corrected, degrees, decision != null)
            }
        } catch (t: Throwable) {
            // Dispatcher return can be cancelled after allocating the rotated image.
            output?.let { if (it !== source && !it.isRecycled) it.recycle() }
            throw t
        }
    }

    private suspend fun detect(source: Bitmap): DocumentOrientationScore.Candidate? {
        val ratio = (1800f / max(source.width, source.height)).coerceAtMost(1f)
        val probe = if (ratio < 1f) Bitmap.createScaledBitmap(
            source, (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true
        ) else source
        val client = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
        catch (t: Throwable) {
            if (probe !== source) probe.recycle()
            throw t
        }
        var lastTask: Task<Text>? = null
        try {
            val candidates = mutableListOf<DocumentOrientationScore.Candidate>()
            for (degrees in listOf(0, 90, 180, 270)) {
                currentCoroutineContext().ensureActive()
                // Always give ML Kit its own bitmap: a cancelled await must not recycle a
                // bitmap which its native recognizer is still reading.
                val candidate = if (degrees == 0) probe.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("방향 확인용 이미지를 만들 수 없습니다.") else rotate(probe, degrees)
                val height = candidate.height
                val task = try {
                    client.process(InputImage.fromBitmap(candidate, 0))
                } catch (t: Throwable) {
                    candidate.recycle()
                    throw t
                }
                lastTask = task
                task.addOnCompleteListener(directExecutor) { candidate.recycle() }
                val text = task.await()
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { bounds ->
                        DocumentOrientationScore.Line(line.text, bounds.exactCenterY() / height, line.angle)
                    }
                }
                val score = DocumentOrientationScore.score(degrees, lines)
                candidates += score
                // The common upright page needs just one small pass.
                if (DocumentOrientationScore.isClearlyUpright(score)) return score
            }
            return DocumentOrientationScore.choose(candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            // If the UI leaves during recognition, close only after the native task finishes.
            lastTask?.addOnCompleteListener(directExecutor) { client.close() } ?: client.close()
            if (probe !== source && !probe.isRecycled) probe.recycle()
        }
    }

    internal fun rotate(source: Bitmap, clockwiseDegrees: Int): Bitmap = Bitmap.createBitmap(
        source, 0, 0, source.width, source.height,
        Matrix().apply { postRotate(clockwiseDegrees.toFloat()) }, true
    )
}
