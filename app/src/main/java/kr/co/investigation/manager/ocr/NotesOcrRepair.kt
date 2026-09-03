package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 기타요청사항 전용 재검증.
 * 고정 셀 crop이 섹션 제목까지 포함하면서 첫 "8/14"의 8/를 놓치는 현상을 줄이기 위해
 * 요청사항 블록 전체를 넓게 다시 OCR하고, 실제 날짜(M/D)로 시작하는 줄부터 값으로 채택한다.
 */
object NotesOcrRepair {
    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull() ?: return base
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) return base

        val source = normalized.bitmap
        // 실제 정규화 양식에서 3. 기타요청사항 제목 + 본문 박스를 넉넉히 포함한다.
        val left = (source.width * 0.035f).toInt().coerceIn(0, source.width - 2)
        val right = (source.width * 0.965f).toInt().coerceIn(left + 1, source.width)
        val top = (source.height * 0.655f).toInt().coerceIn(0, source.height - 2)
        val bottom = (source.height * 0.805f).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val raw = recognize(client, crop).text
            val enhanced = OcrImageEnhancer.enhance(crop)
            val enhancedRaw = try {
                if (enhanced === crop) "" else recognize(client, enhanced).text
            } finally {
                if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
            }

            val candidates = listOf(raw, enhancedRaw)
                .map(::extractNotes)
                .filter { it.isNotBlank() }
                .sortedByDescending { score(it) }
            val best = candidates.firstOrNull().orEmpty()
            val fixedNotes = if (best.isNotBlank() && score(best) > score(base.parsed.requestNotes)) best else base.parsed.requestNotes

            if (fixedNotes == base.parsed.requestNotes) base else base.copy(
                parsed = base.parsed.copy(requestNotes = fixedNotes),
                rawText = base.rawText + buildString {
                    append("\n\n--- 기타요청사항 재검증 v0.15 ---\n")
                    append("확정 : ").append(fixedNotes.replace('\n', ' ')).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 기타요청사항 블록 재검증 v0.15"
            )
        } finally {
            if (!crop.isRecycled) crop.recycle()
            client.close()
        }
    }

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap) =
        suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun extractNotes(text: String): String {
        val lines = text.lines()
            .map { it.replace(Regex("[\\t ]+"), " ").trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // 가장 신뢰할 수 있는 시작점: 8/14 같은 M/D. 문서마다 날짜가 달라도 일반적으로 처리한다.
        val dateIndex = lines.indexOfFirst { Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(it) }
        val start = when {
            dateIndex >= 0 -> dateIndex
            else -> lines.indexOfFirst { it.contains("대출실행") || it.contains("현장조사") }
        }
        if (start < 0) return ""

        val selected = lines.drop(start)
            .takeWhile { line ->
                val c = line.replace(Regex("\\s+"), "")
                !c.contains("농협영업점") && !c.contains("조사의뢰자") && !c.contains("전화번호") && !c.contains("신청인")
            }
            .take(6)
            .joinToString("\n")

        var s = selected
            .replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
            .replace(Regex("(^|\\s)임차료\\s*[:：]"), "$1월임차료:")
            .replace(Regex("월임차료\\s*[:：]\\s*[oO]\\b"), "월임차료:0")
            .replace(Regex("보증금\\s*[:：]\\s*[oO]\\b"), "보증금:0")
            .trim()
        return s.take(700)
    }

    private fun score(value: String): Int {
        var s = 0
        if (Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(value)) s += 8
        if (value.contains("대출실행")) s += 4
        if (value.contains("현장조사")) s += 3
        if (value.contains("보증금")) s += 2
        if (value.contains("월임차료")) s += 2
        s += (value.length / 40).coerceAtMost(4)
        if (value.contains("기타요청사항")) s -= 3
        return s
    }
}
