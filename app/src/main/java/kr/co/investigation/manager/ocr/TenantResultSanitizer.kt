package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.35.21: 임차인 표의 라벨/깨진 OCR이 여러 명의 사람 이름으로 증식하는 문제를 차단한다.
 *
 * 단순히 '한글 2~6자'만으로 임차인을 만들지 않는다. 특히 실기기에서 임차인 표 라벨이
 * 일치리/초인/치인성일/은치인/자인/전호/일초기처럼 깨져 5~10개 행으로 생성되는 경우,
 * 전화번호가 없는 후보가 다수 발생하면 이를 OCR 표 구조 붕괴로 판단한다.
 */
object TenantResultSanitizer {
    private data class Candidate(val name: String, val phone: String)

    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val c = base.parsed
        val source = runCatching { JSONArray(c.tenantsJson) }.getOrNull() ?: return base
        val debtorName = c.debtorName.substringBefore('(').replace(" ", "").trim()
        val candidates = mutableListOf<Candidate>()
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
            candidates += Candidate(name, phone)
        }

        // 실기기 보호 규칙:
        // 정상 임차인 표라면 여러 행이 동시에 잡힐수록 전화번호도 함께 잡히는 비율이 높다.
        // 3개 이상 후보 중 70% 이상이 전화번호 없이 이름만 존재하면 라벨/표선 OCR 증식으로 본다.
        // 이 경우 전화번호가 실제로 읽힌 행과 채무자와 동일한 이름만 보존한다.
        val phoneLessCount = candidates.count { it.phone.isBlank() }
        val massHallucination = candidates.size >= 3 && phoneLessCount * 10 >= candidates.size * 7

        val filtered = if (massHallucination) {
            changed = true
            candidates.filter { candidate ->
                candidate.phone.isNotBlank() ||
                    (debtorName.isNotBlank() && candidate.name.replace(" ", "") == debtorName)
            }
        } else {
            candidates
        }

        // 동일한 이름/전화가 여러 줄로 중복 인식된 경우 하나만 유지한다.
        val deduped = filtered.distinctBy { it.name.replace(" ", "") + "|" + it.phone }
        if (deduped.size != filtered.size) changed = true

        val cleaned = JSONArray()
        deduped.take(10).forEach { item ->
            cleaned.put(JSONObject().apply {
                put("name", item.name)
                put("phone", item.phone)
            })
        }

        if (!changed && cleaned.length() == source.length()) return base
        val fixed = c.copy(tenantsJson = cleaned.toString())
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 임차인 구조 검증 v0.35.21 ---\n")
                append("원본 후보 : ").append(source.length()).append('\n')
                append("유효 후보 : ").append(candidates.size).append('\n')
                append("전화번호 없는 후보 : ").append(phoneLessCount).append('\n')
                append("다중 오검출 판단 : ").append(massHallucination).append('\n')
                append("최종 임차인 : ").append(cleaned.length()).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 임차인 구조 검증 v0.35.21"
        )
    }

    internal fun validTenantName(value: String): Boolean {
        val compact = value.replace(" ", "").trim()
        if (!Regex("[가-힣]{2,6}").matches(compact)) return false

        val badExact = setOf(
            "임차인", "임차인명", "성명", "스명", "전화", "전화번호", "전화번", "전호번", "전환번호", "번호",
            "연락처", "임차인성명", "핸드폰", "핸드폰번호",
            "임치인", "일치인", "의치인", "리초인", "임친인", "임자인", "입차인", "임차임", "임차언",
            "지인",
            // v0.35.20 실기기에서 확인된 임차인 표 라벨 파편
            "일치리", "초인", "치인성일", "은치인", "자인", "전호", "일초기"
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
