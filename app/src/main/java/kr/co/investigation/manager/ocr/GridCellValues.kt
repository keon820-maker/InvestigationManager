package kr.co.investigation.manager.ocr

import java.time.LocalDate

/** Parsers receive only one verified cell. Never search other roles to fill a blank. */
internal object GridCellValues {
    data class Choice(val value: String, val review: Boolean)

    fun choose(first: String, second: String, normalize: (String) -> String): Choice {
        val a = normalize(first); val b = normalize(second)
        return when {
            a == b || a.replace(Regex("\\s+"), "") == b.replace(Regex("\\s+"), "") -> Choice(a, false)
            a.isBlank() -> Choice(b, true)
            b.isBlank() -> Choice(a, true)
            else -> Choice("", true) // Conflicting identities/numbers require review, not a guess.
        }
    }

    fun text(value: String) = value.replace(Regex("[\\t ]+"), " ").lines()
        .map(String::trim).filter(String::isNotBlank).joinToString("\n")
    fun singleLine(value: String) = text(value).replace('\n', ' ').trim()
    fun name(value: String): String = singleLine(value).replace(" ", "")
        .takeIf { Regex("[가-힣]{2,6}").matches(it) && TenantResultSanitizer.validTenantName(it) }.orEmpty()

    fun date(value: String): String {
        val clean = value.uppercase().replace('O', '0').replace('I', '1')
        val match = Regex("(20\\d{2})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})").find(clean) ?: return ""
        return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()).toString() }.getOrDefault("")
    }

    fun management(value: String): String = Regex("[가-힣A-Za-z]{0,8}20\\d{4}-\\d{3,8}")
        .find(value.replace(Regex("\\s+"), ""))?.value.orEmpty()

    fun address(value: String): String = singleLine(value).replace(Regex("^\\d{5,6}\\s+"), "")

    fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        // Whitespace can wrap a number inside this cell. Brackets and adjacent numbers are boundaries.
        return Regex("(?<!\\d)0\\d{1,3}[\\s.\\-)]*\\d{3,4}[\\s.\\-]*\\d{4}(?!\\d)")
            .findAll(fixed).map { OcrPhoneNormalizer.normalize(it.value) }
            .filter(String::isNotBlank).distinct().toList()
    }

    fun phone(value: String): String = phones(value).singleOrNull().orEmpty()
}
