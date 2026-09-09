package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/** Pure role mapping used after OCR so a correctly read number is not assigned to the wrong person. */
object ContactRoleResolverV3517 {
    data class Result(
        val debtorPhone: String,
        val debtorMobile: String,
        val ownerPhone: String,
        val tenantsJson: String
    )

    fun resolve(
        currentPhone: String,
        currentMobile: String,
        currentOwnerPhone: String,
        tenantsJson: String,
        debtorName: String,
        ownerName: String = "",
        debtorRowPhones: List<String>,
        mobileCellPhones: List<String>,
        ownerCellPhones: List<String>,
        tenant1Name: String,
        tenant1Phones: List<String>,
        excluded: Set<String>
    ): Result {
        fun safe(values: List<String>) = values.map(::normalizePhone).filter { it.isNotBlank() && it !in excluded }.distinct()
        val row = safe(debtorRowPhones)
        val mobileCell = safe(mobileCellPhones).filter { it.startsWith("01") }
        val ownerCell = safe(ownerCellPhones)
        val tenantPhones = safe(tenant1Phones)

        val curPhone = normalizePhone(currentPhone).takeIf { it !in excluded }.orEmpty()
        val curMobile = normalizePhone(currentMobile).takeIf { it !in excluded }.orEmpty()
        val curOwner = normalizePhone(currentOwnerPhone).takeIf { it !in excluded }.orEmpty()

        val detectedMobile = mobileCell.firstOrNull()
            ?: row.filter { it.startsWith("01") }.distinct().singleOrNull()
            ?: curMobile.takeIf { it.startsWith("01") }.orEmpty()

        // 전화번호와 휴대폰번호가 같은 문서도 있으나, 별도 전화 셀 근거가 없는 경우에는
        // 과거 실기기 오배치 방지를 위해 중복 전화번호를 비운다.
        val detectedPhone = curPhone.takeUnless { it.isNotBlank() && it == detectedMobile }.orEmpty()

        val debtor = bareName(debtorName)
        val ownerPerson = bareName(ownerName)
        val ownerSameAsDebtor = debtor.isNotBlank() && ownerPerson.isNotBlank() && debtor == ownerPerson

        // 소유자와 채무자가 동일인이면 같은 번호가 소유자 연락처에 반복되는 것이 정상일 수 있다.
        // 이 경우 소유자 셀에서 실제로 읽힌 번호는 채무자 번호와 같아도 보존한다.
        val ownerFromCell = ownerCell.firstOrNull {
            ownerSameAsDebtor || (it != detectedMobile && it != detectedPhone)
        }
        val owner = when {
            ownerFromCell != null -> ownerFromCell
            curOwner.isNotBlank() && (ownerSameAsDebtor || (curOwner != detectedMobile && curOwner != detectedPhone)) -> curOwner
            else -> ""
        }

        val source = runCatching { JSONArray(tenantsJson) }.getOrElse { JSONArray() }
        val old = source.optJSONObject(0)
        val oldName = old?.optString("name").orEmpty().ifBlank { old?.optString("tenantName").orEmpty() }
        val oldPhone = normalizePhone(old?.optString("phone").orEmpty().ifBlank { old?.optString("mobile").orEmpty() })

        val tName = oldName.takeIf(TenantResultSanitizer::validTenantName).orEmpty()
            .ifBlank { tenant1Name.takeIf(TenantResultSanitizer::validTenantName).orEmpty() }
        val tPhone = if (tName.isBlank()) {
            ""
        } else {
            oldPhone.takeIf(TenantResultSanitizer::validTenantPhone).orEmpty()
                .ifBlank { tenantPhones.firstOrNull().orEmpty() }
        }

        // v0.35.25: 임차인 셀에 실제 유효 이름이 없으면 채무자 이름/번호를 임차인으로 자동 생성하지 않는다.
        val out = JSONArray()
        if (tName.isNotBlank()) {
            out.put(JSONObject().apply { put("name", tName); put("phone", tPhone) })
        }
        for (i in 1 until source.length().coerceAtMost(10)) source.optJSONObject(i)?.let(out::put)

        return Result(detectedPhone, detectedMobile, owner, out.toString())
    }

    fun normalizePhone(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0,3)}-${d.substring(3,7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2,6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2,5)}-${d.substring(5)}"
            d.length == 10 && d.substring(0,3) in prefixes -> "${d.substring(0,3)}-${d.substring(3,6)}-${d.substring(6)}"
            d.length == 11 && d.substring(0,3) in prefixes -> "${d.substring(0,3)}-${d.substring(3,7)}-${d.substring(7)}"
            else -> ""
        }
    }

    private fun bareName(value: String): String = value.substringBefore('(').replace(" ", "").trim()

    private val prefixes = setOf("031","032","033","041","042","043","044","051","052","053","054","055","061","062","063","064","070","080")
}
