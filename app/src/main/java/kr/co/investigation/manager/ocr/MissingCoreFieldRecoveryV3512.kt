package kr.co.investigation.manager.ocr

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/**
 * v0.35.12: 전체 결과가 대체로 정상이어도 소유자 신원이나 하단 영업점 정보만 비는 문서를 보강한다.
 * 기존 정상 필드는 덮어쓰지 않고, 전체 페이지의 실제 라벨 위치와 기존 공간 파서를 보조 후보로 사용한다.
 */
object MissingCoreFieldRecoveryV3512 {
    private data class Item(val text: String, val box: Rect) {
        val cy: Float get() = box.exactCenterY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private data class FooterContacts(val phone: String = "", val fax: String = "")

    suspend fun repair(
        normalized: DocumentNormalizer.Result,
        base: OcrService.OcrResult
    ): OcrService.OcrResult {
        if (!needsRecovery(base.parsed)) return base
        if (normalized.bitmap.width < 1500 || normalized.bitmap.height < 2200) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val full = recognize(client, normalized.bitmap)
            val spatial = SpatialFormParser.parse(full, normalized.bitmap.width, normalized.bitmap.height).parsed
            val fallback = FixedTemplateOcr.parseFallback(full.text)
            val branchFromRow = extractBranchRow(full, normalized.bitmap.height)
            val footer = extractFooterContacts(full, normalized.bitmap.height)

            val current = base.parsed
            val ownerName = current.ownerName.takeIf(::validName)
                ?: spatial.ownerName.takeIf(::validName)
                ?: fallback.ownerName.takeIf(::validName)
                ?: ""
            val ownerResident = current.ownerResidentNo.takeIf(::validResident)
                ?: spatial.ownerResidentNo.takeIf(::validResident)
                ?: fallback.ownerResidentNo.takeIf(::validResident)
                ?: ""

            val normalizedCurrentBranch = FinalResultConsistencyV3512.normalizeBranch(current.branch)
            val branch = normalizedCurrentBranch.takeIf(::validBranch)
                ?: branchFromRow.takeIf(::validBranch)
                ?: FinalResultConsistencyV3512.normalizeBranch(spatial.branch).takeIf(::validBranch)
                ?: FinalResultConsistencyV3512.normalizeBranch(fallback.branch).takeIf(::validBranch)
                ?: ""

            val branchPhone = current.branchPhone.takeIf(::validPhone)
                ?: footer.phone.takeIf(::validPhone)
                ?: spatial.branchPhone.takeIf(::validPhone)
                ?: fallback.branchPhone.takeIf(::validPhone)
                ?: ""
            val branchFax = current.branchFax.takeIf(::validPhone)
                ?: footer.fax.takeIf(::validPhone)
                ?: spatial.branchFax.takeIf(::validPhone)
                ?: fallback.branchFax.takeIf(::validPhone)
                ?: ""

            val fixed = current.copy(
                managementNo = FinalResultConsistencyV3512.normalizeManagement(current.managementNo),
                ownerName = ownerName,
                ownerResidentNo = ownerResident,
                branch = branch,
                branchPhone = branchPhone,
                branchFax = branchFax
            )

            // 이 표식은 바로 뒤 SpatialLeakRepair가 상단 담당자 번호/교차 필드 누수를 다시 검증하도록 한다.
            base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 누락 핵심필드 라벨 좌표 최종 복구 v0.35.12 ---\n")
                    append("관리번호 : ").append(fixed.managementNo).append('\n')
                    append("소유자 : ").append(fixed.ownerName).append(" / ").append(fixed.ownerResidentNo).append('\n')
                    append("영업점 : ").append(fixed.branch).append('\n')
                    append("영업점 전화/Fax : ").append(fixed.branchPhone).append(" / ").append(fixed.branchFax).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 라벨 좌표 최종 복구 보강 v0.35.12"
            )
        } finally {
            client.close()
        }
    }

    internal fun needsRecovery(c: InvestigationCase): Boolean {
        val management = FinalResultConsistencyV3512.normalizeManagement(c.managementNo)
        return management != c.managementNo.replace(Regex("\\s+"), "").trim() ||
            !validName(c.ownerName) ||
            !validResident(c.ownerResidentNo) ||
            !validBranch(FinalResultConsistencyV3512.normalizeBranch(c.branch)) ||
            !validPhone(c.branchPhone) ||
            !validPhone(c.branchFax)
    }

    private fun extractBranchRow(text: Text, imageHeight: Int): String {
        val items = items(text)
        val anchor = items
            .filter { it.cy >= imageHeight * 0.68f }
            .firstOrNull {
                val c = compact(it.text)
                c.contains("농협영업점") || c.contains("영업점")
            } ?: return ""
        val row = rowAround(items, anchor, imageHeight)
            .joinToString(" | ") { it.text }
            .replace(labelRegex("농협영업점"), " ")
            .replace(labelRegex("영업점"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return FinalResultConsistencyV3512.normalizeBranch(row)
    }

    private fun extractFooterContacts(text: Text, imageHeight: Int): FooterContacts {
        val items = items(text)
        val anchor = items
            .filter { it.cy >= imageHeight * 0.70f }
            .firstOrNull {
                val c = compact(it.text)
                c.contains("전화번호") || c.contains("팩스") || c.contains("FAX", ignoreCase = true)
            } ?: return FooterContacts()

        val joined = rowAround(items, anchor, imageHeight)
            .joinToString(" | ") { it.text }
        val all = phones(joined)
        if (all.isEmpty()) return FooterContacts()

        val faxMatch = Regex("팩\\s*스|F\\s*A\\s*X", RegexOption.IGNORE_CASE).find(joined)
        if (faxMatch != null) {
            val before = phones(joined.substring(0, faxMatch.range.first))
            val after = phones(joined.substring(faxMatch.range.last + 1))
            return FooterContacts(
                phone = before.lastOrNull().orEmpty(),
                fax = after.firstOrNull().orEmpty()
            )
        }
        return FooterContacts(
            phone = all.getOrNull(0).orEmpty(),
            fax = all.getOrNull(1).orEmpty()
        )
    }

    private fun rowAround(items: List<Item>, anchor: Item, imageHeight: Int): List<Item> {
        val tolerance = max(anchor.h * 1.5f, imageHeight * 0.014f)
        return items.filter { abs(it.cy - anchor.cy) <= tolerance }.sortedBy { it.box.left }
    }

    private fun items(text: Text): List<Item> = text.textBlocks.flatMap { it.lines }
        .mapNotNull { line -> line.boundingBox?.let { Item(line.text.trim(), it) } }
        .filter { it.text.isNotBlank() }

    private fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val compact = Regex("(?<!\\d)0\\d{8,11}(?!\\d)")
            .findAll(fixed)
            .map { normalizePhone(it.value) }
        val separated = Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")
            .findAll(fixed)
            .map { normalizePhone(it.value) }
        return (compact + separated).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun normalizePhone(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 12 && d.startsWith("050") -> "${d.substring(0, 4)}-${d.substring(4, 8)}-${d.substring(8)}"
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 11 && d.substring(0, 3) in threeDigitPrefixes -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 && d.substring(0, 3) in threeDigitPrefixes -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun validPhone(value: String): Boolean = normalizePhone(value).isNotBlank()

    private fun validName(value: String): Boolean {
        val s = value.trim()
        if (!Regex("[가-힣]{2,6}").matches(s)) return false
        return listOf("전화", "번호", "소유", "연락", "주소", "영업", "요청", "완료").none { s.contains(it) }
    }

    private fun validResident(value: String): Boolean = Regex("\\d{6}-[1-4*]?[0-9*]{0,6}").matches(value.trim())

    private fun validBranch(value: String): Boolean {
        val s = value.replace(Regex("\\s+"), "")
        if (s.length !in 3..40) return false
        if (listOf("전화번호", "팩스", "조사의뢰자", "신청인").any { s.contains(it) }) return false
        return s.endsWith("지점") || s.endsWith("센터") || s.endsWith("출장소") || s.endsWith("<출>")
    }

    private fun labelRegex(label: String): Regex = Regex(
        label.map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")

    private val threeDigitPrefixes = setOf(
        "031", "032", "033", "041", "042", "043", "044",
        "051", "052", "053", "054", "055", "061", "062", "063", "064",
        "070", "080"
    )

    private suspend fun recognize(
        client: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: android.graphics.Bitmap
    ): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
}
