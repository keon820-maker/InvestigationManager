package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/** v0.25: 빈 임차인 셀의 라벨/깨진 전화번호가 실제 임차인으로 저장되는 것을 차단한다. */
object TenantResultSanitizer {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val c = base.parsed
        val source = runCatching { JSONArray(c.tenantsJson) }.getOrNull() ?: return base
        val cleaned = JSONArray()
        var changed = false

        for (i in 0 until minOf(source.length(), 10)) {
            val old = source.optJSONObject(i) ?: JSONObject()
            val rawName = old.optString("name").ifBlank { old.optString("tenantName") }.trim()
            val rawPhone = old.optString("phone").ifBlank { old.optString("mobile") }.trim()
            val name = rawName.takeIf(::validTenantName).orEmpty()
            val phone = rawPhone.takeIf(::validTenantPhone).orEmpty()
            if (name != rawName || phone != rawPhone) changed = true
            cleaned.put(JSONObject().apply {
                put("name", name)
                put("phone", phone)
            })
        }

        if (!changed) return base
        val fixed = c.copy(tenantsJson = cleaned.toString())
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + "\n\n--- 임차인 오검출 제거 v0.25 ---\n빈 셀 라벨 및 비정상 전화번호 제거 완료\n",
            preprocessMessage = base.preprocessMessage + " / 임차인 오검출 제거 v0.25"
        )
    }

    private fun validTenantName(value: String): Boolean {
        if (!Regex("[가-힣]{2,6}").matches(value)) return false
        val compact = value.replace(" ", "")
        val badExact = setOf(
            "임차인", "임차인명", "성명", "스명", "전화", "전화번호", "전환번호", "번호",
            "연락처", "임차인성명", "핸드폰", "핸드폰번호"
        )
        if (compact in badExact) return false
        if (compact.startsWith("임차인")) return false
        if (compact.contains("전화") || compact.contains("번호") || compact.contains("성명")) return false
        return true
    }

    private fun validTenantPhone(value: String): Boolean {
        if (Regex("01[016789]-\\d{3,4}-\\d{4}").matches(value)) return true
        if (Regex("02-\\d{3,4}-\\d{4}").matches(value)) return true
        if (Regex("0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4]|70)-\\d{3,4}-\\d{4}").matches(value)) return true
        return false
    }
}
