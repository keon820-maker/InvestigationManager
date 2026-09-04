package kr.co.investigation.manager.ocr

/**
 * 조사의뢰서 상단 조사담당자 연락처와 하단 영업점 연락처를 DB 필드로 복구한다.
 * 원본 이미지는 수정하지 않고 OCR 문자열만 후처리한다.
 */
object ContactInfoRepair {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val raw = base.rawText
        val parsed = base.parsed

        val header = headerSection(raw)
        val footer = footerSection(raw, parsed.branch, parsed.requester)

        val investigatorPhone = parsed.investigatorPhone.ifBlank {
            findLabeledPhone(header, Regex("(?i)T\\s*e\\s*l\\s*[)）:]?"))
        }
        val investigatorFax = parsed.investigatorFax.ifBlank {
            findLabeledPhone(header, Regex("(?i)F\\s*a\\s*x\\s*[)）:]?"))
        }
        val branchPhone = parsed.branchPhone.ifBlank {
            findLabeledPhone(footer, Regex("전\\s*화\\s*번\\s*호\\s*[:：]?"))
        }
        val branchFax = parsed.branchFax.ifBlank {
            findLabeledPhone(footer, Regex("팩\\s*스\\s*[:：]?"))
        }

        val fixed = parsed.copy(
            investigatorPhone = investigatorPhone,
            investigatorFax = investigatorFax,
            branchPhone = branchPhone,
            branchFax = branchFax
        )

        if (fixed == parsed) return base
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 담당자/영업점 연락처 재검증 v0.21 ---\n")
                append("조사담당자 Tel : ").append(fixed.investigatorPhone).append('\n')
                append("조사담당자 Fax : ").append(fixed.investigatorFax).append('\n')
                append("영업점 전화 : ").append(fixed.branchPhone).append('\n')
                append("영업점 Fax : ").append(fixed.branchFax).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 담당자·영업점 연락처 필드 복구 v0.21"
        )
    }

    private fun headerSection(raw: String): String {
        val start = indexOfAny(raw, listOf("조사담당자", "관리번호")).takeIf { it >= 0 } ?: 0
        val end = (start + 650).coerceAtMost(raw.length)
        return raw.substring(start, end)
    }

    private fun footerSection(raw: String, branch: String, requester: String): String {
        val keys = buildList {
            add("농협영업점")
            add("조사의뢰자")
            if (branch.isNotBlank()) add(branch)
            if (requester.isNotBlank()) add(requester)
        }
        val start = indexOfAny(raw, keys).takeIf { it >= 0 } ?: (raw.length * 2 / 3)
        return raw.substring(start.coerceIn(0, raw.length))
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
            else -> value.replace("(", "").replace(")", "").replace(" ", "").trim()
        }
    }
}
