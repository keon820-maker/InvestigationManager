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
                append("\n\n--- 기타요청사항 오기 보정 v0.35.4 ---\n")
                append("보정 전 : ").append(before.replace('\n', ' ')).append('\n')
                append("보정 후 : ").append(after.replace('\n', ' ')).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 기타요청사항 최종 중복·오기 보정 v0.35.4"
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

        val sourceLines = s.lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() }

        // raw/enhanced OCR가 두 번 이어 붙는 경우 두 번째 블록 앞에
        // '기타요청사항' 라벨(예: 기타요최시환, 기e요최시환)이 다시 나타난다.
        // 이미 본문을 하나 이상 확보한 뒤 라벨이 재등장하면 그 뒤는 중복 OCR 블록으로 보고 버린다.
        val cleaned = mutableListOf<String>()
        for (line in sourceLines) {
            if (looksLikeNotesHeader(line)) {
                if (cleaned.isNotEmpty()) break
                continue
            }
            cleaned += line
        }

        // 라벨 없이도 같은 행이 반복될 수 있으므로 종결점/띄어쓰기 차이까지 정규화해 한 번만 남긴다.
        val unique = linkedMapOf<String, String>()
        cleaned.forEach { line ->
            unique.putIfAbsent(canonicalLine(line), line)
        }
        return unique.values.joinToString("\n").trim()
    }

    private fun canonicalLine(line: String): String = line
        .replace(Regex("[\\s.,!?·ㆍ:：]+"), "")
        .trim()

    private fun looksLikeNotesHeader(line: String): Boolean {
        val compact = line.replace(Regex("[^가-힣A-Za-z0-9]"), "")
        val hangul = compact.replace(Regex("[^가-힣]"), "")
        if (hangul == "기타요청사항") return true

        // '기타요최시환', '기e요최시환'처럼 한두 글자가 깨진 짧은 라벨만 허용한다.
        // 일반 메모 문장이 잘리는 것을 막기 위해 짧은 독립 행에만 적용한다.
        return compact.length in 5..12 && compact.startsWith("기") &&
            (compact.contains("요최시") || compact.contains("요청사") ||
                hangul.contains("요최시") || hangul.contains("요청사") ||
                hangul.contains("요시환"))
    }
}
