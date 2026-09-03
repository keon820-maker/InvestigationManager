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

/** 기타요청사항 전용 재검증. */
object NotesOcrRepair {
    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull() ?: return base
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) return base

        val source = normalized.bitmap
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

            // 전용 crop에서 날짜+요청 문장이 잡혔다면, 길이가 더 긴 전체 OCR 결과보다 우선한다.
            // 전체 OCR은 표의 보증금/월임차료/소유자 셀까지 뒤에 붙어 점수가 오르는 문제가 있었다.
            val fixedNotes = when {
                isDedicatedNotes(best) -> best
                best.isNotBlank() && score(best) > score(base.parsed.requestNotes) -> best
                else -> clean(base.parsed.requestNotes)
            }

            if (fixedNotes == base.parsed.requestNotes) base else base.copy(
                parsed = base.parsed.copy(requestNotes = fixedNotes),
                rawText = base.rawText + buildString {
                    append("\n\n--- 기타요청사항 재검증 v0.18 ---\n")
                    append("확정 : ").append(fixedNotes.replace('\n', ' ')).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 기타요청사항 전용 OCR v0.18"
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

        val dateIndex = lines.indexOfFirst { Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(it) }
        val start = when {
            dateIndex >= 0 -> dateIndex
            else -> lines.indexOfFirst { it.contains("대출실행") || it.contains("현장조사") }
        }
        if (start < 0) return ""

        val selected = lines.drop(start)
            .takeWhile { line -> !isForeignFormRow(line) }
            .take(7)
            .joinToString("\n")

        return clean(selected).take(700)
    }

    private fun isForeignFormRow(line: String): Boolean {
        val c = line.replace(Regex("\\s+"), "")
        return listOf(
            "농협영업점", "조사의뢰자", "전화번호", "신청인", "보증금", "월임차료",
            "물건소유자", "주민번호", "소유자주소", "팩스"
        ).any { c.contains(it) }
    }

    private fun clean(value: String): String {
        var s = value.trim()
        s = s.replace(
            Regex("\\s*(?:보증금|증금)\\s*[:：]?\\s*[^\\n]{0,20}?(?:월\\s*임차료|임차료)\\s*[:：]?\\s*[^\\n]{0,20}$"),
            ""
        )
        s = s.replace(Regex("\\s*(?:보증금|증금)\\s*[:：]?\\s*[0Oo이Il|]?\\s*$"), "")
        s = s.replace(Regex("\\s*(?:월\\s*임차료|임차료)\\s*[:：]?\\s*[0Oo이Il|]?\\s*$"), "")
        s = s.replace(Regex("([.!?])(?=[가-힣])"), "$1 ")
        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
        s = s.lines().joinToString("\n") { it.trim() }.trim()
        return s
    }

    private fun isDedicatedNotes(value: String): Boolean {
        if (!Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(value)) return false
        return value.contains("대출실행") || value.contains("현장조사") || value.contains("방문")
    }

    private fun score(value: String): Int {
        var s = 0
        if (Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(value)) s += 8
        if (value.contains("대출실행")) s += 4
        if (value.contains("현장조사")) s += 3
        if (value.contains("방문")) s += 2
        s += (value.length / 40).coerceAtMost(4)
        if (value.contains("기타요청사항")) s -= 3
        if (value.contains("보증금")) s -= 5
        if (value.contains("월임차료")) s -= 5
        if (value.contains("물건소유자")) s -= 5
        return s
    }
}
