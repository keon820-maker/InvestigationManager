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

        // 대상자 행에서 휴대폰이 하나만 보이면 전화번호 칸이 아니라 휴대폰번호로 취급한다.
        val detectedMobile = mobileCell.firstOrNull()
            ?: row.filter { it.startsWith("01") }.distinct().singleOrNull()
            ?: curMobile.takeIf { it.startsWith("01") }.orEmpty()

        // 종이 원본에서 전화번호 칸이 공란인데 같은 010이 전화번호로 잘못 매핑된 경우 제거한다.
        val detectedPhone = curPhone.takeUnless { it.isNotBlank() && it == detectedMobile }.orEmpty()

        // 소유자 번호는 소유자 셀에서 읽힌 번호를 최우선한다. 대상자 번호와 같은 값은 교차누수로 본다.
        val ownerFromCell = ownerCell.firstOrNull { it != detectedMobile && it != detectedPhone }
        val owner = when {
            ownerFromCell != null -> ownerFromCell
            curOwner.isNotBlank() && curOwner != detectedMobile && curOwner != detectedPhone -> curOwner
            else -> ""
        }

        val source = runCatching { JSONArray(tenantsJson) }.getOrElse { JSONArray() }
        val old = source.optJSONObject(0)
        val oldName = old?.optString("name").orEmpty().ifBlank { old?.optString("tenantName").orEmpty() }
        val oldPhone = normalizePhone(old?.optString("phone").orEmpty().ifBlank { old?.optString("mobile").orEmpty() })
        val debtor = debtorName.substringBefore('(').replace(" ", "").trim()

        var tName = oldName.takeIf(TenantResultSanitizer::validTenantName).orEmpty()
            .ifBlank { tenant1Name.takeIf(TenantResultSanitizer::validTenantName).orEmpty() }
        var tPhone = oldPhone.takeIf(TenantResultSanitizer::validTenantPhone).orEmpty()
            .ifBlank { tenantPhones.firstOrNull().orEmpty() }

        // 이 양식처럼 채무자와 임차인이 같은 사람인 경우 동일 번호 중복은 정상이다.
        if (tPhone.isBlank() && detectedMobile.isNotBlank() && (tName == debtor || tName.isBlank())) tPhone = detectedMobile
        if (tName.isBlank() && tPhone.isNotBlank() && tPhone == detectedMobile && TenantResultSanitizer.validTenantName(debtor)) tName = debtor

        val out = JSONArray()
        if (tName.isNotBlank() || tPhone.isNotBlank()) {
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

    private val prefixes = setOf("031","032","033","041","042","043","044","051","052","053","054","055","061","062","063","064","070","080")
}
