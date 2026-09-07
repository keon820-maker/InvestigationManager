package kr.co.investigation.manager.ocr

/**
 * v0.35.12: 후반 재OCR 패스가 끝난 뒤 형식상 명백한 잔여 오염만 정리한다.
 * 문서별 개인정보나 실제 값은 사용하지 않고 관리번호/영업점의 구조만 검사한다.
 */
object FinalResultConsistencyV3512 {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val before = base.parsed
        val management = normalizeManagement(before.managementNo)
        val branch = normalizeBranch(before.branch)
        val fixed = before.copy(
            managementNo = management.ifBlank { before.managementNo.trim() },
            branch = branch.ifBlank { before.branch.trim() }
        )
        if (fixed == before) return base

        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 최종 형식 일관성 보정 v0.35.12 ---\n")
                append("관리번호 : ").append(fixed.managementNo).append('\n')
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
}
