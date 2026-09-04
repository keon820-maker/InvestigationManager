package kr.co.investigation.manager.ocr

/** v0.29: 기타요청사항에서 반복되는 한글 OCR 오기를 문맥 기반으로 최종 보정한다. */
object NotesTypoRepairV29 {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val before = base.parsed.requestNotes
        val after = clean(before)
        if (before == after) return base

        return base.copy(
            parsed = base.parsed.copy(requestNotes = after),
            rawText = base.rawText + buildString {
                append("\n\n--- 기타요청사항 오기 보정 v0.29 ---\n")
                append("보정 전 : ").append(before.replace('\n', ' ')).append('\n')
                append("보정 후 : ").append(after.replace('\n', ' ')).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 기타요청사항 오기 보정 v0.29"
        )
    }

    private fun clean(value: String): String {
        var s = value

        // 현장 메모에서 자주 반복되는 OCR 오기. 기타요청사항 필드에만 적용한다.
        val replacements = listOf(
            Regex("통\\s*[호오와](?=\\s|가능|시간|후|부탁|\\)|,|\\.|$)") to "통화",
            Regex("통호\\s*\\(\\s*통호\\s*\\)") to "통화",
            Regex("치\\s*무\\s*[자지]") to "채무자",
            Regex("채\\s*무\\s*지") to "채무자",
            Regex("제\\s*무\\s*자") to "채무자",
            Regex("현장\\s*조시") to "현장조사",
            Regex("임대차\\s*현장\\s*조시") to "임대차현장조사",
            Regex("연락\\s*후\\s*방문") to "연락 후 방문",
            Regex("사전\\s*통화\\s*후\\s*방문") to "사전 통화 후 방문"
        )
        replacements.forEach { (pattern, replacement) -> s = s.replace(pattern, replacement) }

        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
            .replace(Regex("(?<=채무자)(?=[가-힣])"), " ")
            .replace(Regex("\\s+([,.])"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")

        return s.lines().joinToString("\n") { it.trim() }.trim()
    }
}
