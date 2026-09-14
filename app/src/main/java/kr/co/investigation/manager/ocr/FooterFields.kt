package kr.co.investigation.manager.ocr

/** Only accepts values following labels in an already isolated footer region. */
internal object FooterFields {
    data class Values(
        val branch: String = "",
        val requester: String = "",
        val phone: String = "",
        val fax: String = "",
        val review: Set<String> = emptySet()
    ) {
        val complete: Boolean get() = listOf(branch, requester, phone, fax).all(String::isNotBlank)
    }

    private fun spaced(value: String) = value.map { Regex.escape(it.toString()) }.joinToString("\\s*")
    private val labels = Regex(
        "${spaced("농협영업점")}|${spaced("영업점")}|${spaced("조사의뢰자")}|" +
            "${spaced("전화번호")}|${spaced("전화")}|(?<![A-Za-z])TEL(?![A-Za-z])|" +
            "${spaced("팩스")}|(?<![A-Za-z])FAX(?![A-Za-z])|" +
            "${spaced("신청인")}|${spaced("조사담당자")}",
        RegexOption.IGNORE_CASE
    )

    fun parse(footerText: String): Values {
        val matches = labels.findAll(footerText).toList()
        val anchor = matches.indexOfFirst { role(it.value) in setOf("branch", "requester") }
        if (anchor < 0) return Values() // An unlabelled phone is never assigned by position.
        val candidates = mutableMapOf<String, MutableList<String>>()
        for (index in anchor until matches.size) {
            val match = matches[index]
            val key = role(match.value)
            if (key == "stop") break
            val end = matches.getOrNull(index + 1)?.range?.first ?: footerText.length
            val raw = footerText.substring(match.range.last + 1, end).trim(' ', '\n', '\r', '\t', ':', '：', '|', '▷', '▶', '>', '·')
            val value = when (key) {
                "branch" -> branch(raw)
                "requester" -> requester(raw)
                "phone", "fax" -> GridCellValues.phone(raw)
                else -> ""
            }
            if (value.isNotBlank()) candidates.getOrPut(key) { mutableListOf() }.add(value)
        }
        val review = mutableSetOf<String>()
        fun choose(key: String): String {
            val values = candidates[key].orEmpty().distinct()
            if (values.size > 1) review += key
            return values.singleOrNull().orEmpty()
        }
        val branch = choose("branch")
        val requester = choose("requester")
        val phone = choose("phone")
        val fax = choose("fax")
        return Values(branch, requester, phone, fax, review)
    }

    /** Compare independent readings without replacing one conflicting name/number with another. */
    fun reconcile(first: Values, second: Values): Values {
        val review = (first.review + second.review).toMutableSet()
        fun choose(key: String, a: String, b: String): String = when {
            key in review -> ""
            a.isBlank() -> b
            b.isBlank() || a == b -> a
            else -> { review += key; "" }
        }
        val branch = choose("branch", first.branch, second.branch)
        val requester = choose("requester", first.requester, second.requester)
        val phone = choose("phone", first.phone, second.phone)
        val fax = choose("fax", first.fax, second.fax)
        return Values(branch, requester, phone, fax, review)
    }

    fun branch(value: String): String {
        val compact = value.replace(Regex("\\s+"), "")
        // Institution types, not a dictionary of private branch names. Finance centers are valid.
        return compact.takeIf {
            it.length in 4..40 &&
                Regex("[가-힣A-Za-z0-9]+(?:지점|센터|출장소|지부|본점|영업부|<출>)").matches(it) &&
                !Regex("조사의뢰|전화번호|조사담당|신청인").containsMatchIn(it)
        }.orEmpty()
    }

    fun requester(value: String): String {
        val compact = value.replace(Regex("\\s+"), "")
        return compact.takeIf {
            Regex("[가-힣]{2,5}").matches(it) &&
                it !in setOf("전화번호", "조사의뢰자", "농협영업점", "신청인", "연락처", "팩스", "영업점", "조사담당자", "농협은행")
        }.orEmpty()
    }

    private fun role(label: String): String = when (label.replace(Regex("\\s+"), "").uppercase()) {
        "농협영업점", "영업점" -> "branch"
        "조사의뢰자" -> "requester"
        "전화번호", "전화", "TEL" -> "phone"
        "팩스", "FAX" -> "fax"
        else -> "stop"
    }
}
