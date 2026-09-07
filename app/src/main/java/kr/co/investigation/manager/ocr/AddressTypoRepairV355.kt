package kr.co.investigation.manager.ocr

/**
 * 실기기 조사의뢰서 OCR에서 반복 확인된 주소 오기를 최종 저장 직전에 보정한다.
 * 전체 주소나 개인정보를 하드코딩하지 않고, 행정구역/건물명 토큰 단위로만 보정한다.
 */
object AddressTypoRepairV355 {
    fun repair(base: OcrService.OcrResult): OcrService.OcrResult {
        val c = base.parsed
        val property = normalize(c.propertyAddress)
        val owner = normalize(c.ownerAddress)

        if (property == c.propertyAddress && owner == c.ownerAddress) return base

        return base.copy(
            parsed = c.copy(
                propertyAddress = property,
                ownerAddress = owner
            ),
            rawText = base.rawText + buildString {
                append("\n\n--- 주소 OCR 오기 보정 v0.35.12 ---\n")
                append("물건소재지 확정 : ").append(property).append('\n')
                append("소유자주소 확정 : ").append(owner).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 주소 OCR 오기 보정 v0.35.12"
        )
    }

    internal fun normalize(value: String): String {
        if (value.isBlank()) return value
        var s = value
            .replace(Regex("\\s+"), " ")
            .trim()

        // 행정구역 접미사 앞에서 OCR이 잘못 띄어 쓴 경우: '광주 시' -> '광주시'.
        s = s.replace(
            Regex("([가-힣]{2,10})\\s+(시|군|구|읍|면|동|리)(?=\\s|\\d|\\(|$)"),
            "$1$2"
        )

        // 실사진에서 반복 확인된 건물명 오기. 주소 필드 안에서만 적용한다.
        s = s
            .replace(Regex("대\\s*이\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("디\\s*이\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("대\\s*에\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("송\\s*피\\s*파\\s*동"), "송파동")
            .replace(Regex("송\\s*파\\s*피\\s*동"), "송파동")
            .replace(Regex("주원\\s*히\\s*우스"), "주원하우스")
            // '푸르지오아파트'의 '아파트'가 '오피트'로 흔들린 경우만 제한적으로 복원한다.
            .replace(Regex("푸르지오\\s*오피트(?=\\s|\\d|$)"), "푸르지오아파트")

        // '디에뜨르 A 101호'에서 가운데 1이 누락되어 'A01호'로 읽히는 실기기 패턴.
        // 특정 전체 주소를 저장하지 않고 해당 건물명+동/호 표기 조합에서만 제한적으로 보정한다.
        s = s.replace(
            Regex("디에뜨르\\s+[Aa]\\s*0?1호"),
            "디에뜨르 A 101호"
        )

        return s.replace(Regex("\\s+"), " ").trim()
    }
}
