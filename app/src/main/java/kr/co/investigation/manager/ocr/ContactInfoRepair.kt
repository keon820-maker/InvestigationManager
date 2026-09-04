package kr.co.investigation.manager.ocr

/**
 * OCR 문자열 안의 상단 조사담당자 연락처를 보조 복구한다.
 *
 * 영업점 연락처는 v0.23부터 절대 전체 OCR 문자열에서 추측하지 않는다.
 * 대상자/소유자 전화번호가 하단 연락처로 잘못 들어가는 것을 막기 위해
 * StructuredFieldOcrRepair가 실제 하단 영역을 다시 OCR해서 채운다.
 */
object ContactInfoRepair {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val raw = base.rawText
        val parsed = base.parsed
        val header = headerSection(raw)

        val investigatorPhone = parsed.investigatorPhone.ifBlank {
            findLabeledPhone(header, Regex("(?i)T\\s*e\\s*l\\s*[)）:]?"))
        }
        val investigatorFax = parsed.investigatorFax.ifBlank {
            findLabeledPhone(header, Regex("(?i)F\\s*a\\s*x\\s*[)）:]?"))
        }

        val fixed = parsed.copy(
            investigatorPhone = investigatorPhone,
            investigatorFax = investigatorFax
        )

        if (fixed == parsed) return base
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 담당자 연락처 문자열 보조 v0.23 ---\n")
                append("조사담당자 Tel : ").append(fixed.investigatorPhone).append('\n')
                append("조사담당자 Fax : ").append(fixed.investigatorFax).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 담당자 연락처 문자열 보조 v0.23"
        )
    }

    private fun headerSection(raw: String): String {
        val start = indexOfAny(raw, listOf("조사담당자", "관리번호")).takeIf { it >= 0 } ?: 0
        val end = (start + 650).coerceAtMost(raw.length)
        return raw.substring(start, end)
    }

    private fun indexOfAny(raw: String, keys: List<String>): Int = keys
        .map { raw.indexOf(it, ignoreCase = true) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

    private fun findLabeledPhone(text: String, label: Regex): String {
        val labelMatch = label.find(text) ?: return ""
        val start = labelMatch.range.last + 1
        val window = text.substring(start, (start + 100).coerceAtMost(text.length))
        return phonePattern.find(window)?.value?.let(::normalizePhone).orEmpty()
    }

    private val phonePattern = Regex("\\(?0\\d{1,2}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")

    private fun normalizePhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when (digits.length) {
            9 -> "${digits.take(2)}-${digits.substring(2, 5)}-${digits.takeLast(4)}"
            10 -> if (digits.startsWith("02")) {
                "02-${digits.substring(2, 6)}-${digits.takeLast(4)}"
            } else {
                "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            }
            11 -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.takeLast(4)}"
            else -> ""
        }
    }
}
