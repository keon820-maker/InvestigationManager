package kr.co.investigation.manager.ocr

/** Explicit company/legal-person markers distinguish long names from spilled form text. */
internal object LegalEntityNames {
    private val markers = listOf(
        "농업회사법인", "어업회사법인", "유한책임회사", "사회복지법인", "영농조합법인", "영어조합법인",
        "주식회사", "유한회사", "합자회사", "합명회사", "사단법인", "재단법인", "의료법인", "학교법인", "협동조합",
        "(주)", "(유)", "(사)", "(재)", "㈜", "㈔", "㈖"
    )
    private val fieldLabels = Regex("채무자명|임차인[0-9]*(?:성명)?|전화번호|핸드폰번호|연락처|완료요청일|소유자주소")

    fun compact(value: String): String = value.replace('（', '(').replace('）', ')')
        .replace(Regex("\\s+"), "").trim()

    fun normalize(value: String): String {
        val name = compact(value)
        if (name.length !in 3..100 || fieldLabels.containsMatchIn(name)) return ""
        if (!Regex("[가-힣A-Za-z0-9㈜㈔㈖().&·ㆍ-]+").matches(name)) return ""
        val prefix = markers.firstOrNull { name.startsWith(it) }
        val suffix = markers.firstOrNull { name.endsWith(it) }
        val body = when {
            prefix != null -> name.removePrefix(prefix)
            suffix != null -> name.removeSuffix(suffix)
            else -> return ""
        }
        // A bare legal form is a clipped value, not a company name.
        if (body.isBlank() || markers.any { body == it }) return ""
        if (!Regex("[가-힣A-Za-z]").containsMatchIn(body)) return ""
        return name
    }

    fun hasMarker(value: String): Boolean {
        val name = compact(value)
        return markers.any { name.startsWith(it) || name.endsWith(it) }
    }
}
