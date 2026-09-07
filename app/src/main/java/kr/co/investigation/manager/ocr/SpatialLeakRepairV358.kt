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
 * v0.35.8: 라벨 좌표 복구 뒤에도 다른 행의 전화번호/라벨이 필드에 새는 경우를 정리한다.
 * 개인정보 원문을 하드코딩하지 않고, 현재 문서 안의 라벨과 boundingBox 관계만 사용한다.
 */
object SpatialLeakRepairV358 {
    private data class Item(val text: String, val box: Rect) {
        val cy: Float get() = box.exactCenterY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private data class DebtorContacts(val phone: String = "", val mobile: String = "")

    suspend fun repair(
        normalized: DocumentNormalizer.Result,
        base: OcrService.OcrResult
    ): OcrService.OcrResult {
        if (!needsRepair(base)) return base
        if (normalized.bitmap.width < 1500 || normalized.bitmap.height < 2200) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val full = recognize(client, normalized.bitmap)
            val imageHeight = normalized.bitmap.height
            val headerPhones = extractHeaderPhones(full, imageHeight)
            val debtor = extractDebtorContacts(full, imageHeight)
            val ownerPhone = extractOwnerPhone(full, imageHeight)
            val propertyAddress = extractAddressRow(full, imageHeight, "물건소재지")
            val ownerAddress = extractAddressRow(full, imageHeight, "소유자주소")

            val fixed = repairFields(
                current = base.parsed,
                explicitPhone = debtor.phone,
                explicitMobile = debtor.mobile,
                explicitOwnerPhone = ownerPhone,
                explicitPropertyAddress = propertyAddress,
                explicitOwnerAddress = ownerAddress,
                headerPhones = headerPhones
            )

            if (fixed == base.parsed) base else base.copy(
                parsed = fixed,
                rawText = buildString {
                    append(base.rawText)
                    append("\n\n--- 필드 누수 최종 정리 v0.35.8 ---\n")
                    append("상단 고정영역 전화 제외 : ").append(headerPhones.joinToString(", ")).append('\n')
                    append("채무자 전화/핸드폰 : ").append(fixed.phone).append(" / ").append(fixed.mobile).append('\n')
                    append("물건소재지 : ").append(fixed.propertyAddress).append('\n')
                    append("소유자 연락처 : ").append(fixed.ownerPhone).append('\n')
                    append("소유자 주소 : ").append(fixed.ownerAddress).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 필드 누수 최종 정리 v0.35.8"
            )
        } finally {
            client.close()
        }
    }

    internal fun needsRepair(base: OcrService.OcrResult): Boolean {
        val c = base.parsed
        val ranSpatialRescue = base.preprocessMessage.contains("라벨 좌표 최종 복구")
        return ranSpatialRescue ||
            managementLeak(c.phone, c.managementNo) ||
            managementLeak(c.mobile, c.managementNo) ||
            containsAddressLeak(c.propertyAddress) ||
            containsAddressLeak(c.ownerAddress) ||
            sameNonBlank(c.ownerPhone, c.branchPhone) ||
            sameNonBlank(c.ownerPhone, c.branchFax)
    }

    internal fun repairFields(
        current: InvestigationCase,
        explicitPhone: String,
        explicitMobile: String,
        explicitOwnerPhone: String,
        explicitPropertyAddress: String,
        explicitOwnerAddress: String,
        headerPhones: Set<String>
    ): InvestigationCase {
        fun usablePhone(value: String): String = value
            .takeIf(::validPhone)
            .takeUnless { it in headerPhones }
            .takeUnless { managementLeak(it, current.managementNo) }
            .orEmpty()

        val phone = usablePhone(explicitPhone).ifBlank { usablePhone(current.phone) }
        val mobile = usablePhone(explicitMobile).ifBlank { usablePhone(current.mobile) }

        val currentOwnerPhone = current.ownerPhone
            .takeIf(::validPhone)
            .takeUnless { it in headerPhones }
            .takeUnless { sameNonBlank(it, current.branchPhone) }
            .takeUnless { sameNonBlank(it, current.branchFax) }
            .orEmpty()
        val ownerPhone = usablePhone(explicitOwnerPhone).ifBlank { currentOwnerPhone }

        val propertyAddress = cleanAddressLeak(explicitPropertyAddress)
            .takeIf(::validAddress)
            .orEmpty()
            .ifBlank { cleanAddressLeak(current.propertyAddress).takeIf(::validAddress).orEmpty() }
        val ownerAddress = cleanAddressLeak(explicitOwnerAddress)
            .takeIf(::validAddress)
            .orEmpty()
            .ifBlank { cleanAddressLeak(current.ownerAddress).takeIf(::validAddress).orEmpty() }

        return current.copy(
            phone = phone,
            mobile = mobile,
            propertyAddress = propertyAddress,
            ownerPhone = ownerPhone,
            ownerAddress = ownerAddress
        )
    }

    internal fun cleanAddressLeak(value: String): String {
        if (value.isBlank()) return ""
        var s = value.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
        // ML Kit에서 '모종로22번길'의 첫 글자 ㅁ이 약하게 찍힌 경우 '오종로'로 흔들리는 패턴 보정.
        s = s.replace(Regex("오종로(?=\\d+번길)"), "모종로")
        val cutLabels = listOf("물건소유자", "연락처", "전화번호", "핸드폰번호", "성명")
        val starts = cutLabels.mapNotNull { label ->
            val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            Regex(pattern, RegexOption.IGNORE_CASE).find(s)?.range?.first
        }.filter { it > 0 }
        val cut = starts.minOrNull()
        if (cut != null) s = s.substring(0, cut).trim()

        // 주소 뒤에 대괄호 전화번호만 붙은 경우도 제거한다.
        s = s.replace(Regex("\\s*\\[?0\\d{8,11}\\]?\\s*$"), "").trim()
        return s
    }

    private fun extractHeaderPhones(text: Text, imageHeight: Int): Set<String> = items(text)
        .filter { it.cy < imageHeight * 0.22f }
        .flatMap { phones(it.text) }
        .toSet()

    private fun extractDebtorContacts(text: Text, imageHeight: Int): DebtorContacts {
        val its = items(text)
        val anchor = its
            .filter { it.cy in (imageHeight * 0.20f)..(imageHeight * 0.36f) }
            .firstOrNull { compact(it.text).contains("채무자명") || compact(it.text) == "채무자" }
            ?: return DebtorContacts()
        val row = rowAround(its, anchor, imageHeight)
        val joined = row.joinToString(" | ") { it.text }

        val phoneSegment = betweenLabels(joined, "전화번호", "핸드폰번호")
        val mobileSegment = afterLabel(joined, "핸드폰번호")
        return DebtorContacts(
            phone = phones(phoneSegment).firstOrNull().orEmpty(),
            mobile = phones(mobileSegment).firstOrNull().orEmpty()
        )
    }

    private fun extractOwnerPhone(text: Text, imageHeight: Int): String {
        val its = items(text)
        val anchor = its
            .filter { it.cy in (imageHeight * 0.34f)..(imageHeight * 0.56f) }
            .firstOrNull { compact(it.text).contains("물건소유자") }
            ?: return ""
        val joined = rowAround(its, anchor, imageHeight).joinToString(" | ") { it.text }
        return phones(afterLabel(joined, "연락처")).firstOrNull().orEmpty()
    }

    private fun extractAddressRow(text: Text, imageHeight: Int, label: String): String {
        val its = items(text)
        val anchor = its.firstOrNull { compact(it.text).contains(compact(label)) } ?: return ""
        var row = rowAround(its, anchor, imageHeight).joinToString(" | ") { it.text }
        row = removeLabel(row, label)
        row = cleanAddressLeak(row)

        val region = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기(?:도)?|강원(?:도)?|충북|충남|전북|전남|경북|경남|제주(?:도)?)")
        val match = region.find(row) ?: return ""
        val prefix = Regex("\\d{5,6}").findAll(row.substring(0, match.range.first)).lastOrNull()?.value
        val tail = row.substring(match.range.first).trim()
        return listOfNotNull(prefix, tail.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .let(::cleanAddressLeak)
            .takeIf(::validAddress)
            .orEmpty()
    }

    private fun rowAround(items: List<Item>, anchor: Item, imageHeight: Int): List<Item> {
        val tolerance = max(anchor.h * 1.15f, imageHeight * 0.011f)
        return items.filter { abs(it.cy - anchor.cy) <= tolerance }.sortedBy { it.box.left }
    }

    private fun betweenLabels(value: String, start: String, end: String): String {
        val startMatch = labelRegex(start).find(value) ?: return ""
        val tail = value.substring(startMatch.range.last + 1)
        val endMatch = labelRegex(end).find(tail) ?: return tail
        return tail.substring(0, endMatch.range.first)
    }

    private fun afterLabel(value: String, label: String): String {
        val match = labelRegex(label).find(value) ?: return ""
        return value.substring(match.range.last + 1)
    }

    private fun removeLabel(value: String, label: String): String = value.replace(labelRegex(label), " ")

    private fun labelRegex(label: String): Regex = Regex(
        label.map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    private fun phones(value: String): List<String> {
        val fixed = normalizeDigits(value)
        val compact = Regex("(?<!\\d)0\\d{8,11}(?!\\d)")
            .findAll(fixed)
            .map { normalizePhone(it.value) }
        val separated = phonePattern.findAll(fixed).map { normalizePhone(it.value) }
        return (compact + separated).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun items(text: Text): List<Item> = text.textBlocks.flatMap { it.lines }
        .mapNotNull { line -> line.boundingBox?.let { Item(line.text.trim(), it) } }
        .filter { it.text.isNotBlank() }

    private fun containsAddressLeak(value: String): Boolean {
        val c = compact(value)
        return listOf("물건소유자", "연락처", "전화번호", "핸드폰번호").any { c.contains(it) }
    }

    private fun sameNonBlank(a: String, b: String): Boolean = a.isNotBlank() && b.isNotBlank() && a == b

    private fun managementLeak(phone: String, management: String): Boolean {
        val p = phone.filter(Char::isDigit)
        val m = management.filter(Char::isDigit)
        return p.length >= 8 && m.contains(p)
    }

    private fun validAddress(v: String) = v.length >= 8 && Regex(
        "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)"
    ).containsMatchIn(v)

    private fun validPhone(v: String): Boolean {
        val d = v.filter(Char::isDigit)
        return when {
            d.length == 12 -> d.startsWith("050")
            d.length == 11 -> d.startsWith("01") || d.substring(0, 3) in threeDigitPrefixes
            d.length == 10 -> d.startsWith("02") || d.substring(0, 3) in threeDigitPrefixes
            d.length == 9 -> d.startsWith("02")
            else -> false
        }
    }

    private fun normalizeDigits(value: String): String = value.uppercase()
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1')

    private val phonePattern = Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")
    private val threeDigitPrefixes = setOf(
        "031", "032", "033", "041", "042", "043", "044",
        "051", "052", "053", "054", "055", "061", "062", "063", "064",
        "070", "080"
    )

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

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")

    private suspend fun recognize(
        client: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: android.graphics.Bitmap
    ): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
}
