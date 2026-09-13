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
                append("\n\n--- 기타요청사항 오기 보정 v0.35.25 ---\n")
                append("보정 전 : ").append(before.replace('\n', ' ')).append('\n')
                append("보정 후 : ").append(after.replace('\n', ' ')).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 기타요청사항 중복·오기 보정 v0.35.25"
        )
    }

    internal fun clean(value: String): String {
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
            Regex("[월울]\\s*[임은]\\s*[차치초]\\s*[료로]\\s*[:：]?") to "월임차료:"
        )
        replacements.forEach { (pattern, replacement) -> s = s.replace(pattern, replacement) }

        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
            .replace(Regex("(?<=채무자)(?=[가-힣])"), " ")
            .replace(Regex("드립니\\s*다"), "드립니다")
            .replace(Regex("\\s+([,.])"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")

        return cleanRepeatedSections(s)
    }

    /**
     * Also safe to apply to an existing editor draft: remove structural duplicate
     * sections, never delete all text after a repeated heading. Conflicting numbers,
     * dates, people roles or visit instructions remain visible for review.
     */
    internal fun cleanRepeatedSections(value: String): String {
        val sourceLines = value.lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .map { it.trimStart('|', '｜', '│') .trimStart() }
            .filter { it.isNotBlank() }

        val cleaned = mutableListOf<String>()
        var repeatedSection = false
        fun addLine(line: String) {
            if (line.isBlank()) return
            if (cleaned.any { canonicalLine(it) == canonicalLine(line) }) return
            // Only known OCR-confusion spellings may share an identity. A generic
            // edit-distance match can delete distinct requests such as 등기부/등기일.
            if (repeatedSection && cleaned.any { isRepeatedReading(it, line) }) return
            cleaned += line
        }
        for (line in sourceLines) {
            val header = findInlineNotesHeader(line)
            if (header != null) {
                val rawPrefix = line.substring(0, header.range.first).trim()
                val sectionMarkerOnly = rawPrefix.matches(Regex("^[0-9A-Za-z가-힣]{1,2}[.)．:]?$"))
                if (!sectionMarkerOnly) {
                    val prefix = rawPrefix
                        .trimEnd('.', ',', '·', 'ㆍ')
                        .trim()
                    addLine(prefix)
                }
                repeatedSection = cleaned.isNotEmpty()
                addLine(line.substring(header.range.last + 1).trimStart(' ', ':', '：', '|'))
                continue
            }

            if (looksLikeRepeatedNotesMarker(line) || looksLikeNotesHeader(line)) {
                repeatedSection = cleaned.isNotEmpty()
                continue
            }

            if (looksLikeTrailingFooterNoise(line, cleaned)) continue
            addLine(line)
        }

        return cleaned.joinToString("\n").trim()
    }

    private fun canonicalLine(line: String): String = line
        .trim().trimEnd('.', '。')
        .replace('：', ':')
        .replace(Regex("[\\s,!?·ㆍ|｜│]+"), "")

    private fun findInlineNotesHeader(line: String): MatchResult? {
        val patterns = listOf(
            Regex("기\\s*타\\s*요\\s*[청천추침]\\s*[사시]\\s*항"),
            Regex("기\\s*타\\s*요\\s*최\\s*시\\s*환"),
            Regex("기\\s*[eE]\\s*요\\s*최\\s*시\\s*환")
        )
        return patterns.mapNotNull { it.find(line) }
            .filter { match ->
                val after = line.getOrNull(match.range.last + 1)
                // "기타요청사항은 ..." is a sentence, not a section heading.
                after == null || after.isWhitespace() || after in ":：|"
            }.minByOrNull { it.range.first }
    }

    private fun isRepeatedReading(first: String, repeated: String): Boolean {
        // Normalization is comparison-only: keep the first text verbatim. No
        // numeric substitutions or general similarity threshold are permitted.
        fun readingKey(value: String): String = canonicalLine(value)
            .replace(Regex("(?:때|[Cc])출(?=실행|완료)"), "대출")
            .replace("임미차확인", "임대차확인")
            .replace("임다츠현장조사", "임대차현장조사")
            .replace("입주사실흑인", "입주사실확인")
            .replace("계약흑인요청", "계약확인요청")
            .replace("본인기주", "본인거주")
            .replace("부탁드립니드", "부탁드립니다")
        return readingKey(first) == readingKey(repeated)
    }

    private fun looksLikeRepeatedNotesMarker(line: String): Boolean {
        val compact = line.replace(Regex("\\s+"), "")
        if (compact.length !in 4..18) return false
        if (!compact.contains("요청") || !compact.endsWith("항")) return false
        if (compact.contains("방문") || compact.contains("연락") || compact.contains("보증금")) return false
        return Regex("^[0-9A-Za-z가-힣.()\\-]+$").matches(compact)
    }

    private fun looksLikeTrailingFooterNoise(line: String, cleaned: List<String>): Boolean {
        if (cleaned.isEmpty()) return false
        val last = canonicalLine(cleaned.last())
        if (!last.startsWith("월임차료")) return false

        val compact = line.replace(Regex("\\s+"), "")
        if (compact.length !in 4..10) return false
        if (line.contains(':') || line.contains('：') || line.contains("원") ||
            line.contains("일자") || line.contains("기간") || line.contains("계약") ||
            line.contains("요청") || line.contains("방문") || line.contains("연락")) return false

        return Regex("^[가-힣]{2,4}\\d{2,4}[가-힣]{1,3}$").matches(compact)
    }

    private fun looksLikeNotesHeader(line: String): Boolean {
        val compact = line.replace(Regex("[^가-힣A-Za-z0-9]"), "")
        val hangulOnly = compact.replace(Regex("[^가-힣]"), "")
        val firstGi = hangulOnly.indexOf('기')
        val candidate = if (firstGi >= 0) hangulOnly.substring(firstGi) else hangulOnly
        val target = "기타요청사항"

        if (candidate == target) return true
        if (candidate.length in 4..8 && editDistance(candidate, target) <= 2) return true

        return candidate.length in 4..12 && candidate.startsWith("기") &&
            (candidate.contains("요최시") || candidate.contains("요청사") ||
                candidate.contains("요침사") || candidate.contains("요시환") ||
                candidate.contains("요천시"))
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
