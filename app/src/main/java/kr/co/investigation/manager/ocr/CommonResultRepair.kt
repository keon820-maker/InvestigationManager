package kr.co.investigation.manager.ocr

/**
 * OCR 최종 결과의 반복적인 구조 오류를 보정한다.
 *
 * 고정 셀 OCR 결과와 하단 동적 OCR 결과를 모두 통과한 뒤 실행된다.
 * 이미지 내용을 임의로 추측해서 만들지 않고, rawText 안에 실제로 OCR된 후보가 있을 때만
 * 잘린 우편번호/조사의뢰자 등을 복구한다.
 */
object CommonResultRepair {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val raw = base.rawText
        val c = base.parsed

        val requesterFromRaw = extractRequester(raw)
        val branchFromRaw = extractBranch(raw)
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
            else -> c.branch.takeIf(::validBranch).orEmpty()
        }

        // 이전 normalizeAddress가 앞의 5~6자리 우편번호를 의도적으로 제거하고 있었다.
        // raw 진단값에 우편번호가 포함되어 있을 때만 원래 문자열을 복구한다.
        val propertyAddress = preferPostalAddress(c.propertyAddress, propertyAddressFromRaw)
        val ownerAddress = preferPostalAddress(c.ownerAddress, ownerAddressFromRaw)
        val notes = cleanNotes(c.requestNotes)

        val fixed = c.copy(
            requester = requester,
            branch = branch,
            propertyAddress = propertyAddress,
            ownerAddress = ownerAddress,
            requestNotes = notes
        )

        if (fixed == c) return base
        return base.copy(
            parsed = fixed,
            rawText = base.rawText + buildString {
                append("\n\n--- 최종 구조 보정 v0.14 ---\n")
                append("물건소재지 확정 : ").append(fixed.propertyAddress).append('\n')
                append("소유자주소 확정 : ").append(fixed.ownerAddress).append('\n')
                append("영업점 확정 : ").append(fixed.branch).append('\n')
                append("조사의뢰자 확정 : ").append(fixed.requester).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 최종 구조 보정 v0.14"
        )
    }

    private fun extractRequester(raw: String): String {
        // 같은 OCR line 안에 "농협영업점 ... ▷조사의뢰자 : 권현지"처럼 합쳐져도 찾는다.
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

    private fun extractAddress(raw: String, label: String): String {
        val spaced = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        val regex = Regex("$spaced\\s*[:：]\\s*([^\\n]+)")
        return regex.findAll(raw)
            .map { it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { looksLikeAddress(it) }
            .orEmpty()
    }

    private fun preferPostalAddress(current: String, rawCandidate: String): String {
        if (!looksLikeAddress(current)) return rawCandidate.takeIf(::looksLikeAddress).orEmpty()
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(current)) return current
        if (Regex("^\\d{5,6}\\s+").containsMatchIn(rawCandidate) && looksLikeAddress(rawCandidate)) {
            return rawCandidate
        }
        return current
    }

    private fun cleanNotes(value: String): String {
        var s = value.trim()
        s = s.replace(
            Regex("^[\\s.·ㆍ,;:：\\-]*기타\\s*요청\\s*사항\\s*[:：]?\\s*"),
            ""
        )
        s = s.replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
        s = s.replace(Regex("보증금\\s*[:：]\\s*0[IiLlOo]\\b"), "보증금:0")
        s = s.replace(Regex("(^|\\s)임차료\\s*[:：]"), "$1월임차료:")
        s = s.replace(Regex("월임차료\\s*[:：]\\s*[oO]\\b"), "월임차료:0")
        return s.trim()
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
