package kr.co.investigation.manager.ocr

/**
 * OCR 최종 결과의 반복적인 구조 오류를 보정한다.
 *
 * 고정 셀 OCR 결과와 하단 동적 OCR 결과를 모두 통과한 뒤 실행된다.
 * 이미지 내용을 임의로 추측해서 만들지 않고, rawText 안에 실제로 OCR된 후보가 있을 때만
 * 잘린 우편번호/조사의뢰자/물건종류 등을 복구한다.
 */
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

        // 저장 데이터에는 문서에 찍힌 앞자리 숫자(우편번호/관리용 코드)를 보존한다.
        // 지도 검색 시에는 GeocoderService가 그 앞자리 숫자만 제외해서 사용한다.
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
                append("\n\n--- 최종 구조 보정 v0.18 ---\n")
                append("조사구분 확정 : ").append(fixed.investigationType).append('\n')
                append("물건종류 확정 : ").append(fixed.propertyType).append('\n')
                append("물건소재지 확정 : ").append(fixed.propertyAddress).append('\n')
                append("소유자주소 확정 : ").append(fixed.ownerAddress).append('\n')
                append("기타요청사항 확정 : ").append(fixed.requestNotes.replace('\n', ' ')).append('\n')
                append("영업점 확정 : ").append(fixed.branch).append('\n')
                append("조사의뢰자 확정 : ").append(fixed.requester).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 최종 구조 보정 v0.18"
        )
    }

    private fun extractRequester(raw: String): String {
        val regex = Regex(
            "조\\s*사\\s*의\\s*뢰\\s*자\\s*[:：]?\\s*([가-힣]{2,5})",
            setOf(RegexOption.IGNORE_CASE)
        )
        return regex.findAll(raw)
            .map { it.groupValues[1].trim() }
            .firstOrNull(::validRequester)
            .orEmpty()
    }

    private fun extractBranch(raw: String): String {
        val regex = Regex(
            "(?:농\\s*협\\s*)?영\\s*업\\s*점\\s*[:：]?\\s*([가-힣0-9]{2,24}지점)",
            setOf(RegexOption.IGNORE_CASE)
        )
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
            .map { cleanSimpleValue(it.groupValues[1]) }
            .filter(::looksLikeAddress)
            .toList()

        return candidates.firstOrNull { Regex("^\\d{5,6}\\s+").containsMatchIn(it) }
            ?: candidates.firstOrNull().orEmpty()
    }

    private fun preferPostalAddress(current: String, rawCandidate: String): String {
        if (!looksLikeAddress(current)) return rawCandidate.takeIf(::looksLikeAddress).orEmpty()
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(current)) return current
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(rawCandidate) && looksLikeAddress(rawCandidate)) {
            return rawCandidate
        }
        return current
    }

    private fun normalizeInvestigationType(value: String): String {
        var s = value
            .replace(Regex("\\s+"), "")
            .replace("조시사", "조사")
            .replace("조시", "조사")
            .replace("열람조사사", "열람조사")
            .replace("임대차조사사", "임대차조사")
            .replace("＋", "+")
            .trim('+', ' ', '|')

        val hasView = s.contains("열람")
        val hasLease = s.contains("임대차")
        if (hasView && hasLease) return "열람조사+임대차조사"
        if (hasView && (s.contains("조사") || s.length <= 8)) return "열람조사"
        if (hasLease && (s.contains("조사") || s.length <= 10)) return "임대차조사"
        return s
    }

    private fun cleanNotes(value: String): String {
        var s = value.trim()
        s = s.replace(
            Regex("^[\\s.·ㆍ,;:：\\-]*기타\\s*요청\\s*사항\\s*[:：]?\\s*"),
            ""
        )

        // 전용 요청사항 영역 뒤에 다른 표 셀(보증금/월임차료 등)이 OCR 순서 때문에 붙는 경우 제거한다.
        s = s.replace(
            Regex("\\s*(?:보증금|증금)\\s*[:：]?\\s*[^\\n]{0,20}?(?:월\\s*임차료|임차료)\\s*[:：]?\\s*[^\\n]{0,20}$"),
            ""
        )
        s = s.replace(Regex("\\s*(?:보증금|증금)\\s*[:：]?\\s*[0Oo이Il|]?\\s*$"), "")
        s = s.replace(Regex("\\s*(?:월\\s*임차료|임차료)\\s*[:：]?\\s*[0Oo이Il|]?\\s*$"), "")

        s = s.replace(Regex("([.!?])(?=[가-힣])"), "$1 ")
        s = s.replace(Regex("부탁드리며(?=채무자)"), "부탁드리며 ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun cleanSimpleValue(value: String): String = value
        .replace(Regex("^[\\s:：|>▷.·ㆍ,;\\-]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun validPropertyType(value: String): Boolean {
        val s = cleanSimpleValue(value)
        if (s.length !in 2..20) return false
        val compact = s.replace(" ", "")
        val bad = listOf(
            "물건종류", "물건소재지", "대출종류", "조사구분", "물건소유자",
            "전화번호", "핸드폰번호", "완료요청일"
        )
        if (bad.any { compact.contains(it) }) return false
        return Regex("[가-힣A-Za-z0-9]+(?:\\s*[가-힣A-Za-z0-9]+)*").matches(s)
    }

    private fun validRequester(value: String): Boolean {
        val s = value.trim()
        if (!Regex("[가-힣]{2,5}").matches(s)) return false
        val bad = setOf(
            "전화번호", "조사의뢰자", "농협영업점", "신청인", "연락처", "팩스",
            "채무자명", "완료요청일", "전화번", "영업점", "기타요청"
        )
        return s !in bad && !s.contains("전화") && !s.contains("의뢰") &&
            !s.contains("영업점") && !s.contains("신청")
    }

    private fun validBranch(value: String): Boolean {
        val s = value.replace(" ", "")
        return s.length in 4..30 && s.endsWith("지점") &&
            !s.contains("조사의뢰자") && !s.contains("전화번호") && !s.contains("신청인")
    }

    private fun looksLikeAddress(value: String): Boolean = value.length >= 8 && Regex(
        "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)"
    ).containsMatchIn(value)
}
