package kr.co.investigation.manager.ocr

/** 개인정보 원문이나 실사용 값을 포함하지 않는 순수 OCR 후처리 규칙. */
internal object OcrFieldNormalizer {
    fun debtorIdentity(value: String): String {
        val stripped = stripLabels(value, "채무자명", "채무자 명", "대상자")
            .replace('（', '(')
            .replace('）', ')')
            .replace('–', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
        val name = Regex("[가-힣]{2,6}").find(stripped)?.value.orEmpty()
        if (name.isBlank()) return ""

        val tail = stripped.substringAfter(name, "")
            .uppercase()
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
        val birth = Regex("(?<!\\d)(\\d{6})(?!\\d)").find(tail)?.groupValues?.get(1).orEmpty()
        if (birth.isBlank()) return name

        val afterBirth = tail.substringAfter(birth, "")
        val hasMaskedTail = Regex("[- ]*[1-4*X●○•]").containsMatchIn(afterBirth)
        val identity = if (hasMaskedTail) "$birth-*" else birth
        return "$name($identity)"
    }

    fun loanType(value: String): String {
        val stripped = stripLabels(value, "대출종류", "대출 종류")
            .replace(Regex("[|:：○Oo]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compact = stripped.replace(" ", "")
        // 태블릿 ML Kit가 작은 인쇄체의 '담'을 모양이 비슷한 글자로 읽는 경우가 있다.
        // 앞뒤가 정확히 '부동산…보대출'인 문맥에만 한정해 과도한 보정을 막는다.
        val contextual = compact.replace(
            Regex("부동산[남당탐닮]보대출"),
            "부동산담보대출"
        )
        return when {
            contextual.contains("부동산담보대출") -> "부동산 담보대출"
            contextual.contains("주택구입자금대출") -> "주택구입자금대출"
            contextual.contains("주택담보대출") -> "주택담보대출"
            contextual.contains("전세자금대출") -> "전세자금대출"
            contextual.contains("신용대출") -> "신용대출"
            contextual.contains("담보대출") -> "담보대출"
            contextual.contains("대출") && stripped.length <= 50 -> stripped
            else -> ""
        }
    }

    fun preferDebtor(current: String, candidate: String): String {
        val a = debtorIdentity(current)
        val b = debtorIdentity(candidate)
        val hasBirth = Regex("\\(\\d{6}")
        return when {
            hasBirth.containsMatchIn(b) && !hasBirth.containsMatchIn(a) -> b
            a.isNotBlank() -> a
            else -> b
        }
    }

    fun preferLoan(current: String, candidate: String): String {
        val a = loanType(current)
        val b = loanType(candidate)
        return when {
            b == "부동산 담보대출" && a == "담보대출" -> b
            a.isNotBlank() -> a
            else -> b
        }
    }

    fun redactInvestigatorSection(raw: String): String {
        if (raw.isBlank()) return raw
        val result = mutableListOf<String>()
        var skipping = false
        raw.lines().forEach { line ->
            val compact = compact(line)
            val startsSection = compact.contains("조사담당자")
            val boundary = compact.contains("채무자명") || compact.contains("대상자") ||
                compact.contains("의뢰내용") || compact.contains("완료요청일") ||
                compact.startsWith("---")
            when {
                startsSection -> {
                    if (result.lastOrNull() != "조사담당자 : [OCR 제외]") {
                        result += "조사담당자 : [OCR 제외]"
                    }
                    skipping = true
                }
                skipping && boundary -> {
                    skipping = false
                    result += line
                }
                skipping -> Unit
                else -> result += line
            }
        }
        return result.joinToString("\n").trim()
    }

    private fun stripLabels(value: String, vararg labels: String): String {
        var out = value
        labels.forEach { label ->
            val pattern = label.replace(" ", "").map { Regex.escape(it.toString()) }.joinToString("\\s*")
            out = out.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
        }
        return out.trim(' ', ':', '：', '|', '·', '-')
    }

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")
}
