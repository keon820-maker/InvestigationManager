package kr.co.investigation.manager.ocr

/**
 * v0.35.12: 후반 재OCR 패스가 끝난 뒤 형식상 명백한 잔여 오염만 정리한다.
 * 문서별 개인정보나 실제 값은 사용하지 않고 관리번호/영업점/조사구분의 구조만 검사한다.
 */
object FinalResultConsistencyV3512 {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val before = base.parsed
        val management = normalizeManagement(before.managementNo)
        val branch = normalizeBranch(before.branch)
        val investigationType = preserveInvestigationQualifier(before.investigationType, base.sourceText)
        val fixed = before.copy(
            managementNo = management.ifBlank { before.managementNo.trim() },
            branch = branch.ifBlank { before.branch.trim() },
            investigationType = investigationType
        )
        if (fixed == before) return base

        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 최종 형식 일관성 보정 v0.35.12 ---\n")
                append("관리번호 : ").append(fixed.managementNo).append('\n')
                append("조사구분 : ").append(fixed.investigationType).append('\n')
                append("영업점 : ").append(fixed.branch).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 최종 형식 일관성 보정 v0.35.12"
        )
    }

    /** 관리번호 일련번호는 현재 의뢰서 양식의 5자리까지만 보존한다. */
    internal fun normalizeManagement(value: String): String {
        val compact = value.replace(Regex("\\s+"), "").trim()
        val match = Regex("[가-힣A-Za-z]{0,10}20\\d{4}-\\d{5}").find(compact) ?: return compact
        return match.value
    }

    /**
     * `D : 판교역지점적` 같은 주변 OCR 문자를 제거한다.
     * 지점뿐 아니라 센터/출장소 및 `<출>` 표기를 정상 영업점명으로 허용한다.
     */
    internal fun normalizeBranch(value: String): String {
        if (value.isBlank()) return ""
        var s = value
            .replace('〈', '<')
            .replace('〉', '>')
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.replace(Regex("^[A-Za-z0-9]{1,3}\\s*[:：]\\s*"), "")
        val compact = s.replace(" ", "")

        Regex("[가-힣0-9]{2,30}(?:출장소|지점|센터)").find(compact)?.value?.let { return it }

        val outpost = Regex("([가-힣0-9]{2,30})[<(]?출[>)]?").find(compact)
        if (outpost != null) return outpost.groupValues[1] + "<출>"

        return s.trim(' ', ':', '：', '▷', '>')
    }

    /**
     * 중간 필드 파서가 괄호 안의 `현장조사`만 떨어뜨린 경우, 원 OCR의 조사구분 행에서
     * 해당 세부표기가 실제로 확인될 때에만 다시 붙인다. 기타요청사항의 `현장조사` 문구에는 반응하지 않는다.
     */
    internal fun preserveInvestigationQualifier(value: String, rawText: String): String {
        val current = value
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("\\s+"), "")
            .replace("현장조시", "현장조사")
            .trim()
        if (!current.contains("임대차조사") || current.contains("현장조사")) return current

        val rowShowsQualifier = Regex(
            "조\\s*사\\s*구\\s*분[\\s:：|>▷-]{0,12}[^\\n]{0,80}임\\s*대\\s*차[^\\n]{0,50}현\\s*장\\s*조\\s*[사시]",
            setOf(RegexOption.IGNORE_CASE)
        ).containsMatchIn(rawText)

        return if (rowShowsQualifier) {
            current.replace("임대차조사", "임대차조사(현장조사)")
        } else current
    }
}
