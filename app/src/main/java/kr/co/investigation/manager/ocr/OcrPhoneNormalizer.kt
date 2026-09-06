package kr.co.investigation.manager.ocr

/**
 * 조사의뢰서에 등장하는 국내 전화/Fax 번호를 OCR 문자열에서 정규화한다.
 * 02, 3자리 지역번호, 휴대전화, 050x 서비스번호를 지원하며
 * 존재하지 않는 038 같은 3자리 지역번호는 오인식 후보로 보고 버린다.
 */
internal object OcrPhoneNormalizer {
    val pattern = Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")

    private val threeDigitPrefixes = setOf(
        "031", "032", "033", "041", "042", "043", "044",
        "051", "052", "053", "054", "055", "061", "062", "063", "064",
        "070", "080"
    )

    fun normalize(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 12 && d.startsWith("050") ->
                "${d.substring(0, 4)}-${d.substring(4, 8)}-${d.substring(8)}"
            d.length == 11 && d.startsWith("01") ->
                "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 11 && d.substring(0, 3) in threeDigitPrefixes ->
                "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") ->
                "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 && d.substring(0, 3) in threeDigitPrefixes ->
                "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") ->
                "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }
}
