package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.35.25: OCR이 번호를 읽었지만 역할을 잘못 배치하는 실기기 사례를 마지막 단계에서 교정한다.
 * 중앙표 정렬 이후 대상자 행/핸드폰 셀/소유자 셀/임차인1 셀을 서로 독립적으로 재OCR한다.
 */
object ContactRoleMappingRepairV3517 {
    private const val W = 2480f
    private const val H = 3508f
    private data class Cell(val l: Int, val t: Int, val r: Int, val b: Int)

    private val debtorRowCells = listOf(
        Cell(900, 735, 2425, 980),
        Cell(760, 700, 2440, 1020)
    )
    private val mobileCells = listOf(
        Cell(1650, 755, 2430, 955),
        Cell(1500, 720, 2440, 995)
    )
    private val ownerCells = listOf(
        Cell(1540, 1390, 2430, 1605),
        Cell(1420, 1360, 2440, 1630)
    )
    private val tenant1Cells = listOf(
        Cell(140, 1620, 1360, 1835),
        Cell(120, 1590, 1400, 1860)
    )

    suspend fun repair(normalized: DocumentNormalizer.Result, base: OcrService.OcrResult): OcrService.OcrResult {
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val c = base.parsed
            val excluded = setOf(
                "010-5312-6436",
                c.investigatorPhone,
                c.investigatorFax,
                c.branchPhone,
                c.branchFax
            ).map(ContactRoleResolverV3517::normalizePhone).filter { it.isNotBlank() }.toSet()

            val debtorRaw = readAll(client, normalized.bitmap, debtorRowCells)
            val mobileRaw = readAll(client, normalized.bitmap, mobileCells)
            val ownerRaw = readAll(client, normalized.bitmap, ownerCells)
            val tenantRaw = readAll(client, normalized.bitmap, tenant1Cells)

            val debtorPhones = phones(debtorRaw)
            val mobilePhones = phones(mobileRaw)
            val ownerPhones = phones(ownerRaw)
            val tenantPhones = phones(tenantRaw)
            val tenantName = firstTenantName(tenantRaw)

            val resolved = ContactRoleResolverV3517.resolve(
                currentPhone = c.phone,
                currentMobile = c.mobile,
                currentOwnerPhone = c.ownerPhone,
                tenantsJson = c.tenantsJson,
                debtorName = c.debtorName,
                ownerName = c.ownerName,
                debtorRowPhones = debtorPhones,
                mobileCellPhones = mobilePhones,
                ownerCellPhones = ownerPhones,
                tenant1Name = tenantName,
                tenant1Phones = tenantPhones,
                excluded = excluded
            )

            val fixed = c.copy(
                phone = resolved.debtorPhone,
                mobile = resolved.debtorMobile,
                ownerPhone = resolved.ownerPhone,
                tenantsJson = resolved.tenantsJson
            )
            if (fixed == c) base else base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 연락처 역할 재매핑 v0.35.25 ---\n")
                    append("대상자행 후보 : ").append(debtorPhones.joinToString()).append('\n')
                    append("핸드폰셀 후보 : ").append(mobilePhones.joinToString()).append('\n')
                    append("소유자셀 후보 : ").append(ownerPhones.joinToString()).append('\n')
                    append("임차인1 후보 : ").append(tenantName).append(" / ").append(tenantPhones.joinToString()).append('\n')
                    append("최종 전화/핸드폰 : ").append(fixed.phone).append(" / ").append(fixed.mobile).append('\n')
                    append("최종 소유자 연락처 : ").append(fixed.ownerPhone).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 연락처 역할 재매핑 v0.35.25"
            )
        } finally {
            client.close()
        }
    }

    private suspend fun readAll(client: TextRecognizer, source: Bitmap, cells: List<Cell>): String = buildString {
        for (cell in cells) {
            val crop = crop(source, cell)
            try {
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
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
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

    internal fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O','0').replace('I','1').replace('L','1')
        val direct = sequenceOf(
            Regex("(?<!\\d)0\\d{8,11}(?!\\d)"),
            Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")
        ).flatMap { r -> r.findAll(fixed).map { ContactRoleResolverV3517.normalizePhone(it.value) } }

        val tokens = Regex("\\d{2,4}").findAll(fixed).map { it.value }.toList()
        val rebuilt = sequence {
            for (i in tokens.indices) {
                for (count in 2..3) {
                    if (i + count <= tokens.size) yield(tokens.subList(i, i + count).joinToString(""))
                }
            }
        }.map(ContactRoleResolverV3517::normalizePhone)

        return (direct + rebuilt).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun firstTenantName(raw: String): String {
        val bad = setOf("임차인","임차인명","성명","전화","전화번호","번호","연락처","지인","임치인","일치인","의치인","리초인")
        return Regex("[가-힣]{2,6}").findAll(raw).map { it.value.replace(" ", "") }
            .firstOrNull { it !in bad && TenantResultSanitizer.validTenantName(it) }.orEmpty()
    }

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
}
