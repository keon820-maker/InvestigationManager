package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.35.16: 중앙표가 A4 2480x3508 기준으로 정렬된 뒤, 연락처를 '라벨 주변 줄'이 아니라
 * 실제 값 셀 ROI 자체에서 여러 번 재OCR한다. 촬영 각도는 TemplateAnchorNormalizer가 먼저
 * 원근 보정하므로 여기서는 정렬된 고정 좌표만 사용한다.
 */
object TemplateCellContactRepairV3516 {
    private const val W = 2480f
    private const val H = 3508f

    internal data class Cell(val l: Int, val t: Int, val r: Int, val b: Int)
    private data class Tenant(val name: String = "", val phone: String = "")

    // 같은 셀을 tight/wide 두 범위로 읽는다. 표선이나 글자 잘림에 대한 여유를 준다.
    private val debtorPhoneCells = listOf(
        Cell(1120, 790, 1585, 935),
        Cell(1020, 770, 1660, 955)
    )
    private val debtorMobileCells = listOf(
        Cell(1840, 790, 2390, 935),
        Cell(1690, 770, 2430, 955)
    )
    private val ownerPhoneCells = listOf(
        Cell(1680, 1415, 2390, 1575),
        Cell(1560, 1395, 2430, 1595)
    )
    private val tenant1Cells = listOf(
        Cell(170, 1640, 1325, 1810),
        Cell(140, 1625, 1360, 1830)
    )

    suspend fun repair(
        normalized: DocumentNormalizer.Result,
        base: OcrService.OcrResult
    ): OcrService.OcrResult {
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) return base

        val c = base.parsed
        val needs = c.mobile.isBlank() || c.ownerPhone.isBlank() || firstTenant(c.tenantsJson).let { it.name.isBlank() || it.phone.isBlank() }
        if (!needs) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val excluded = setOf(
                c.investigatorPhone,
                c.investigatorFax,
                c.branchPhone,
                c.branchFax
            ).map(::normalizePhone).filter { it.isNotBlank() }.toSet()

            val debtorPhone = if (c.phone.isBlank()) readFirstPhone(client, normalized.bitmap, debtorPhoneCells, excluded) else ""
            val debtorMobile = if (c.mobile.isBlank()) {
                readFirstPhone(client, normalized.bitmap, debtorMobileCells, excluded) { it.startsWith("010-") || it.startsWith("01") }
            } else ""
            val ownerPhone = if (c.ownerPhone.isBlank()) readFirstPhone(client, normalized.bitmap, ownerPhoneCells, excluded) else ""

            val tenantRaw = readCombined(client, normalized.bitmap, tenant1Cells)
            val tenantFresh = parseTenant(tenantRaw, excluded)
            val tenantMerged = mergeTenant1(c.tenantsJson, tenantFresh, c.debtorName, c.mobile.ifBlank { debtorMobile })

            val fixed = c.copy(
                phone = c.phone.ifBlank { debtorPhone },
                mobile = c.mobile.ifBlank { debtorMobile },
                ownerPhone = c.ownerPhone.ifBlank { ownerPhone },
                tenantsJson = tenantMerged
            )

            if (fixed == c) base else base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 고정 셀 연락처 재OCR v0.35.16 ---\n")
                    append("채무자 전화 : ").append(fixed.phone).append('\n')
                    append("채무자 핸드폰 : ").append(fixed.mobile).append('\n')
                    append("소유자 연락처 : ").append(fixed.ownerPhone).append('\n')
                    val t1 = firstTenant(fixed.tenantsJson)
                    append("임차인1 : ").append(t1.name).append(" / ").append(t1.phone).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 고정 셀 연락처 재OCR v0.35.16"
            )
        } finally {
            client.close()
        }
    }

    private suspend fun readFirstPhone(
        client: TextRecognizer,
        source: Bitmap,
        cells: List<Cell>,
        excluded: Set<String>,
        extra: (String) -> Boolean = { true }
    ): String {
        for (cell in cells) {
            val variants = readVariants(client, source, cell)
            for (text in variants) {
                val phones = phones(text)
                val hit = phones.firstOrNull { it !in excluded && extra(it) }
                if (hit != null) return hit
            }
        }
        return ""
    }

    private suspend fun readCombined(client: TextRecognizer, source: Bitmap, cells: List<Cell>): String = buildString {
        cells.forEach { cell ->
            readVariants(client, source, cell).forEach { text ->
                if (text.isNotBlank()) append(text).append('\n')
            }
        }
    }

    /** 원본 crop + 보정 crop + 2배 확대 crop을 모두 읽는다. */
    private suspend fun readVariants(client: TextRecognizer, source: Bitmap, cell: Cell): List<String> {
        val crop = crop(source, cell)
        return try {
            val out = mutableListOf<String>()
            recognize(client, crop).text.takeIf { it.isNotBlank() }?.let(out::add)

            val enhanced = OcrImageEnhancer.enhance(crop)
            try {
                recognize(client, enhanced).text.takeIf { it.isNotBlank() }?.let(out::add)
            } finally {
                if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
            }

            val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
            try {
                recognize(client, scaled).text.takeIf { it.isNotBlank() }?.let(out::add)
            } finally {
                if (!scaled.isRecycled) scaled.recycle()
            }
            out.distinct()
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun crop(source: Bitmap, cell: Cell): Bitmap {
        val sx = source.width / W
        val sy = source.height / H
        val l = (cell.l * sx).toInt().coerceIn(0, source.width - 2)
        val t = (cell.t * sy).toInt().coerceIn(0, source.height - 2)
        val r = (cell.r * sx).toInt().coerceIn(l + 1, source.width)
        val b = (cell.b * sy).toInt().coerceIn(t + 1, source.height)
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    private fun parseTenant(raw: String, excluded: Set<String>): Tenant {
        val phone = phones(raw).firstOrNull { it !in excluded }.orEmpty()
        val bad = setOf(
            "임차인", "임차인명", "성명", "전화", "전화번호", "전화번", "번호", "연락처",
            "핸드폰", "핸드폰번호", "임치인", "일치인", "의치인", "리초인", "지인"
        )
        val name = Regex("[가-힣]{2,6}").findAll(raw)
            .map { it.value.replace(" ", "") }
            .firstOrNull { candidate ->
                candidate !in bad && TenantResultSanitizer.validTenantName(candidate)
            }.orEmpty()
        return Tenant(name, phone)
    }

    private fun mergeTenant1(existingJson: String, fresh: Tenant, debtorName: String, debtorMobile: String): String {
        val source = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
        val old = source.optJSONObject(0)
        var oldName = old?.optString("name").orEmpty().ifBlank { old?.optString("tenantName").orEmpty() }
        var oldPhone = old?.optString("phone").orEmpty().ifBlank { old?.optString("mobile").orEmpty() }

        if (!TenantResultSanitizer.validTenantName(oldName)) oldName = ""
        if (!TenantResultSanitizer.validTenantPhone(oldPhone)) oldPhone = ""

        val normalizedDebtorMobile = normalizePhone(debtorMobile)
        var name = oldName.ifBlank { fresh.name }
        val phone = oldPhone.ifBlank { fresh.phone }

        // 같은 사람이 채무자이면서 임차인인 양식은 같은 휴대폰번호 중복이 정상이다.
        if (name.isBlank() && phone.isNotBlank() && phone == normalizedDebtorMobile) {
            val debtor = debtorName.substringBefore('(').replace(" ", "").trim()
            if (TenantResultSanitizer.validTenantName(debtor)) name = debtor
        }

        if (name.isBlank() && phone.isBlank()) return existingJson.ifBlank { "[]" }

        val out = JSONArray()
        out.put(JSONObject().apply { put("name", name); put("phone", phone) })
        for (i in 1 until source.length().coerceAtMost(10)) {
            source.optJSONObject(i)?.let(out::put)
        }
        return out.toString()
    }

    internal fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val compact = Regex("(?<!\\d)0\\d{8,11}(?!\\d)").findAll(fixed).map { normalizePhone(it.value) }
        val separated = Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}").findAll(fixed).map { normalizePhone(it.value) }
        return (compact + separated).filter { it.isNotBlank() }.distinct().toList()
    }

    internal fun normalizePhone(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 && d.substring(0, 3) in prefixes -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 11 && d.substring(0, 3) in prefixes -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun firstTenant(json: String): Tenant = runCatching {
        val a = JSONArray(json)
        val o = a.optJSONObject(0) ?: return@runCatching Tenant()
        Tenant(
            o.optString("name").ifBlank { o.optString("tenantName") },
            o.optString("phone").ifBlank { o.optString("mobile") }
        )
    }.getOrDefault(Tenant())

    private val prefixes = setOf(
        "031", "032", "033", "041", "042", "043", "044",
        "051", "052", "053", "054", "055", "061", "062", "063", "064", "070", "080"
    )

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }
}
