package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.35.16: 임차인 표의 라벨/깨진 OCR이 실제 사람 이름으로 저장되는 것을 강하게 차단한다.
 * 유효한 임차인 행만 남기고 빈 행은 JSON에서 제거해 UI에 허위 10개 행이 생성되지 않게 한다.
 */
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

            if (name.isBlank() && phone.isBlank()) {
                if (rawName.isNotBlank() || rawPhone.isNotBlank() || source.length() > 0) changed = true
                continue
            }

            cleaned.put(JSONObject().apply {
                put("name", name)
                put("phone", phone)
            })
        }

        if (!changed && cleaned.length() == source.length()) return base
        val fixed = c.copy(tenantsJson = cleaned.toString())
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + "\n\n--- 임차인 오검출 제거 v0.35.16 ---\n라벨 유사문자·빈 행·비정상 전화번호 제거 완료\n",
            preprocessMessage = base.preprocessMessage + " / 임차인 오검출 제거 v0.35.16"
        )
    }

    internal fun validTenantName(value: String): Boolean {
        val compact = value.replace(" ", "").trim()
        if (!Regex("[가-힣]{2,6}").matches(compact)) return false

        val badExact = setOf(
            "임차인", "임차인명", "성명", "스명", "전화", "전화번호", "전화번", "전호번", "전환번호", "번호",
            "연락처", "임차인성명", "핸드폰", "핸드폰번호",
            "임치인", "일치인", "의치인", "리초인", "임친인", "임자인", "입차인", "임차임", "임차언",
            // 실기기에서 임차인 라벨 일부가 잘려 사람 이름처럼 잡힌 경우
            "지인"
        )
        if (compact in badExact) return false
        if (compact.startsWith("임차") || compact.endsWith("차인")) return false
        if (compact.contains("전화") || compact.contains("번호") || compact.contains("성명") || compact.contains("연락")) return false

        if (editDistance(compact, "임차인") <= 1) return false
        if (editDistance(compact, "전화번호") <= 1) return false

        return true
    }

    internal fun validTenantPhone(value: String): Boolean {
        if (Regex("01[016789]-\\d{3,4}-\\d{4}").matches(value)) return true
        if (Regex("02-\\d{3,4}-\\d{4}").matches(value)) return true
        if (Regex("0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4]|70)-\\d{3,4}-\\d{4}").matches(value)) return true
        return false
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }
}
