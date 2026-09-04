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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.24: 대상자 연락처/물건소재지 코드/임차인 표를 실제 위치에서 재OCR한다.
 * 기존 구조화 결과가 이미 정상인 필드는 최대한 유지하고, 비어 있거나 더 완전한 값만 보강한다.
 */
object TargetTenantOcrRepair {
    private const val W = 2480f
    private const val H = 3508f

    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int)
    private data class Tenant(val name: String = "", val phone: String = "")

    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull() ?: return base
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) {
            if (!normalized.bitmap.isRecycled) normalized.bitmap.recycle()
            return base
        }

        val source = normalized.bitmap
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            // 대상자 첫 행: 라벨과 값의 경계가 약간 흔들려도 잡히도록 기존 셀보다 넓게 읽는다.
            val phoneRaw = readBox(client, source, Box(930, 780, 1640, 950))
            val mobileRaw = readBox(client, source, Box(1660, 780, 2420, 950))
            val phoneCandidate = firstPhone(phoneRaw)
            val mobileCandidate = firstPhone(mobileRaw)

            // 물건 소재지: 앞의 5~6자리 우편번호/관리코드를 포함한 채 보존한다.
            val propertyAddressRaw = readBox(client, source, Box(420, 1310, 2400, 1495))
            val propertyAddressCandidate = sanitizeAddressPreservePrefix(propertyAddressRaw)

            // 임차인 표는 한 행에 좌/우 두 명씩 5행이다. 각 반쪽을 따로 읽어 잘못된 열 혼입을 줄인다.
            val tenantRows = listOf(
                1660 to 1785,
                1775 to 1895,
                1885 to 2010,
                2000 to 2125,
                2115 to 2245
            )
            val detectedTenants = mutableListOf<Tenant>()
            tenantRows.forEach { (top, bottom) ->
                detectedTenants += parseTenantHalf(readBox(client, source, Box(170, top, 1325, bottom)))
                detectedTenants += parseTenantHalf(readBox(client, source, Box(1320, top, 2400, bottom)))
            }

            val c = base.parsed
            val mergedTenants = mergeTenants(c.tenantsJson, detectedTenants)
            val fixedPhone = phoneCandidate.ifBlank { c.phone }
            val fixedMobile = mobileCandidate.ifBlank { c.mobile }
            val fixedPropertyAddress = preferPrefixedAddress(c.propertyAddress, propertyAddressCandidate)
            val fixedNotes = repairNumericNoteFields(c.requestNotes)

            val fixed = c.copy(
                phone = fixedPhone,
                mobile = fixedMobile,
                propertyAddress = fixedPropertyAddress,
                tenantsJson = mergedTenants,
                requestNotes = fixedNotes
            )

            if (fixed == c) base else base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 대상자/임차인 위치 한정 재OCR v0.24 ---\n")
                    append("대상자 전화 영역 : ").append(phoneRaw.replace('\n', ' ')).append('\n')
                    append("대상자 전화 확정 : ").append(fixed.phone).append('\n')
                    append("핸드폰 영역 : ").append(mobileRaw.replace('\n', ' ')).append('\n')
                    append("핸드폰 확정 : ").append(fixed.mobile).append('\n')
                    append("물건소재지 영역 : ").append(propertyAddressRaw.replace('\n', ' ')).append('\n')
                    append("물건소재지 확정 : ").append(fixed.propertyAddress).append('\n')
                    detectedTenants.forEachIndexed { index, tenant ->
                        if (tenant.name.isNotBlank() || tenant.phone.isNotBlank()) {
                            append("임차인").append(index + 1).append(" 확정 : ")
                                .append(tenant.name).append(" / ").append(tenant.phone).append('\n')
                        }
                    }
                    append("기타요청사항 숫자 보정 : ").append(fixed.requestNotes.replace('\n', ' ')).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 대상자·임차인 위치 한정 재OCR v0.24"
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
            val enhanced = OcrImageEnhancer.enhance(crop)
            try {
                val enhancedText = recognize(client, enhanced).text
                if (enhancedText.isNotBlank()) enhancedText else recognize(client, crop).text
            } finally {
                if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
            }
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun firstPhone(value: String): String {
        val fixed = normalizeDigits(value)
        return phonePattern.find(fixed)?.value?.let(::normalizePhone).orEmpty()
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

    private fun sanitizeAddressPreservePrefix(value: String): String {
        var s = value
            .replace(Regex("물\\s*건\\s*소\\s*재\\s*지\\s*[:：]?", RegexOption.IGNORE_CASE), " ")
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val region = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기(?:도)?|강원(?:도)?|충북|충남|전북|전남|경북|경남|제주(?:도)?)")
        val regionMatch = region.find(s) ?: return ""
        val beforeRegion = s.substring(0, regionMatch.range.first)
        val prefix = Regex("\\d{5,6}").findAll(beforeRegion).lastOrNull()?.value
        s = s.substring(regionMatch.range.first).trim()

        // 주소 끝 뒤에 영문 OCR 잡음이 붙으면 마지막 호/동/층/번지 뒤에서 자른다.
        val endTokens = Regex("(?:\\d+호|\\d+동|\\d+층|\\d+번지|\\d+(?:-\\d+)?)").findAll(s).toList()
        val last = endTokens.lastOrNull()
        if (last != null && last.range.last + 1 < s.length) {
            val tail = s.substring(last.range.last + 1).trim()
            if (tail.count { it.isLetter() && it !in '가'..'힣' } >= 3) {
                s = s.substring(0, last.range.last + 1).trim()
            }
        }

        val result = listOfNotNull(prefix, s.takeIf { it.isNotBlank() }).joinToString(" ")
        return result.takeIf(::looksLikeAddress).orEmpty()
    }

    private fun preferPrefixedAddress(current: String, candidate: String): String {
        if (candidate.isBlank()) return current
        if (current.isBlank()) return candidate
        val currentHasPrefix = Regex("^\\d{5,6}\\s+").containsMatchIn(current)
        val candidateHasPrefix = Regex("^\\d{5,6}\\s+").containsMatchIn(candidate)
        return when {
            candidateHasPrefix && !currentHasPrefix -> candidate
            candidateHasPrefix && candidate.length >= current.length -> candidate
            else -> current
        }
    }

    private fun looksLikeAddress(value: String): Boolean = value.length >= 8 && Regex(
        "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)"
    ).containsMatchIn(value)

    private fun parseTenantHalf(raw: String): Tenant {
        val normalized = normalizeDigits(raw)
        val phone = phonePattern.find(normalized)?.value?.let(::normalizePhone).orEmpty()
        val bad = setOf(
            "임차인", "성명", "전화번호", "전화번", "번호", "연락처", "전환번호",
            "임차인성명", "전화"
        )
        val name = Regex("[가-힣]{2,6}").findAll(raw)
            .map { it.value }
            .firstOrNull { candidate ->
                candidate !in bad && !candidate.startsWith("임차인") &&
                    !candidate.contains("전화") && !candidate.contains("번호") && !candidate.contains("성명")
            }
            .orEmpty()
        return Tenant(name = name, phone = phone)
    }

    private fun mergeTenants(existingJson: String, detected: List<Tenant>): String {
        val existing = parseExistingTenants(existingJson)
        val merged = (0 until 10).map { index ->
            val old = existing.getOrNull(index) ?: Tenant()
            val fresh = detected.getOrNull(index) ?: Tenant()
            Tenant(
                name = old.name.ifBlank { fresh.name },
                phone = old.phone.ifBlank { fresh.phone }
            )
        }
        if (merged.none { it.name.isNotBlank() || it.phone.isNotBlank() }) return existingJson.ifBlank { "[]" }
        val array = JSONArray()
        merged.forEach { tenant ->
            array.put(JSONObject().apply {
                put("name", tenant.name)
                put("phone", tenant.phone)
            })
        }
        return array.toString()
    }

    private fun parseExistingTenants(json: String): List<Tenant> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).take(10).map { i ->
            val obj = array.optJSONObject(i)
            if (obj == null) Tenant() else Tenant(
                name = obj.optString("name").ifBlank { obj.optString("tenantName") },
                phone = obj.optString("phone").ifBlank { obj.optString("mobile") }
            )
        }
    }.getOrDefault(emptyList())

    private fun repairNumericNoteFields(value: String): String {
        var s = value
        val confusingZero = "[Oo오이Il|]"
        s = Regex("(보증금\\s*[:：]?\\s*)($confusingZero)(?=\\s|$|월)")
            .replace(s) { it.groupValues[1] + "0" }
        s = Regex("((?:월\\s*임차료|월임차료|임차료)\\s*[:：]?\\s*)($confusingZero)(?=\\s|$)")
            .replace(s) { it.groupValues[1] + "0" }
        return s
    }
}
