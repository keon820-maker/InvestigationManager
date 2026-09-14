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
            .let { row ->
                val nextField = Regex("전\\s*화\\s*번\\s*호|핸\\s*드\\s*폰\\s*번\\s*호|완\\s*료\\s*요\\s*청\\s*일")
                    .find(row)?.range?.first
                nextField?.let { row.substring(0, it).trim(' ', '|', ':', '：') } ?: row
            }
        // The debtor may be a corporation. A six-character person-name match used
        // to turn a full company name into its legal prefix plus the first syllables.
        val identityStart = Regex("\\(\\s*[0-9OoIiLl]{6}(?![0-9OoIiLl])").find(stripped)?.range?.first
        val nameCell = identityStart?.let { stripped.substring(0, it) } ?: stripped
        val company = LegalEntityNames.normalize(nameCell)
        if (company.isBlank() && LegalEntityNames.hasMarker(nameCell)) return ""
        val name = company.ifBlank { Regex("[가-힣]{2,6}").find(stripped)?.value.orEmpty() }
        if (name.isBlank()) return ""

        val tail = (if (company.isNotBlank()) identityStart?.let(stripped::substring).orEmpty()
            else stripped.substringAfter(name, ""))
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

        // 실제 의뢰서의 작은 인쇄체에서 반복되는 한 글자 오인식을 문맥이 확실한 경우에만 보정한다.
        val contextual = compact
            .replace(Regex("부동산[남당탐닮]보대출"), "부동산담보대출")
            .replace(Regex("전세자[금긍]"), "전세자금")
            .replace(Regex("주택구입자[금긍]"), "주택구입자금")
            .replace(Regex("경락자[금긍]"), "경락자금")
        val semantic = contextual.replace(Regex("[()（）\\[\\]]"), "")
            // Restrict this glyph correction to the complete known product label.
            .replace(Regex("^전세자금보증세$"), "전세자금보증서")

        return when {
            semantic.contains("부동산담보대출") -> "부동산 담보대출"
            semantic.contains("전세자금보증서") -> "전세자금(보증서)"
            semantic.contains("주택구입자금대출") -> "주택구입자금대출"
            semantic.contains("경락자금대출") -> "경락자금대출"
            semantic.contains("주택담보대출") -> "주택담보대출"
            semantic.contains("전세자금대출") -> "전세자금대출"
            semantic.contains("신용대출") -> "신용대출"
            semantic.contains("담보대출") -> "담보대출"
            semantic.contains("대출") && stripped.length <= 50 -> stripped
            else -> ""
        }
    }

    fun preferDebtor(current: String, candidate: String): String {
        val a = debtorIdentity(current)
        val b = debtorIdentity(candidate)
        val hasBirth = Regex("\\(\\d{6}")
        val aName = withoutIdentity(a)
        val bName = withoutIdentity(b)
        val aIdentity = a.removePrefix(aName)
        val bIdentity = b.removePrefix(bName)
        return when {
            // Only extend an explicitly corporate prefix when its existing identity
            // agrees. Never combine unrelated names or conflicting identity numbers.
            LegalEntityNames.normalize(bName).isNotBlank() && LegalEntityNames.hasMarker(aName) &&
                bName.startsWith(aName) && bName.length > aName.length &&
                (aIdentity.isBlank() || aIdentity == bIdentity) -> b
            LegalEntityNames.normalize(aName).isNotBlank() && LegalEntityNames.normalize(bName).isNotBlank() &&
                aName.startsWith(bName) && aName.length > bName.length -> a
            hasBirth.containsMatchIn(b) && !hasBirth.containsMatchIn(a) -> b
            a.isNotBlank() -> a
            else -> b
        }
    }

    internal fun withoutIdentity(value: String): String = value
        .replace(Regex("\\(\\d{6}(?:-\\*)?\\)$"), "")
        .trim()

    internal fun validDebtor(value: String): Boolean {
        val normalized = debtorIdentity(value)
        return normalized.isNotBlank() && normalized == LegalEntityNames.compact(value)
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
