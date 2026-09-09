package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Rect
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
 * v0.35.25
 * 이름 앵커 연락처 교정에서 소유자/기타요청사항의 같은 이름을 임차인으로 오인하지 않도록
 * 임차인 표의 세로 영역을 제한한다. 채무자와 소유자가 동일인이면 소유자 셀에 반복된 동일 번호는 보존한다.
 */
object AnchorContactRepairV3518 {
    private data class Hit(val text: String, val box: Rect)

    suspend fun repair(
        normalized: DocumentNormalizer.Result,
        base: OcrService.OcrResult
    ): OcrService.OcrResult {
        if (normalized.bitmap.width < 900 || normalized.bitmap.height < 1200) return base

        val c = base.parsed
        val debtor = bareName(c.debtorName)
        val owner = bareName(c.ownerName)
        if (debtor.isBlank() && owner.isBlank()) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val full = recognize(client, normalized.bitmap)
            val hits = collectHits(full)
            val excluded = setOf(
                "010-5312-6436",
                c.investigatorPhone,
                c.investigatorFax,
                c.branchPhone,
                c.branchFax
            ).map(ContactRoleResolverV3517::normalizePhone).filter { it.isNotBlank() }.toSet()

            val debtorHits = nameHits(hits, debtor)
            val ownerHits = nameHits(hits, owner)

            val debtorHit = debtorHits.minByOrNull { it.box.centerY() }
            val tenantHit = debtorHits
                .filter { hit ->
                    (debtorHit == null || hit.box.centerY() > debtorHit.box.centerY() + normalized.bitmap.height * 0.10) &&
                        isTenantBandY(hit.box.centerY(), normalized.bitmap.height)
                }
                .maxByOrNull { it.box.centerY() }
            val ownerHit = ownerHits
                .filter { debtorHit == null || it.box.centerY() > debtorHit.box.centerY() + normalized.bitmap.height * 0.06 }
                .minByOrNull { it.box.centerY() }
                ?: ownerHits.minByOrNull { it.box.centerY() }

            val debtorStrip = debtorHit?.let { readStrip(client, normalized.bitmap, it.box, 0.9f, 7.5f) }.orEmpty()
            val ownerStrip = ownerHit?.let { readStrip(client, normalized.bitmap, it.box, 0.9f, 2.8f) }.orEmpty()
            val tenantStrip = tenantHit?.let { readStrip(client, normalized.bitmap, it.box, 0.9f, 2.5f) }.orEmpty()

            val debtorPhones = phones(debtorStrip).filter { it !in excluded }
            val ownerPhones = phones(ownerStrip).filter { it !in excluded }
            val tenantPhones = phones(tenantStrip).filter { it !in excluded }

            val currentMobile = ContactRoleResolverV3517.normalizePhone(c.mobile)
                .takeIf { it.startsWith("01") && it !in excluded }
                .orEmpty()
            val tenantMobile = tenantPhones.firstOrNull { it.startsWith("01") && it !in excluded }.orEmpty()

            val detectedMobile = debtorPhones.firstOrNull { it.startsWith("01") && it !in excluded }
                ?: currentMobile.takeIf { it.isNotBlank() }
                ?: tenantMobile.takeIf { tenantHit != null && it.isNotBlank() }
                .orEmpty()

            val curPhone = ContactRoleResolverV3517.normalizePhone(c.phone)
            val fixedPhone = when {
                curPhone.isBlank() -> ""
                curPhone == detectedMobile -> ""
                curPhone in excluded -> ""
                else -> curPhone
            }

            val sameOwnerAsDebtor = owner.isNotBlank() && debtor.isNotBlank() && owner == debtor
            val ownerFromAnchor = ownerPhones.firstOrNull {
                sameOwnerAsDebtor || (it != detectedMobile && it != fixedPhone)
            }
            val curOwner = ContactRoleResolverV3517.normalizePhone(c.ownerPhone)
            val fixedOwner = when {
                ownerFromAnchor != null -> ownerFromAnchor
                curOwner.isNotBlank() && curOwner !in excluded &&
                    (sameOwnerAsDebtor || (curOwner != detectedMobile && curOwner != fixedPhone)) -> curOwner
                else -> ""
            }

            val fixedTenants = mergeTenant1(
                existingJson = c.tenantsJson,
                debtorName = debtor,
                detectedMobile = detectedMobile,
                tenantAnchorFound = tenantHit != null,
                tenantPhones = tenantPhones
            )

            val fixed = c.copy(
                phone = fixedPhone,
                mobile = detectedMobile,
                ownerPhone = fixedOwner,
                tenantsJson = fixedTenants
            )

            base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 비정렬 이름 앵커 연락처 교정 v0.35.25 ---\n")
                    append("기준점 검출 : ").append(normalized.documentDetected).append('\n')
                    append("채무자 앵커 횟수 : ").append(debtorHits.size).append('\n')
                    append("소유자 앵커 횟수 : ").append(ownerHits.size).append('\n')
                    append("임차인 표 앵커 : ").append(tenantHit != null).append('\n')
                    append("채무자 영역 번호 : ").append(debtorPhones.joinToString()).append('\n')
                    append("소유자 영역 번호 : ").append(ownerPhones.joinToString()).append('\n')
                    append("임차인 영역 번호 : ").append(tenantPhones.joinToString()).append('\n')
                    append("최종 채무자 전화/휴대폰 : ").append(fixed.phone).append(" / ").append(fixed.mobile).append('\n')
                    append("최종 소유자 연락처 : ").append(fixed.ownerPhone).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 이름 앵커 연락처 검증 v0.35.25"
            )
        } finally {
            client.close()
        }
    }

    internal fun isTenantBandY(centerY: Int, imageHeight: Int): Boolean {
        if (imageHeight <= 0) return false
        val ratio = centerY.toFloat() / imageHeight.toFloat()
        return ratio in 0.45f..0.70f
    }

    private fun collectHits(text: Text): List<Hit> = buildList {
        for (block in text.textBlocks) {
            block.boundingBox?.let { add(Hit(block.text, it)) }
            for (line in block.lines) {
                line.boundingBox?.let { add(Hit(line.text, it)) }
                for (element in line.elements) element.boundingBox?.let { add(Hit(element.text, it)) }
            }
        }
    }

    private fun nameHits(hits: List<Hit>, name: String): List<Hit> {
        if (name.isBlank()) return emptyList()
        val compactName = compact(name)
        return hits.filter { hit -> compact(hit.text).contains(compactName) }
            .distinctBy { "${it.box.left}:${it.box.top}:${it.box.right}:${it.box.bottom}" }
    }

    private suspend fun readStrip(
        client: TextRecognizer,
        source: Bitmap,
        anchor: Rect,
        aboveFactor: Float,
        belowFactor: Float
    ): String {
        val h = anchor.height().coerceAtLeast((source.height * 0.018f).toInt())
        val top = (anchor.centerY() - h * aboveFactor).toInt().coerceAtLeast(0)
        val bottom = (anchor.centerY() + h * belowFactor).toInt().coerceAtMost(source.height)
        val left = (source.width * 0.08f).toInt()
        val right = (source.width * 0.995f).toInt()
        if (bottom <= top + 4 || right <= left + 4) return ""

        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            buildString {
                recognize(client, crop).text.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                val enhanced = OcrImageEnhancer.enhance(crop)
                try {
                    recognize(client, enhanced).text.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                } finally {
                    if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
                }
                val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                try {
                    recognize(client, scaled).text.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                } finally {
                    if (!scaled.isRecycled) scaled.recycle()
                }
            }
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    internal fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O','0').replace('I','1').replace('L','1')
        val direct = sequenceOf(
            Regex("(?<!\\d)0\\d{8,11}(?!\\d)"),
            Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")
        ).flatMap { regex -> regex.findAll(fixed).map { ContactRoleResolverV3517.normalizePhone(it.value) } }

        val tokens = Regex("\\d{2,4}").findAll(fixed).map { it.value }.toList()
        val rebuilt = sequence {
            for (i in tokens.indices) {
                for (count in 2..4) {
                    if (i + count <= tokens.size) yield(tokens.subList(i, i + count).joinToString(""))
                }
            }
        }.map(ContactRoleResolverV3517::normalizePhone)
        return (direct + rebuilt).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun mergeTenant1(
        existingJson: String,
        debtorName: String,
        detectedMobile: String,
        tenantAnchorFound: Boolean,
        tenantPhones: List<String>
    ): String {
        val source = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
        val old = source.optJSONObject(0)
        val oldName = old?.optString("name").orEmpty().ifBlank { old?.optString("tenantName").orEmpty() }
        val oldPhone = ContactRoleResolverV3517.normalizePhone(
            old?.optString("phone").orEmpty().ifBlank { old?.optString("mobile").orEmpty() }
        )

        val name = oldName.takeIf(TenantResultSanitizer::validTenantName).orEmpty()
            .ifBlank { debtorName.takeIf { tenantAnchorFound && TenantResultSanitizer.validTenantName(it) }.orEmpty() }
        val phone = if (name.isBlank()) {
            ""
        } else {
            oldPhone.takeIf(TenantResultSanitizer::validTenantPhone).orEmpty()
                .ifBlank { tenantPhones.firstOrNull().orEmpty() }
                .ifBlank { detectedMobile.takeIf { tenantAnchorFound }.orEmpty() }
        }

        val out = JSONArray()
        if (name.isNotBlank()) {
            out.put(JSONObject().apply { put("name", name); put("phone", phone) })
        }
        for (i in 1 until source.length().coerceAtMost(10)) source.optJSONObject(i)?.let(out::put)
        return out.toString()
    }

    private fun bareName(value: String): String = value.substringBefore('(').replace(" ", "").trim()
    private fun compact(value: String): String = value.replace(Regex("[^0-9A-Za-z가-힣]"), "")

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }
}
