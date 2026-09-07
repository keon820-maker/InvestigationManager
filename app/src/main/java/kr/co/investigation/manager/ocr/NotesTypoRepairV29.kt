package kr.co.investigation.manager.ocr

/** v0.29+: 기타요청사항에서 반복되는 한글 OCR 오기와 중복 OCR 블록을 문맥 기반으로 최종 보정한다. */
object NotesTypoRepairV29 {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val before = base.parsed.requestNotes
        val after = clean(before)
        if (before == after) return base

        return base.copy(
            parsed = base.parsed.copy(requestNotes = after),
            rawText = base.rawText + buildString {
                append("\n\n--- 기타요청사항 오기 보정 v0.35.11 ---\n")
                append("보정 전 : ").append(before.replace('\n', ' ')).append('\n')
                append("보정 후 : ").append(after.replace('\n', ' ')).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 기타요청사항 최종 중복·오기 보정 v0.35.11"
        )
    }

    private fun clean(value: String): String {
        var s = value

        val replacements = listOf(
            Regex("통\\s*[호오와](?=\\s|가능|시간|후|부탁|\\)|,|\\.|$)") to "통화",
            Regex("통호\\s*\\(\\s*통호\\s*\\)") to "통화",
            Regex("치\\s*무\\s*[자지]") to "채무자",
            Regex("채\\s*무\\s*지") to "채무자",
            Regex("제\\s*무\\s*자") to "채무자",
            Regex("현장\\s*조시") to "현장조사",
            Regex("임대차\\s*현장\\s*조시") to "임대차현장조사",
            Regex("임\\s*[대데]\\s*[차치]\\s*조\\s*[사시]") to "임대차조사",
            Regex("연락\\s*후\\s*방문") to "연락 후 방문",
            Regex("사전\\s*통화\\s*후\\s*방문") to "사전 통화 후 방문",
            Regex("입주\\s*사실\\s*흑\\s*인") to "입주 사실 확인",
            Regex("본인\\s*입주\\s*사실\\s*흑\\s*인") to "본인 입주 사실 확인",
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

        val cleaned = mutableListOf<String>()
        for (line in sourceLines) {
            val inlineHeaderStart = findInlineNotesHeaderStart(line)
            if (inlineHeaderStart >= 0) {
                val rawPrefix = line.substring(0, inlineHeaderStart).trim()
                val sectionMarkerOnly = rawPrefix.matches(Regex("^[0-9A-Za-z가-힣]{1,2}[.)．:]?$"))
                if (!sectionMarkerOnly) {
                    val prefix = rawPrefix
                        .trimEnd('.', ',', '·', 'ㆍ')
                        .trim()
                    if (prefix.isNotBlank()) cleaned += prefix
                }
                if (cleaned.isNotEmpty()) break
                continue
            }

            if (looksLikeNotesHeader(line)) {
                if (cleaned.isNotEmpty()) break
                continue
            }
            cleaned += line
        }

        val unique = linkedMapOf<String, String>()
        cleaned.forEach { line -> unique.putIfAbsent(canonicalLine(line), line) }
        return unique.values.joinToString("\n").trim()
    }

    private fun canonicalLine(line: String): String = line
        .replace(Regex("[\\s.,!?·ㆍ:：]+"), "")
        .trim()

    /**
     * v0.35.11: `임대차종료일자:20280928. 기타요청 사항`처럼 정상 내용 뒤에
     * 두 번째 OCR 블록의 섹션명이 같은 줄로 붙는 경우를 자른다.
     * 짧은 섹션명 패턴만 찾으므로 일반 메모 문장의 `요청` 단어에는 반응하지 않는다.
     */
    private fun findInlineNotesHeaderStart(line: String): Int {
        val patterns = listOf(
            Regex("기\\s*타\\s*요\\s*[청추침]\\s*사\\s*항"),
            Regex("기\\s*타\\s*요\\s*청\\s*사\\s*항"),
            Regex("기\\s*타\\s*요\\s*최\\s*시\\s*환"),
            Regex("기\\s*[eE]\\s*요\\s*최\\s*시\\s*환")
        )
        return patterns.mapNotNull { it.find(line)?.range?.first }.minOrNull() ?: -1
    }

    private fun looksLikeNotesHeader(line: String): Boolean {
        val compact = line.replace(Regex("[^가-힣A-Za-z0-9]"), "")
        val hangulOnly = compact.replace(Regex("[^가-힣]"), "")
        val firstGi = hangulOnly.indexOf('기')
        val candidate = if (firstGi >= 0) hangulOnly.substring(firstGi) else hangulOnly
        val target = "기타요청사항"

        if (candidate == target) return true

        // '다. 기타요추 사항'처럼 섹션 표식이 한글로 읽히거나 한두 글자가 흔들린 경우까지 처리한다.
        // 짧은 독립 행에만 적용해 실제 메모 문장을 잘못 자르지 않는다.
        if (candidate.length in 4..8 && editDistance(candidate, target) <= 2) return true

        // 기존 실기기에서 확인된 더 심한 깨짐도 계속 허용한다.
        return candidate.length in 4..12 && candidate.startsWith("기") &&
            (candidate.contains("요최시") || candidate.contains("요청사") ||
                candidate.contains("요침사") || candidate.contains("요시환"))
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            previous = current
        }
        return previous[b.length]
    }
}
