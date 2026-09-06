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
                append("\n\n--- 기타요청사항 오기 보정 v0.35.2 ---\n")
                append("보정 전 : ").append(before.replace('\n', ' ')).append('\n')
                append("보정 후 : ").append(after.replace('\n', ' ')).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 기타요청사항 중복·오기 보정 v0.35.2"
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
            Regex("사전\\s*통화\\s*후\\s*방문") to "사전 통화 후 방문",
            // 실사진에서 '확'이 '흑'으로 읽히는 경우. '입주 사실' 문맥으로만 한정한다.
            Regex("입주\\s*사실\\s*흑\\s*인") to "입주 사실 확인",
            Regex("본인\\s*입주\\s*사실\\s*흑\\s*인") to "본인 입주 사실 확인",
            // 작은 인쇄체에서 '월임차료'가 울은치료/울임차료 등으로 흔들리는 경우.
            Regex("[월울]\\s*[임은]\\s*[차치]\\s*[료로]\\s*[:：]?") to "월임차료:"
        )
        replacements.forEach { (pattern, replacement) -> s = s.replace(pattern, replacement) }

        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
            .replace(Regex("(?<=채무자)(?=[가-힣])"), " ")
            .replace(Regex("드립니\\s*다"), "드립니다")
            .replace(Regex("\\s+([,.])"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")

        val cleaned = s.lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() }
            // OCR raw+enhanced 결합 과정에서 중간에 끼어드는 깨진 '기타요청사항' 라벨 제거.
            .filterNot(::looksLikeNotesHeader)

        // raw/enhanced 두 패스가 같은 메모를 연속으로 붙이는 경우가 있어,
        // 오기 보정 후 동일해진 행은 첫 번째 것만 남긴다.
        val unique = linkedSetOf<String>()
        cleaned.forEach { line -> unique += line }
        return unique.joinToString("\n").trim()
    }

    private fun looksLikeNotesHeader(line: String): Boolean {
        val c = line.replace(Regex("[^가-힣]"), "")
        if (c == "기타요청사항") return true
        // 기타요최시환처럼 짧은 라벨 자체가 깨진 경우만 제거하고 일반 문장은 건드리지 않는다.
        return c.length in 5..10 && c.startsWith("기타요") &&
            (c.contains("사항") || c.contains("시환") || c.contains("시항") || c.contains("최시"))
    }
}
