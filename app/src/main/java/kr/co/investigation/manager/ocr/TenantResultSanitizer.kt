package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.35.23: 임차인 표의 라벨/깨진 OCR이 실제 임차인으로 생성되는 문제를 차단한다.
 *
 * 실기기에서 확인된 핵심 원칙은 '유효한 사람 이름이 없으면 임차인 행 자체를 만들지 않는다'이다.
 * 라벨이 이름 칸으로 들어오고 다른 칸의 전화번호만 정상 인식된 경우에도 전화번호만 남긴 임차인을
 * 생성하지 않는다. 또한 소유자 라벨의 OCR 흔들림(예: 소유사)도 제한적으로 차단한다.
 */
object TenantResultSanitizer {
    private data class Candidate(val name: String, val phone: String)

    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val c = base.parsed
        val source = runCatching { JSONArray(c.tenantsJson) }.getOrNull() ?: return base
        val debtorName = c.debtorName.substringBefore('(').filter { it in '가'..'힣' }
        val candidates = mutableListOf<Candidate>()
        var changed = false

        for (i in 0 until minOf(source.length(), 10)) {
            val old = source.optJSONObject(i) ?: JSONObject()
            val rawName = old.optString("name").ifBlank { old.optString("tenantName") }.trim()
            val rawPhone = old.optString("phone").ifBlank { old.optString("mobile") }.trim()

            // v0.35.23: 이름이 비어 있거나 양식 라벨이면 전화번호가 정상이어도 행 전체를 버린다.
            // 이전에는 잘못된 이름만 비우고 전화번호를 남겨 '전화번호만 있는 임차인'이 생성될 수 있었다.
            if (!validTenantName(rawName)) {
                if (rawName.isNotBlank() || rawPhone.isNotBlank()) changed = true
                continue
            }

            val name = normalizeTenantName(rawName)
            val phone = rawPhone.takeIf(::validTenantPhone).orEmpty()
            if (name != rawName.replace(" ", "").trim() || phone != rawPhone) changed = true
            candidates += Candidate(name, phone)
        }

        // 실기기 보호 규칙:
        // 정상 임차인 표라면 여러 행이 동시에 잡힐수록 전화번호도 함께 잡히는 비율이 높다.
        // 3개 이상 후보 중 70% 이상이 전화번호 없이 이름만 존재하면 표 구조 붕괴 가능성이 높다.
        // 이 경우 전화번호가 실제로 읽힌 행과 채무자와 동일한 이름만 보존한다.
        val phoneLessCount = candidates.count { it.phone.isBlank() }
        val massHallucination = candidates.size >= 3 && phoneLessCount * 10 >= candidates.size * 7

        val filtered = if (massHallucination) {
            changed = true
            candidates.filter { candidate ->
                candidate.phone.isNotBlank() ||
                    (debtorName.isNotBlank() && candidate.name == debtorName)
            }
        } else {
            candidates
        }

        // 동일한 이름/전화가 여러 줄로 중복 인식된 경우 하나만 유지한다.
        val deduped = filtered.distinctBy { it.name + "|" + it.phone }
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
                append("\n\n--- 임차인 구조 검증 v0.35.23 ---\n")
                append("원본 후보 : ").append(source.length()).append('\n')
                append("유효 이름 후보 : ").append(candidates.size).append('\n')
                append("전화번호 없는 후보 : ").append(phoneLessCount).append('\n')
                append("다중 오검출 판단 : ").append(massHallucination).append('\n')
                append("최종 임차인 : ").append(cleaned.length()).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 임차인 구조 검증 v0.35.23"
        )
    }

    internal fun validTenantName(value: String): Boolean {
        val compact = normalizeTenantName(value)
        if (!Regex("[가-힣]{2,6}").matches(compact)) return false

        val badExact = setOf(
            // 임차인 표 자체의 라벨
            "임차인", "임차인명", "임차인성명", "성명", "스명", "전화", "전화번호", "전화번", "전호번", "전환번호", "번호",
            "연락처", "핸드폰", "핸드폰번호", "관계", "관계인", "비고", "주소", "임차주소",
            // 다른 역할/설명 라벨이 임차인 이름 칸으로 새는 경우
            "소유자", "소유주", "소유자명", "소유주명", "채무자", "채무자명", "임대인", "세입자",
            "지인", "지인명", "지인성명", "본인", "본인거주",
            // 실기기에서 확인된 역할 라벨 OCR 흔들림. 사람 이름과 충돌 가능성이 큰 포괄적 '소유*' 차단은 하지 않는다.
            "소유사", "소유쟈", "소유쥬",
            // 임차인 라벨 OCR 흔들림
            "임치인", "일치인", "의치인", "리초인", "임친인", "임자인", "입차인", "임차임", "임차언",
            // 이전 실기기에서 확인된 임차인 표 라벨 파편
            "일치리", "초인", "치인성일", "은치인", "자인", "전호", "일초기"
        )
        if (compact in badExact) return false
        if (compact.startsWith("임차") || compact.endsWith("차인")) return false
        if (compact.contains("전화") || compact.contains("번호") || compact.contains("성명") || compact.contains("연락")) return false
        if (compact.contains("소유자") || compact.contains("소유주") || compact.contains("채무자")) return false
        if (compact.contains("임대인") || compact.contains("세입자") || compact.contains("지인성명")) return false

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

    private fun normalizeTenantName(value: String): String = value
        .filter { it in '가'..'힣' }
        .trim()

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
