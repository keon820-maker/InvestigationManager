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
 * v0.35.9: v0.35.8에서 잘못된 교차 필드 값은 제거됐지만, 실제 대상자/소유자 연락처까지
 * 공란이 되는 경우를 실제 라벨과 같은 행의 boundingBox 관계로 재복구한다.
 * 개인정보 원문은 하드코딩하지 않는다.
 */
object ContactRecoveryRepairV359 {
    private data class Item(val text: String, val box: Rect) {
        val cx: Float get() = box.exactCenterX()
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
            val exclusions = buildSet {
                addAll(extractHeaderPhones(full, imageHeight))
                base.parsed.branchPhone.takeIf { it.isNotBlank() }?.let(::add)
                base.parsed.branchFax.takeIf { it.isNotBlank() }?.let(::add)
            }

            val debtor = extractDebtorContacts(
                text = full,
                imageHeight = imageHeight,
                debtorName = base.parsed.debtorName,
                managementNo = base.parsed.managementNo,
                exclusions = exclusions
            )
            val ownerPhone = extractOwnerPhone(
                text = full,
                imageHeight = imageHeight,
                ownerName = base.parsed.ownerName,
                managementNo = base.parsed.managementNo,
                exclusions = exclusions
            )

            val fixed = mergeRecoveredContacts(
                current = base.parsed,
                debtorPhone = debtor.phone,
                debtorMobile = debtor.mobile,
                ownerPhone = ownerPhone,
                exclusions = exclusions
            )

            if (fixed == base.parsed) base else base.copy(
                parsed = fixed,
                rawText = buildString {
                    append(base.rawText)
                    append("\n\n--- 연락처 라벨 재복구 v0.35.9 ---\n")
                    append("채무자 전화/핸드폰 : ").append(fixed.phone).append(" / ").append(fixed.mobile).append('\n')
                    append("소유자 연락처 : ").append(fixed.ownerPhone).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 연락처 라벨 재복구 v0.35.9"
            )
        } finally {
            client.close()
        }
    }

    internal fun needsRepair(base: OcrService.OcrResult): Boolean {
        val ranLeakRepair = base.preprocessMessage.contains("필드 누수 최종 정리 v0.35.8")
        if (!ranLeakRepair) return false
        val c = base.parsed
        return c.phone.isBlank() || c.mobile.isBlank() || c.ownerPhone.isBlank()
    }

    internal fun mergeRecoveredContacts(
        current: InvestigationCase,
        debtorPhone: String,
        debtorMobile: String,
        ownerPhone: String,
        exclusions: Set<String>
    ): InvestigationCase {
        fun safe(value: String): String {
            if (!validPhone(value)) return ""
            if (value in exclusions) return ""
            if (managementLeak(value, current.managementNo)) return ""
            return value
        }

        val currentPhone = safe(current.phone)
        val currentMobile = safe(current.mobile)
        val currentOwner = safe(current.ownerPhone)
        val recoveredPhone = safe(debtorPhone)
        val recoveredMobile = safe(debtorMobile).takeIf { it.startsWith("01") }.orEmpty()
        val recoveredOwner = safe(ownerPhone)

        return current.copy(
            phone = currentPhone.ifBlank { recoveredPhone },
            mobile = currentMobile.ifBlank { recoveredMobile },
            ownerPhone = currentOwner.ifBlank { recoveredOwner }
        )
    }

    private fun extractDebtorContacts(
        text: Text,
        imageHeight: Int,
        debtorName: String,
        managementNo: String,
        exclusions: Set<String>
    ): DebtorContacts {
        val its = items(text)
        val debtorKey = debtorName.substringBefore('(').replace(" ", "").trim()
        val debtorAnchor = its
            .filter { it.cy in (imageHeight * 0.18f)..(imageHeight * 0.42f) }
            .firstOrNull { item ->
                val c = compact(item.text)
                c.contains("채무자명") || c == "채무자" ||
                    (debtorKey.length >= 2 && c.contains(debtorKey))
            }
            ?: return DebtorContacts()

        val row = rowAround(its, debtorAnchor, imageHeight, multiplier = 2.15f, minRatio = 0.017f)
        val phoneLabel = row.firstOrNull { compact(it.text).contains("전화번호") }
        val mobileLabel = row.firstOrNull {
            val c = compact(it.text)
            c.contains("핸드폰번호") || c.contains("휴대폰번호")
        }

        fun usable(value: String): Boolean = validPhone(value) &&
            value !in exclusions &&
            !managementLeak(value, managementNo)

        val rowPhones = row.flatMap { item ->
            phones(item.text).map { phone -> item to phone }
        }.filter { usable(it.second) }

        var phone = ""
        if (phoneLabel != null) {
            phone = phones(afterAnyLabel(phoneLabel.text, listOf("전화번호")))
                .firstOrNull(::usable)
                .orEmpty()
            if (phone.isBlank()) {
                val rightLimit = mobileLabel?.box?.left ?: Int.MAX_VALUE
                phone = rowPhones
                    .filter { (item, _) -> item.box.left >= phoneLabel.box.right - 20 && item.box.left < rightLimit }
                    .minByOrNull { (item, _) -> item.box.left - phoneLabel.box.right }
                    ?.second.orEmpty()
            }
        }

        var mobile = ""
        if (mobileLabel != null) {
            mobile = phones(afterAnyLabel(mobileLabel.text, listOf("핸드폰번호", "휴대폰번호")))
                .firstOrNull { usable(it) && it.startsWith("01") }
                .orEmpty()
            if (mobile.isBlank()) {
                mobile = rowPhones
                    .filter { (item, value) ->
                        value.startsWith("01") && item.cx >= mobileLabel.cx - 10
                    }
                    .minByOrNull { (item, _) -> abs(item.box.left - mobileLabel.box.right).toFloat() }
                    ?.second.orEmpty()
            }
        }

        // 라벨이 약하게 인식되더라도 대상자 행에 남은 01x 번호가 하나뿐이면 핸드폰으로 사용한다.
        if (mobile.isBlank()) {
            val candidates = rowPhones.map { it.second }.filter { it.startsWith("01") }.distinct()
            if (candidates.size == 1) mobile = candidates.single()
        }

        // 같은 번호를 전화/핸드폰 두 칸에 중복으로 넣지 않는다.
        if (phone.isNotBlank() && phone == mobile) phone = ""
        return DebtorContacts(phone = phone, mobile = mobile)
    }

    private fun extractOwnerPhone(
        text: Text,
        imageHeight: Int,
        ownerName: String,
        managementNo: String,
        exclusions: Set<String>
    ): String {
        val its = items(text)
        val ownerKey = ownerName.replace(" ", "").trim()
        val ownerAnchor = its
            .filter { it.cy in (imageHeight * 0.32f)..(imageHeight * 0.62f) }
            .firstOrNull { item ->
                val c = compact(item.text)
                c.contains("물건소유자") ||
                    (ownerKey.length >= 2 && c.contains(ownerKey))
            }
            ?: return ""

        val row = rowAround(its, ownerAnchor, imageHeight, multiplier = 2.15f, minRatio = 0.017f)
        val contactLabel = row.firstOrNull { compact(it.text).contains("연락처") }

        fun usable(value: String): Boolean = validPhone(value) &&
            value !in exclusions &&
            !managementLeak(value, managementNo)

        if (contactLabel != null) {
            val sameLine = phones(afterAnyLabel(contactLabel.text, listOf("연락처"))).firstOrNull(::usable)
            if (!sameLine.isNullOrBlank()) return sameLine

            val right = row.flatMap { item -> phones(item.text).map { item to it } }
                .filter { (item, value) -> item.cx >= contactLabel.cx - 10 && usable(value) }
                .minByOrNull { (item, _) -> abs(item.box.left - contactLabel.box.right).toFloat() }
                ?.second
            if (!right.isNullOrBlank()) return right
        }

        val candidates = row.flatMap { phones(it.text) }.filter(::usable).distinct()
        return candidates.singleOrNull().orEmpty()
    }

    private fun extractHeaderPhones(text: Text, imageHeight: Int): Set<String> = items(text)
        .filter { it.cy < imageHeight * 0.22f }
        .flatMap { phones(it.text) }
        .toSet()

    private fun rowAround(
        items: List<Item>,
        anchor: Item,
        imageHeight: Int,
        multiplier: Float,
        minRatio: Float
    ): List<Item> {
        val tolerance = max(anchor.h * multiplier, imageHeight * minRatio)
        return items.filter { abs(it.cy - anchor.cy) <= tolerance }.sortedBy { it.box.left }
    }

    private fun afterAnyLabel(value: String, labels: List<String>): String {
        labels.forEach { label ->
            val match = labelRegex(label).find(value)
            if (match != null) return value.substring(match.range.last + 1)
        }
        return ""
    }

    private fun labelRegex(label: String): Regex = Regex(
        label.map { Regex.escape(it.toString()) }.joinToString("\\s*"),
        RegexOption.IGNORE_CASE
    )

    private fun phones(value: String): List<String> {
        val fixed = normalizeDigits(value)
        val compactPhones = Regex("(?<!\\d)0\\d{8,11}(?!\\d)")
            .findAll(fixed)
            .map { normalizePhone(it.value) }
        val separated = phonePattern.findAll(fixed).map { normalizePhone(it.value) }
        return (compactPhones + separated).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun items(text: Text): List<Item> = text.textBlocks.flatMap { it.lines }
        .mapNotNull { line -> line.boundingBox?.let { Item(line.text.trim(), it) } }
        .filter { it.text.isNotBlank() }

    private fun managementLeak(phone: String, management: String): Boolean {
        val p = phone.filter(Char::isDigit)
        val m = management.filter(Char::isDigit)
        return p.length >= 8 && m.contains(p)
    }

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
