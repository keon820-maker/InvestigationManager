package kr.co.investigation.manager.ocr

/** OCR 최종 결과의 반복적인 구조 오류를 보정한다. */
object CommonResultRepair {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val raw = base.rawText
        val c = base.parsed

        val requesterFromRaw = extractRequester(raw)
        val branchFromRaw = extractBranch(raw)
        val propertyTypeFromRaw = extractPropertyType(raw)
        val propertyAddressFromRaw = extractAddress(raw, "물건소재지")
        val ownerAddressFromRaw = extractAddress(raw, "소유자주소")

        val requester = when {
            validRequester(c.requester) -> c.requester
            validRequester(requesterFromRaw) -> requesterFromRaw
            else -> ""
        }
        val branch = when {
            validBranch(c.branch) -> c.branch
            validBranch(branchFromRaw) -> branchFromRaw
            else -> ""
        }
        val propertyType = when {
            validPropertyType(c.propertyType) -> c.propertyType.trim()
            validPropertyType(propertyTypeFromRaw) -> propertyTypeFromRaw.trim()
            else -> ""
        }

        val propertyAddress = preferPostalAddress(c.propertyAddress, propertyAddressFromRaw)
        val ownerAddress = preferPostalAddress(c.ownerAddress, ownerAddressFromRaw)
        val investigationType = normalizeInvestigationType(c.investigationType)
        val notes = cleanNotes(c.requestNotes)

        val fixed = c.copy(
            requester = requester,
            branch = branch,
            propertyType = propertyType,
            propertyAddress = propertyAddress,
            ownerAddress = ownerAddress,
            investigationType = investigationType,
            requestNotes = notes
        )

        if (fixed == c) return base
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 최종 구조 보정 v0.23 ---\n")
                append("조사구분 확정 : ").append(fixed.investigationType).append('\n')
                append("물건종류 확정 : ").append(fixed.propertyType).append('\n')
                append("물건소재지 확정 : ").append(fixed.propertyAddress).append('\n')
                append("소유자주소 확정 : ").append(fixed.ownerAddress).append('\n')
                append("기타요청사항 확정 : ").append(fixed.requestNotes.replace('\n', ' ')).append('\n')
                append("영업점 확정 : ").append(fixed.branch).append('\n')
                append("조사의뢰자 확정 : ").append(fixed.requester).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 최종 구조 보정 v0.23"
        )
    }

    private fun extractRequester(raw: String): String {
        val regex = Regex("조\\s*사\\s*의\\s*뢰\\s*자\\s*[:：]?\\s*([가-힣]{2,5})", RegexOption.IGNORE_CASE)
        return regex.findAll(raw)
            .map { it.groupValues[1].trim() }
            .firstOrNull(::validRequester)
            .orEmpty()
    }

    private fun extractBranch(raw: String): String {
        val regex = Regex("(?:농\\s*협\\s*)?영\\s*업\\s*점\\s*[:：]?\\s*([가-힣0-9]{2,24}지점)", RegexOption.IGNORE_CASE)
        return regex.findAll(raw)
            .map { it.groupValues[1].replace(" ", "").trim() }
            .firstOrNull(::validBranch)
            .orEmpty()
    }

    private fun extractPropertyType(raw: String): String {
        val labelRegex = Regex("물\\s*건\\s*종\\s*류\\s*[:：|]?\\s*([^\\n|]{1,30})")
        val labeled = labelRegex.findAll(raw)
            .map { cleanSimpleValue(it.groupValues[1]) }
            .firstOrNull(::validPropertyType)
        if (!labeled.isNullOrBlank()) return labeled

        val known = listOf(
            "아파트", "오피스텔", "연립주택", "다세대주택", "다가구주택", "단독주택",
            "주택", "빌라", "상가", "근린생활시설", "토지", "공장", "사무실"
        )
        return raw.lineSequence()
            .map(::cleanSimpleValue)
            .firstOrNull { line -> known.any { line.equals(it, ignoreCase = true) } }
            .orEmpty()
    }

    private fun extractAddress(raw: String, label: String): String {
        val spaced = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        val regex = Regex("$spaced\\s*[:：|]?\\s*([^\\n|]+)")
        val candidates = regex.findAll(raw)
            .map { sanitizeAddress(cleanSimpleValue(it.groupValues[1])) }
            .filter(::looksLikeAddress)
            .toList()

        return candidates.firstOrNull { Regex("^\\d{5,6}\\s+").containsMatchIn(it) }
            ?: candidates.firstOrNull().orEmpty()
    }

    private fun preferPostalAddress(current: String, rawCandidate: String): String {
        val a = sanitizeAddress(current)
        val b = sanitizeAddress(rawCandidate)
        if (!looksLikeAddress(a)) return b.takeIf(::looksLikeAddress).orEmpty()
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(a)) return a
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(b) && looksLikeAddress(b)) return b
        return if (addressScore(b) > addressScore(a)) b else a
    }

    private fun sanitizeAddress(value: String): String {
        var s = value.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
        val region = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기(?:도)?|강원(?:도)?|충북|충남|전북|전남|경북|경남|제주(?:도)?)")
        val rm = region.find(s) ?: return s
        val before = s.substring(0, rm.range.first)
        val postal = Regex("\\d{5,6}").findAll(before).lastOrNull()
        val start = postal?.range?.first ?: rm.range.first
        s = s.substring(start).trim()

        val lastLocation = Regex("(?:\\d+호|\\d+동|\\d+층|\\d+번지|\\d+(?:-\\d+)?)").findAll(s).lastOrNull()
        if (lastLocation != null && lastLocation.range.last + 1 < s.length) {
            val tail = s.substring(lastLocation.range.last + 1).trim()
            val latinNoise = tail.count { it in 'A'..'Z' || it in 'a'..'z' } >= 3
            if (tail.length >= 4 && latinNoise) s = s.substring(0, lastLocation.range.last + 1).trim()
        }
        return s
    }

    private fun addressScore(value: String): Int {
        if (!looksLikeAddress(value)) return 0
        var score = 1
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(value)) score += 3
        if (Regex("[가-힣]+(?:시|군|구)").containsMatchIn(value)) score += 2
        if (Regex("[가-힣0-9]+(?:로|길|동|읍|면|리)").containsMatchIn(value)) score += 2
        if (Regex("\\d+(?:동|호|층|번지|-\\d+)").containsMatchIn(value)) score += 2
        return score
    }

    private fun normalizeInvestigationType(value: String): String {
        val s = value
            .replace(Regex("\\s+"), "")
            .replace("조시사", "조사")
            .replace("조시", "조사")
            .replace("열람조사사", "열람조사")
            .replace("임대차조사사", "임대차조사")
            .replace("담보조사사", "담보조사")
            .replace("＋", "+")
            .trim('+', ' ', '|')

        val parts = mutableListOf<String>()
        if (s.contains("담보")) parts += "담보조사"
        if (s.contains("열람")) parts += "열람조사"
        if (s.contains("임대차")) parts += "임대차조사"
        if (parts.isNotEmpty()) return parts.distinct().joinToString("+")
        return s
    }

    private fun cleanNotes(value: String): String {
        var s = value.trim()
        s = s.replace(Regex("^[\\s.·ㆍ,;:：\\-]*기타\\s*요청\\s*사항\\s*[:：]?\\s*"), "")
        s = s.replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
        s = s.replace(Regex("월\\s*임차료\\s*[:：]?"), "월임차료:")
        s = s.replace(Regex("([.!?])(?=[가-힣])"), "$1 ")
        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
        s = s.lines().joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }
        return s.trim()
    }

    private fun cleanSimpleValue(value: String): String = value
        .replace(Regex("^[\\s:：|>▷.·ㆍ,;\\-]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun validPropertyType(value: String): Boolean {
        val s = cleanSimpleValue(value)
        if (s.length !in 2..20) return false
        val compact = s.replace(" ", "")
        val bad = listOf("물건종류", "물건소재지", "대출종류", "조사구분", "물건소유자", "전화번호", "핸드폰번호", "완료요청일")
        if (bad.any { compact.contains(it) }) return false
        return Regex("[가-힣A-Za-z0-9]+(?:\\s*[가-힣A-Za-z0-9]+)*").matches(s)
    }

    private fun validRequester(value: String): Boolean {
        val s = value.trim()
        if (!Regex("[가-힣]{2,5}").matches(s)) return false
        val bad = setOf("전화번호", "조사의뢰자", "농협영업점", "신청인", "연락처", "팩스", "채무자명", "완료요청일", "전화번", "영업점", "기타요청")
        return s !in bad && !s.contains("전화") && !s.contains("의뢰") && !s.contains("영업점") && !s.contains("신청")
    }

    private fun validBranch(value: String): Boolean {
        val s = value.replace(" ", "")
        return s.length in 4..30 && s.endsWith("지점") && !s.contains("조사의뢰자") && !s.contains("전화번호") && !s.contains("신청인")
    }

    private fun looksLikeAddress(value: String): Boolean = value.length >= 8 && Regex(
        "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)"
    ).containsMatchIn(value)
}
