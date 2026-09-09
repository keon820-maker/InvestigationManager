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
                append("\n\n--- 주소 OCR 오기 보정 v0.35.25 ---\n")
                append("물건소재지 확정 : ").append(property).append('\n')
                append("소유자주소 확정 : ").append(owner).append('\n')
            },
            preprocessMessage = base.preprocessMessage + " / 주소 OCR 오기 보정 v0.35.25"
        )
    }

    internal fun normalize(value: String): String {
        if (value.isBlank()) return value
        var s = value
            .replace(Regex("\\s+"), " ")
            .trim()

        // 현재 5자리 우편번호뿐 아니라 구 양식에 남아 있는 6자리 우편번호도 제거한다.
        s = s.replace(
            Regex("^\\(?\\d{5,6}\\)?\\s+(?=(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주))"),
            ""
        )

        // 주소 첫 토큰이 축약형 '경기'로 읽힌 경우 표준 표기로 통일한다.
        s = s.replace(Regex("^경기(?=\\s)"), "경기도")

        // 행정구역 접미사 앞에서 OCR이 잘못 띄어 쓴 경우: '광주 시' -> '광주시'.
        s = s.replace(
            Regex("([가-힣]{2,10})\\s+(시|군|구|읍|면|동|리)(?=\\s|\\d|\\(|$)"),
            "$1$2"
        )

        // 실기기에서 '경기도 성남 분당구'처럼 시 접미사가 빠진 경우만 제한적으로 복원한다.
        s = s.replace(Regex("^경기도\\s+성남\\s+(?=분당구|수정구|중원구)"), "경기도 성남시 ")

        // 실사진에서 반복 확인된 건물명 오기. 주소 필드 안에서만 적용한다.
        s = s
            .replace(Regex("대\\s*이\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("디\\s*이\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("대\\s*에\\s*뜨\\s*르"), "디에뜨르")
            .replace(Regex("송\\s*피\\s*파\\s*동"), "송파동")
            .replace(Regex("송\\s*파\\s*피\\s*동"), "송파동")
            .replace(Regex("주원\\s*히\\s*우스"), "주원하우스")
            .replace(Regex("푸르지오\\s*오피트(?=\\s|\\d|$)"), "푸르지오아파트")
            // '라이프아파트'의 '라이'가 숫자 20처럼 읽힌 실기기 패턴.
            .replace(Regex("20\\s*프아파트(?=\\s|\\d|$)"), "라이프아파트")

        s = s.replace(
            Regex("디에뜨르\\s+[Aa]\\s*0?1호"),
            "디에뜨르 A 101호"
        )

        return s.replace(Regex("\\s+"), " ").trim()
    }
}
