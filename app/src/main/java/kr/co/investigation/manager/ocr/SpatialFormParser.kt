package kr.co.investigation.manager.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * 문서 외곽 검출이 실패해도 OCR의 글자 좌표를 이용해 고정 양식의 각 행을 해석한다.
 *
 * 핵심은 ML Kit가 반환하는 text 순서를 믿지 않는 것이다. 표 문서는 열 때문에 읽기 순서가
 * 자주 뒤섞이므로, '채무자명/전화번호/물건소재지...' 같은 라벨의 boundingBox를 찾고
 * 같은 Y축 행의 텍스트를 X축 순서로 다시 조립한다.
 */
object SpatialFormParser {
    data class ParseResult(
        val parsed: InvestigationCase,
        val diagnostic: String,
        val score: Int
    )

    private data class Item(val text: String, val box: Rect) {
        val cx: Float get() = box.exactCenterX()
        val cy: Float get() = box.exactCenterY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    fun parse(text: Text, imageWidth: Int, imageHeight: Int): ParseResult {
        val items = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { Item(clean(line.text), it) } }
            .filter { it.text.isNotBlank() }

        fun labels(vararg names: String): List<Item> = items.filter { item ->
            names.any { label -> compact(item.text).contains(compact(label)) }
        }

        fun label(vararg names: String): Item? = labels(*names)
            .minWithOrNull(compareBy<Item> { it.box.top }.thenBy { it.box.left })

        fun labelNear(y: Float, vararg names: String): Item? = labels(*names)
            .minByOrNull { abs(it.cy - y) }

        fun row(anchor: Item?): String {
            if (anchor == null) return ""
            val tolerance = max(anchor.h * 1.15f, imageHeight * 0.012f)
            return items
                .filter { abs(it.cy - anchor.cy) <= tolerance }
                .sortedBy { it.box.left }
                .joinToString(" | ") { it.text }
        }

        val requestDateRow = row(label("의뢰일"))
        val managementRow = row(label("관리번호"))
        val investigatorRow = row(label("조사담당자"))

        val debtorLabel = label("채무자명", "채무자 명")
        val debtorRow = row(debtorLabel)
        val phoneLabel = debtorLabel?.let { labelNear(it.cy, "전화번호", "전화 번호") }
        val mobileLabel = debtorLabel?.let { labelNear(it.cy, "핸드폰번호", "핸드폰 번호") }
        val debtorPhones = phones(debtorRow)

        val dueRow = row(label("완료요청일"))
        val investigationLabel = label("조사구분", "조사 구분")
        val investigationRow = row(investigationLabel)
        val loanLabel = investigationLabel?.let { labelNear(it.cy, "대출종류", "대출 종류") } ?: label("대출종류", "대출 종류")
        val propertyTypeRow = row(label("물건종류", "물건 종류"))
        val propertyAddressRow = row(label("물건소재지", "물건 소재지"))

        val ownerLabel = label("물건소유자", "물건 소유자")
        val ownerRow = row(ownerLabel)
        val ownerContactLabel = ownerLabel?.let { labelNear(it.cy, "연락처", "연 락 처") }
        val ownerAddressRow = row(label("소유자주소", "소유자 주소"))

        val branchRow = row(label("농협영업점", "농협 영업점"))
        val requesterRow = row(label("조사의뢰자", "조사 의뢰자"))

        val requestDate = normalizeDate(afterLabel(requestDateRow, "의뢰일"))
        val managementNo = normalizeManagement(afterLabel(managementRow, "관리번호"))
        val investigator = personName(afterLabel(investigatorRow, "조사담당자"))

        val debtorSegment = betweenLabels(debtorRow, "채무자명", "전화번호")
            .ifBlank { betweenLabels(debtorRow, "채무자 명", "전화 번호") }
        val debtorName = personName(debtorSegment)

        val phone = when {
            phoneLabel != null -> phones(afterLabel(debtorRow, "전화번호", "전화 번호")).firstOrNull().orEmpty()
            else -> debtorPhones.getOrNull(0).orEmpty()
        }
        val mobile = when {
            mobileLabel != null -> phones(afterLabel(debtorRow, "핸드폰번호", "핸드폰 번호")).firstOrNull().orEmpty()
            else -> debtorPhones.getOrNull(1).orEmpty().ifBlank { debtorPhones.getOrNull(0).orEmpty() }
        }

        val dueDate = normalizeDate(afterLabel(dueRow, "완료요청일"))
        val investigationType = betweenLabels(investigationRow, "조사구분", "대출종류")
            .ifBlank { betweenLabels(investigationRow, "조사 구분", "대출 종류") }
            .let(::cleanValue)
        val loanType = normalizeLoanType(afterLabel(row(loanLabel), "대출종류", "대출 종류"))
        val propertyType = normalizePropertyType(afterLabel(propertyTypeRow, "물건종류", "물건 종류"))
        val propertyAddress = normalizeAddress(afterLabel(propertyAddressRow, "물건소재지", "물건 소재지"))

        val ownerIdentity = betweenLabels(ownerRow, "성명", "연락처")
            .ifBlank { betweenLabels(ownerRow, "물건소유자", "연락처") }
            .ifBlank { betweenLabels(ownerRow, "물건 소유자", "연 락 처") }
        val ownerName = personName(ownerIdentity)
        val ownerResidentNo = normalizeResident(ownerIdentity)
        val ownerPhone = if (ownerContactLabel != null) {
            phones(afterLabel(ownerRow, "연락처", "연 락 처")).firstOrNull().orEmpty()
        } else {
            phones(ownerRow).firstOrNull().orEmpty()
        }
        val ownerAddress = normalizeAddress(afterLabel(ownerAddressRow, "소유자주소", "소유자 주소"))

        val notes = collectNotes(items, imageHeight)
        val branch = cleanValue(afterLabel(branchRow, "농협영업점", "농협 영업점", "영업점"))
            .removePrefix("▷").removePrefix(">").trim()
        val requester = personName(afterLabel(requesterRow, "조사의뢰자", "조사 의뢰자"))

        val parsed = InvestigationCase(
            year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
            managementNo = managementNo,
            requestDate = requestDate,
            investigator = investigator,
            debtorName = debtorName,
            phone = phone,
            mobile = mobile,
            dueDate = dueDate,
            investigationType = investigationType,
            loanType = loanType,
            propertyType = propertyType,
            propertyAddress = propertyAddress,
            ownerName = ownerName,
            ownerResidentNo = ownerResidentNo,
            ownerPhone = ownerPhone,
            ownerAddress = ownerAddress,
            requestNotes = notes,
            branch = branch,
            requester = requester
        )

        val score = listOf(
            parsed.managementNo, parsed.requestDate, parsed.investigator, parsed.debtorName,
            parsed.phone, parsed.mobile, parsed.dueDate, parsed.investigationType, parsed.loanType,
            parsed.propertyType, parsed.propertyAddress, parsed.ownerName, parsed.ownerResidentNo,
            parsed.ownerPhone, parsed.ownerAddress, parsed.requestNotes, parsed.branch
        ).count { it.isNotBlank() }

        val diagnostic = buildString {
            append("--- 고정양식 위치기반 OCR v0.11 ---\n")
            append("관리번호 : ${parsed.managementNo}\n")
            append("의뢰일 : ${parsed.requestDate}\n")
            append("조사담당자 : ${parsed.investigator}\n")
            append("채무자명 : ${parsed.debtorName}\n")
            append("전화번호 : ${parsed.phone}\n")
            append("핸드폰번호 : ${parsed.mobile}\n")
            append("완료요청일 : ${parsed.dueDate}\n")
            append("조사구분 : ${parsed.investigationType}\n")
            append("대출종류 : ${parsed.loanType}\n")
            append("물건종류 : ${parsed.propertyType}\n")
            append("물건소재지 : ${parsed.propertyAddress}\n")
            append("물건소유자 : ${parsed.ownerName}\n")
            append("주민번호 : ${parsed.ownerResidentNo}\n")
            append("소유자연락처 : ${parsed.ownerPhone}\n")
            append("소유자주소 : ${parsed.ownerAddress}\n")
            append("기타요청사항 : ${parsed.requestNotes.replace('\n', ' ')}\n")
            append("농협영업점 : ${parsed.branch}\n")
            append("조사의뢰자 : ${parsed.requester}\n")
            append("위치기반 인식 품질 : $score/17\n")
        }

        return ParseResult(parsed, diagnostic, score)
    }

    private fun collectNotes(items: List<Item>, imageHeight: Int): String {
        val noteLabel = items.firstOrNull {
            compact(it.text).contains(compact("기타요청사항"))
        } ?: return ""
        val branch = items.filter {
            compact(it.text).contains(compact("농협영업점")) || compact(it.text).contains(compact("영업점"))
        }.filter { it.box.top > noteLabel.box.top }.minByOrNull { it.box.top }

        val top = noteLabel.box.bottom
        val bottom = branch?.box?.top ?: (imageHeight * 0.84f).toInt()
        val xSlack = 80
        val candidates = items.filter {
            it.box.top >= top - noteLabel.h / 3 &&
                it.box.bottom <= bottom &&
                it.box.left >= noteLabel.box.left - xSlack &&
                !isStructuralLabel(it.text)
        }.sortedWith(compareBy<Item> { it.box.top }.thenBy { it.box.left })

        if (candidates.isEmpty()) return ""
        val lines = mutableListOf<MutableList<Item>>()
        for (item in candidates) {
            val group = lines.lastOrNull()
            if (group == null) {
                lines += mutableListOf(item)
            } else {
                val avgY = group.map { it.cy }.average().toFloat()
                val tol = max(item.h * 0.9f, imageHeight * 0.009f)
                if (abs(avgY - item.cy) <= tol) group += item else lines += mutableListOf(item)
            }
        }
        return lines.joinToString("\n") { group ->
            group.sortedBy { it.box.left }.joinToString(" ") { it.text }
        }.trim().take(700)
    }

    private fun isStructuralLabel(text: String): Boolean {
        val c = compact(text)
        val labels = listOf(
            "기타요청사항", "농협영업점", "조사의뢰자", "전화번호", "팩스", "신청인"
        )
        return labels.any { c == compact(it) }
    }

    private fun afterLabel(text: String, vararg labels: String): String {
        var bestStart = -1
        var bestEnd = -1
        for (label in labels) {
            val m = labelRegex(label).find(text) ?: continue
            if (bestStart == -1 || m.range.first < bestStart) {
                bestStart = m.range.first
                bestEnd = m.range.last + 1
            }
        }
        return if (bestEnd >= 0) cleanValue(text.substring(bestEnd)) else cleanValue(text)
    }

    private fun betweenLabels(text: String, start: String, end: String): String {
        val s = labelRegex(start).find(text) ?: return ""
        val tail = text.substring(s.range.last + 1)
        val e = labelRegex(end).find(tail)
        return cleanValue(if (e != null) tail.substring(0, e.range.first) else tail)
    }

    private fun labelRegex(label: String): Regex {
        val chars = compact(label).map { Regex.escape(it.toString()) }.joinToString("\\s*")
        return Regex(chars, RegexOption.IGNORE_CASE)
    }

    private fun normalizeDate(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val patterns = listOf(
            Regex("(20\\d{2})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})\\s*일?"),
            Regex("(20\\d{2})[-./]?(\\d{2})(\\d{2})")
        )
        for (p in patterns) {
            val m = p.find(fixed) ?: continue
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (mo in 1..12 && d in 1..31) return "%04d-%02d-%02d".format(y, mo, d)
        }
        return ""
    }

    private fun phones(value: String): List<String> {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        return Regex("0\\d{1,2}[^0-9]{0,3}\\d{3,4}[^0-9]{0,3}\\d{4}")
            .findAll(fixed)
            .mapNotNull { normalizePhone(it.value).takeIf(String::isNotBlank) }
            .distinct().toList()
    }

    private fun normalizePhone(value: String): String {
        val d = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1').filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun normalizeManagement(value: String): String {
        val s = cleanValue(value).replace(Regex("[^가-힣A-Za-z0-9-]"), "")
        return Regex("[가-힣A-Za-z]{0,10}20\\d{4}-?\\d{3,8}").find(s)?.value.orEmpty()
    }

    private fun personName(value: String): String {
        var s = value
        listOf("성명", "채무자명", "물건소유자", "조사담당자", "조사의뢰자").forEach {
            s = s.replace(labelRegex(it), " ")
        }
        return Regex("[가-힣]{2,6}").find(s)?.value.orEmpty()
    }

    private fun normalizeResident(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val m = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*][0-9*]{0,6})?").find(fixed) ?: return ""
        val tail = m.groupValues.getOrNull(2).orEmpty()
        return if (tail.isBlank()) "${m.groupValues[1]}-" else "${m.groupValues[1]}-$tail"
    }

    private fun normalizeAddress(value: String): String {
        var s = cleanValue(value)
            .replace(Regex("^\\d{5,6}\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (!looksLikeAddress(s)) return ""
        return s.take(220)
    }

    private fun looksLikeAddress(value: String): Boolean = value.length >= 8 &&
        Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)")
            .containsMatchIn(value)

    private fun normalizePropertyType(value: String): String {
        val s = cleanValue(value)
        return listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
            .firstOrNull { s.replace(" ", "").contains(it) }.orEmpty()
    }

    private fun normalizeLoanType(value: String): String {
        val s = cleanValue(value).replace(" ", "")
        val known = listOf("주택구입자금대출", "주택담보대출", "전세자금대출", "담보대출", "신용대출")
        return known.firstOrNull { s.contains(it) }.orEmpty().ifBlank {
            cleanValue(value).takeIf { it.contains("대출") && it.length <= 50 }.orEmpty()
        }
    }

    private fun cleanValue(value: String): String = value
        .replace("|", " ")
        .replace(Regex("^[\\s:：·○Oo0>▷-]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clean(value: String): String = value.replace(Regex("[\\t ]+"), " ").trim()
    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")
}
