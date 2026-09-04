package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 상단 조사담당자 Tel/Fax 전용 재OCR. */
object HeaderContactOcrRepair {
    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        if (base.parsed.investigatorPhone.isNotBlank() && base.parsed.investigatorFax.isNotBlank()) return base

        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull() ?: return base
        val source = normalized.bitmap
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val top = (source.height * 0.27f).toInt().coerceAtLeast(1)
            val crop = Bitmap.createBitmap(source, 0, 0, source.width, top)
            val raw = try { recognize(client, crop).text } finally { crop.recycle() }
            val candidates = phonePattern.findAll(raw)
                .map { normalizePhone(it.value) }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            val tel = base.parsed.investigatorPhone.ifBlank {
                findLabeled(raw, Regex("(?i)T\\s*[e3]\\s*[l1I]\\s*[)）:：]?"))
                    .ifBlank { candidates.getOrNull(0).orEmpty() }
            }
            val fax = base.parsed.investigatorFax.ifBlank {
                findLabeled(raw, Regex("(?i)F\\s*[a4]\\s*[xX×]\\s*[)）:：]?"))
                    .ifBlank { candidates.firstOrNull { it != tel }.orEmpty() }
            }

            val fixed = base.parsed.copy(investigatorPhone = tel, investigatorFax = fax)
            if (fixed == base.parsed) base else base.copy(
                parsed = fixed,
                rawText = base.rawText + "\n\n--- 상단 연락처 재OCR v0.22 ---\n조사담당자 Tel : $tel\n조사담당자 Fax : $fax\n상단 OCR : $raw\n",
                preprocessMessage = base.preprocessMessage + " / 조사담당자 연락처 상단 재OCR v0.22"
            )
        } finally {
            client.close()
            if (!source.isRecycled) source.recycle()
        }
    }

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun findLabeled(text: String, label: Regex): String {
        val m = label.find(text) ?: return ""
        val from = m.range.last + 1
        val window = text.substring(from, (from + 90).coerceAtMost(text.length))
        return phonePattern.find(window)?.value?.let(::normalizePhone).orEmpty()
    }

    private val phonePattern = Regex("\\(?0\\d{1,2}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")

    private fun normalizePhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when (digits.length) {
            9 -> "${digits.take(2)}-${digits.substring(2, 5)}-${digits.takeLast(4)}"
            10 -> if (digits.startsWith("02")) "02-${digits.substring(2, 6)}-${digits.takeLast(4)}"
                  else "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            11 -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.takeLast(4)}"
            else -> ""
        }
    }
}
