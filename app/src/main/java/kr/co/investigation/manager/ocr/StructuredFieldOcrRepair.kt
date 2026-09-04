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
 * 고정양식에서 자주 오인식되는 중요 필드를 해당 위치에서만 다시 읽는다.
 * 다른 행에서 나온 문자열이 필드를 덮어쓰지 못하도록 영역을 강제한다.
 */
object StructuredFieldOcrRepair {
    private const val W = 2480f
    private const val H = 3508f

    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int)
    private data class Owner(val name: String = "", val resident: String = "")

    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull() ?: return base
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) {
            if (!normalized.bitmap.isRecycled) normalized.bitmap.recycle()
            return base
        }

        val source = normalized.bitmap
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val ownerRaw = readBox(client, source, Box(700, 1405, 1585, 1580))
            val owner = extractOwner(ownerRaw)

            val ownerAddressRaw = readBox(client, source, Box(430, 1515, 2390, 1710))
            val ownerAddress = bestAddress(ownerAddressRaw)

            val investigationRaw = readBox(client, source, Box(430, 1110, 1560, 1270))
            val investigationType = normalizeInvestigationType(investigationRaw)

            val notesRaw = readBox(client, source, Box(180, 2340, 2400, 2815))
            val notes = extractNotes(notesRaw)

            val footerRaw = readBox(client, source, Box(1040, 2760, 2400, 3265))
            val footerPhones = phonePattern.findAll(normalizeDigits(footerRaw))
                .map { normalizePhone(it.value) }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            val branchPhone = findLabeledPhone(footerRaw, Regex("전\\s*화\\s*번\\s*호\\s*[:：]?"))
                .ifBlank { footerPhones.getOrNull(0).orEmpty() }
            val branchFax = findLabeledPhone(footerRaw, Regex("팩\\s*스\\s*[:：]?"))
                .ifBlank { footerPhones.firstOrNull { it != branchPhone }.orEmpty() }

            val c = base.parsed
            val fixed = c.copy(
                ownerName = owner.name.ifBlank { c.ownerName.takeIf(::validName).orEmpty() },
                ownerResidentNo = owner.resident.ifBlank { c.ownerResidentNo },
                ownerAddress = ownerAddress.ifBlank { sanitizeAddress(c.ownerAddress) },
                investigationType = investigationType.ifBlank { c.investigationType },
                requestNotes = if (notesScore(notes) >= notesScore(c.requestNotes)) notes.ifBlank { c.requestNotes } else c.requestNotes,
                branchPhone = branchPhone.ifBlank { c.branchPhone.takeIf { isPlausibleFooterPhone(it, c) }.orEmpty() },
                branchFax = branchFax.ifBlank { c.branchFax.takeIf { isPlausibleFooterPhone(it, c) }.orEmpty() }
            )

            if (fixed == c) base else base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 중요필드 위치 한정 재OCR v0.23 ---\n")
                    append("소유자 영역 : ").append(ownerRaw.replace('\n', ' ')).append('\n')
                    append("소유자 확정 : ").append(fixed.ownerName).append(" / ").append(fixed.ownerResidentNo).append('\n')
                    append("소유자주소 영역 : ").append(ownerAddressRaw.replace('\n', ' ')).append('\n')
                    append("소유자주소 확정 : ").append(fixed.ownerAddress).append('\n')
                    append("조사구분 영역 : ").append(investigationRaw.replace('\n', ' ')).append('\n')
                    append("조사구분 확정 : ").append(fixed.investigationType).append('\n')
                    append("기타요청사항 확정 : ").append(fixed.requestNotes.replace('\n', ' ')).append('\n')
                    append("하단 연락처 영역 : ").append(footerRaw.replace('\n', ' ')).append('\n')
                    append("영업점 전화 : ").append(fixed.branchPhone).append('\n')
                    append("영업점 Fax : ").append(fixed.branchFax).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 중요필드 위치 한정 재OCR v0.23"
            )
        } finally {
            client.close()
            if (!source.isRecycled) source.recycle()
        }
    }

    private suspend fun readBox(client: TextRecognizer, source: Bitmap, box: Box): String {
        val sx = source.width / W
        val sy = source.height / H
        val l = (box.l * sx).toInt().coerceIn(0, source.width - 2)
        val t = (box.t * sy).toInt().coerceIn(0, source.height - 2)
        val r = (box.r * sx).toInt().coerceIn(l + 1, source.width)
        val b = (box.b * sy).toInt().coerceIn(t + 1, source.height)
        val crop = Bitmap.createBitmap(source, l, t, r - l, b - t)
        return try {
            val raw = recognize(client, crop).text
            val enhanced = OcrImageEnhancer.enhance(crop)
            val enhancedRaw = try {
                if (enhanced === crop) "" else recognize(client, enhanced).text
            } finally {
                if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
            }
            listOf(raw, enhancedRaw)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun extractOwner(text: String): Owner {
        val fixed = normalizeDigits(text)
        val pair = Regex("([가-힣]{2,6})\\s*[(/]?\\s*(\\d{6})\\s*[-–]?\\s*([1-4*][0-9*]{0,6})?")
            .findAll(fixed)
            .map {
                Owner(
                    name = it.groupValues[1],
                    resident = buildString {
                        append(it.groupValues[2]).append('-')
                        append(it.groupValues.getOrNull(3).orEmpty())
                    }
                )
            }
            .firstOrNull { validName(it.name) }
        if (pair != null) return pair

        val names = Regex("[가-힣]{2,6}").findAll(text)
            .map { it.value }
            .filter(::validName)
            .toList()
        val resident = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*][0-9*]{0,6})?")
            .find(fixed)
            ?.let { "${it.groupValues[1]}-${it.groupValues.getOrNull(2).orEmpty()}" }
            .orEmpty()
        return Owner(names.firstOrNull().orEmpty(), resident)
    }

    private fun validName(value: String): Boolean {
        if (!Regex("[가-힣]{2,6}").matches(value)) return false
        val bad = setOf("성명", "연락처", "소유자", "물건소유자", "전화번호", "주민번호", "주소")
        return value !in bad && !value.contains("소유") && !value.contains("연락") && !value.contains("전화")
    }

    private fun bestAddress(text: String): String = text.lines()
        .map(::sanitizeAddress)
        .filter { it.isNotBlank() }
        .maxByOrNull(::addressScore)
        .orEmpty()
        .ifBlank { sanitizeAddress(text) }

    private fun sanitizeAddress(value: String): String {
        var s = value
            .replace(Regex("소\\s*유\\s*자\\s*주\\s*소\\s*[:：]?", RegexOption.IGNORE_CASE), " ")
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val region = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기(?:도)?|강원(?:도)?|충북|충남|전북|전남|경북|경남|제주(?:도)?)")
        val regionMatch = region.find(s) ?: return ""
        val postal = Regex("\\d{5,6}").findAll(s.substring(0, regionMatch.range.first)).lastOrNull()
        val start = postal?.range?.first ?: regionMatch.range.first
        s = s.substring(start).trim()

        val endTokens = Regex("(?:\\d+호|\\d+동|\\d+층|\\d+번지|\\d+(?:-\\d+)?)").findAll(s).toList()
        val last = endTokens.lastOrNull()
        if (last != null && last.range.last + 1 < s.length) {
            val tail = s.substring(last.range.last + 1).trim()
            val suspiciousTail = tail.length >= 4 && (
                tail.count { it in 'A'..'Z' || it in 'a'..'z' } >= 3 ||
                    tail.contains("ocn", true) || tail.contains("UIU", true)
                )
            if (suspiciousTail) s = s.substring(0, last.range.last + 1).trim()
        }

        return s.takeIf { addressScore(it) >= 4 }.orEmpty()
    }

    private fun addressScore(value: String): Int {
        if (value.length < 8) return 0
        var score = 0
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(value)) score += 3
        if (Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)").containsMatchIn(value)) score += 4
        if (Regex("[가-힣]+(?:시|군|구)").containsMatchIn(value)) score += 2
        if (Regex("[가-힣0-9]+(?:로|길|동|읍|면|리)").containsMatchIn(value)) score += 2
        if (Regex("\\d+(?:동|호|층|번지|-\\d+)").containsMatchIn(value)) score += 2
        score += (value.length / 25).coerceAtMost(3)
        return score
    }

    private fun normalizeInvestigationType(value: String): String {
        val s = value.replace(Regex("\\s+"), "")
            .replace("＋", "+")
            .replace("조시", "조사")
        val parts = mutableListOf<String>()
        if (s.contains("담보")) parts += "담보조사"
        if (s.contains("열람")) parts += "열람조사"
        if (s.contains("임대차")) parts += "임대차조사"
        if (parts.isNotEmpty()) return parts.distinct().joinToString("+")
        return Regex("[가-힣]{2,12}조사").find(s)?.value.orEmpty()
    }

    private fun extractNotes(text: String): String {
        val lines = text.lines()
            .map { it.replace(Regex("[\\t ]+"), " ").trim() }
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val c = line.replace(" ", "")
                c.contains("농협영업점") || c.contains("조사의뢰자") || c.contains("신청인")
            }
            .take(10)
        if (lines.isEmpty()) return ""

        val start = lines.indexOfFirst {
            Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(it) ||
                it.contains("현장") || it.contains("방문") || it.contains("대출실행")
        }.takeIf { it >= 0 } ?: 0

        return lines.drop(start)
            .joinToString("\n")
            .replace(Regex("증금\\s*[:：]"), "보증금:")
            .replace(Regex("월\\s*임차료\\s*[:：]?"), "월임차료:")
            .trim()
            .take(700)
    }

    private fun notesScore(value: String): Int {
        if (value.isBlank()) return 0
        var score = (value.length / 30).coerceAtMost(8)
        if (value.contains("방문")) score += 3
        if (value.contains("현장")) score += 2
        if (value.contains("보증금")) score += 3
        if (value.contains("월임차료")) score += 3
        if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(value)) score += 2
        return score
    }

    private fun findLabeledPhone(text: String, label: Regex): String {
        val fixed = normalizeDigits(text)
        val m = label.find(fixed) ?: return ""
        val from = m.range.last + 1
        val window = fixed.substring(from, (from + 100).coerceAtMost(fixed.length))
        return phonePattern.find(window)?.value?.let(::normalizePhone).orEmpty()
    }

    private fun isPlausibleFooterPhone(value: String, c: kr.co.investigation.manager.data.InvestigationCase): Boolean {
        if (!Regex("0\\d{1,2}-\\d{3,4}-\\d{4}").matches(value)) return false
        return value != c.phone && value != c.mobile && value != c.ownerPhone &&
            value != c.investigatorPhone && value != c.investigatorFax
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

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }
}
