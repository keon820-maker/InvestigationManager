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

/**
 * v0.26 최종 보정.
 * - 관리번호 앞 i/I/ㅣ 등의 OCR 잡음 제거
 * - 보보증금 -> 보증금 중복 보정
 * - 하단 영업점명/전화/Fax를 넓은 영역에서 최종 재OCR
 */
object FinalOcrRepairV26 {
    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val rawManagement = extractManagementNo(base.rawText)
        var fixed = base.parsed.copy(
            managementNo = rawManagement.ifBlank { cleanManagementNo(base.parsed.managementNo) },
            requestNotes = cleanNotes(base.parsed.requestNotes),
            branch = normalizeBranch(base.parsed.branch).ifBlank { base.parsed.branch }
        )

        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull()
            ?: return finish(base, fixed, "")
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) {
            if (!normalized.bitmap.isRecycled) normalized.bitmap.recycle()
            return finish(base, fixed, "")
        }

        val source = normalized.bitmap
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val top = (source.height * 0.66f).toInt().coerceIn(0, source.height - 2)
            val bottom = (source.height * 0.985f).toInt().coerceIn(top + 1, source.height)
            val crop = Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
            val footerText = try {
                val plain = recognize(client, crop).text
                val enhanced = OcrImageEnhancer.enhance(crop)
                val enhancedText = try {
                    if (enhanced === crop) "" else recognize(client, enhanced).text
                } finally {
                    if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
                }
                listOf(plain, enhancedText).filter { it.isNotBlank() }.joinToString("\n")
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }

            val branchCandidate = extractBranch(footerText)
            val footerFocus = footerWindow(footerText)
            val allPhones = phonePattern.findAll(normalizeDigits(footerFocus))
                .map { normalizePhone(it.value) }
                .filter { it.isNotBlank() }
                .filterNot { phone -> phone in excludedPhones(fixed) }
                .distinct()
                .toList()

            val branchPhone = findLabeledPhone(footerFocus, listOf("전화번호", "전 화 번 호", "영업점 전화"))
                .takeIf { it.isNotBlank() && it !in excludedPhones(fixed) }
                ?: allPhones.getOrNull(0).orEmpty()
            val branchFax = findLabeledPhone(footerFocus, listOf("Fax", "FAX", "팩스"))
                .takeIf { it.isNotBlank() && it != branchPhone && it !in excludedPhones(fixed) }
                ?: allPhones.firstOrNull { it != branchPhone }.orEmpty()

            fixed = fixed.copy(
                branch = when {
                    validBranch(branchCandidate) -> branchCandidate
                    validBranch(normalizeBranch(fixed.branch)) -> normalizeBranch(fixed.branch)
                    else -> fixed.branch
                },
                branchPhone = branchPhone.ifBlank { fixed.branchPhone },
                branchFax = branchFax.ifBlank { fixed.branchFax },
                requestNotes = cleanNotes(fixed.requestNotes),
                managementNo = extractManagementNo(base.rawText).ifBlank { cleanManagementNo(fixed.managementNo) }
            )

            finish(base, fixed, buildString {
                append("\n\n--- 최종 중요필드 보정 v0.26 ---\n")
                append("관리번호 확정 : ").append(fixed.managementNo).append('\n')
                append("영업점 영역 : ").append(footerFocus.replace('\n', ' ').take(900)).append('\n')
                append("영업점 확정 : ").append(fixed.branch).append('\n')
                append("영업점 전화 : ").append(fixed.branchPhone).append('\n')
                append("영업점 Fax : ").append(fixed.branchFax).append('\n')
                append("기타요청사항 확정 : ").append(fixed.requestNotes.replace('\n', ' ')).append('\n')
            })
        } finally {
            client.close()
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun finish(base: OcrService.OcrResult, fixed: kr.co.investigation.manager.data.InvestigationCase, debug: String): OcrService.OcrResult {
        if (fixed == base.parsed && debug.isBlank()) return base
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + debug,
            preprocessMessage = if (debug.isBlank()) base.preprocessMessage else base.preprocessMessage + " / 최종 중요필드 보정 v0.26"
        )
    }

    private fun extractManagementNo(raw: String): String {
        val region = "(?:서울|경기|인천|부산|대구|광주|대전|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)"
        return Regex("$region\\s*20\\d{4}\\s*[-–]\\s*\\d{3,8}")
            .findAll(raw)
            .map { it.value.replace(Regex("\\s+"), "").replace('–', '-') }
            .firstOrNull()
            .orEmpty()
    }

    private fun cleanManagementNo(value: String): String {
        var s = value.replace(Regex("\\s+"), "").trim()
        s = s.replace(Regex("^[iIlL|!ㅣ]+(?=[가-힣0-9])"), "")
        s = s.replace('–', '-')
        return s
    }

    private fun cleanNotes(value: String): String {
        var s = value
        repeat(3) {
            s = s.replace(Regex("보\\s*보\\s*증\\s*금", RegexOption.IGNORE_CASE), "보증금")
                .replace(Regex("보증금\\s*보증금", RegexOption.IGNORE_CASE), "보증금")
        }
        s = s.replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
            .replace(Regex("보증금\\s*[:：]\\s*[oO이Il|]\\b"), "보증금:0")
            .replace(Regex("월\\s*임차료\\s*[:：]\\s*[oO이Il|]\\b"), "월임차료:0")
        return s.lines().joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }.trim()
    }

    private fun extractBranch(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val prioritized = lines.filter { it.contains("영업점") || it.contains("지점") } + lines
        return prioritized.asSequence()
            .map(::normalizeBranch)
            .firstOrNull(::validBranch)
            .orEmpty()
    }

    private fun normalizeBranch(raw: String): String {
        if (raw.isBlank()) return ""
        val parts = raw.split("||", "|", "\n")
        for (part0 in parts) {
            var part = part0
                .replace(Regex("[▷>]+"), " ")
                .replace(Regex(".*?(?:농\\s*협\\s*)?영\\s*업\\s*점\\s*[:：]?", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("^\\s*협\\s*[:：]?\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val compact = part.replace(" ", "")
            val match = Regex("[가-힣0-9]{2,18}?지점").find(compact)?.value ?: continue
            val cleaned = match.removePrefix("협").removePrefix("점").trim()
            if (validBranch(cleaned)) return cleaned
        }
        return ""
    }

    private fun validBranch(value: String): Boolean {
        val s = value.replace(" ", "")
        if (s.length !in 4..24 || !s.endsWith("지점")) return false
        val bad = listOf("영업점", "전화번호", "조사의뢰", "신청인", "팩스")
        return bad.none { s.contains(it) }
    }

    private fun footerWindow(text: String): String {
        val compactIndex = listOf("농협영업점", "영업점").mapNotNull { label ->
            text.indexOf(label).takeIf { it >= 0 }
        }.minOrNull()
        if (compactIndex == null) return text.takeLast(1800)
        val from = (compactIndex - 120).coerceAtLeast(0)
        return text.substring(from).take(1800)
    }

    private fun findLabeledPhone(text: String, labels: List<String>): String {
        val fixed = normalizeDigits(text)
        labels.forEach { label ->
            val regex = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            val m = Regex(regex, RegexOption.IGNORE_CASE).find(fixed) ?: return@forEach
            val start = m.range.last + 1
            val window = fixed.substring(start, (start + 120).coerceAtMost(fixed.length))
            val phone = phonePattern.find(window)?.value?.let(::normalizePhone).orEmpty()
            if (phone.isNotBlank()) return phone
        }
        return ""
    }

    private fun excludedPhones(c: kr.co.investigation.manager.data.InvestigationCase): Set<String> = setOf(
        c.phone, c.mobile, c.ownerPhone, c.investigatorPhone, c.investigatorFax
    ).filter { it.isNotBlank() }.toSet()

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun normalizeDigits(value: String): String = value.uppercase()
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1')

    private val phonePattern = Regex("\\(?0\\d{1,2}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")

    private fun normalizePhone(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }
}
