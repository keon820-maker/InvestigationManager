package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.35.25: 임차인 표의 라벨/주소 조각/깨진 OCR이 실제 임차인으로 생성되는 문제를 차단한다.
 *
 * 핵심 원칙은 '유효한 사람 이름이 없으면 임차인 행 자체를 만들지 않는다'이다.
 * 라벨이나 행정구역명이 이름 칸으로 들어오고 다른 칸의 전화번호만 정상 인식된 경우에도
 * 임차인 행 전체를 제거한다.
 */
object TenantResultSanitizer {
    private data class Candidate(val name: String, val phone: String)

    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val c = base.parsed
        val source = runCatching { JSONArray(c.tenantsJson) }.getOrNull() ?: return base
        val candidates = mutableListOf<Candidate>()
        var changed = false

        for (i in 0 until minOf(source.length(), 10)) {
            val old = source.optJSONObject(i) ?: JSONObject()
            val rawName = old.optString("name").ifBlank { old.optString("tenantName") }.trim()
            val rawPhone = old.optString("phone").ifBlank { old.optString("mobile") }.trim()

            // 이름이 비어 있거나 양식/주소 라벨이면 전화번호가 정상이어도 행 전체를 버린다.
            if (!validTenantName(rawName)) {
                if (rawName.isNotBlank() || rawPhone.isNotBlank()) changed = true
                continue
            }

            val name = normalizeTenantName(rawName)
            val phone = rawPhone.takeIf(::validTenantPhone).orEmpty()
            if (name != rawName.replace(" ", "").trim() || phone != rawPhone) changed = true
            candidates += Candidate(name, phone)
        }

        // 3개 이상 후보 중 70% 이상이 전화번호 없는 이름뿐이면 표 구조 붕괴 가능성이 높다.
        // 이 경우 전화번호까지 함께 확인된 행만 보존한다. 채무자 이름을 임차인으로 자동 승격하지 않는다.
        val phoneLessCount = candidates.count { it.phone.isBlank() }
        val massHallucination = candidates.size >= 3 && phoneLessCount * 10 >= candidates.size * 7

        val filtered = if (massHallucination) {
            changed = true
            candidates.filter { it.phone.isNotBlank() }
        } else {
            candidates
        }

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
                append("\n\n--- 임차인 구조 검증 v0.35.25 ---\n")
                append("원본 후보 : ").append(source.length()).append('\n')
                append("유효 이름 후보 : ").append(candidates.size).append('\n')
                append("전화번호 없는 후보 : ").append(phoneLessCount).append('\n')
                append("다중 오검출 판단 : ").append(massHallucination).append('\n')
                append("최종 임차인 : ").append(cleaned.length()).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 임차인 구조 검증 v0.35.25"
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
            // 실기기에서 확인된 역할/필드 라벨 및 OCR 흔들림
            "소유사", "소유쟈", "소유쥬", "물건", "물건소유", "물건소유자", "수소",
            // 주소 행이 임차인 이름 셀로 미끄러져 들어온 경우
            "경기도", "강원도", "충청북도", "충청남도", "전라북도", "전라남도", "경상북도", "경상남도", "제주도",
            "서울시", "부산시", "대구시", "인천시", "광주시", "대전시", "울산시", "세종시",
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
        if (compact.startsWith("물건소유")) return false

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
