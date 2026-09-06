package kr.co.investigation.manager.ocr

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/**
 * v0.35.7: 고정 셀 좌표가 한 행 이상 밀린 경우에만 전체 페이지의 실제 라벨 좌표로 최종 복구한다.
 * 정상 문서는 추가 OCR을 하지 않는다.
 */
object SpatialRescueRepairV357 {
    private data class Item(val text: String, val box: Rect) {
        val cy: Float get() = box.exactCenterY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private data class FooterContacts(val phone: String = "", val fax: String = "")
    private data class DebtorContacts(val phone: String = "", val mobile: String = "")

    suspend fun repair(
        normalized: DocumentNormalizer.Result,
        base: OcrService.OcrResult
    ): OcrService.OcrResult {
        if (!needsRescue(base.parsed)) return base
        if (normalized.bitmap.width < 1500 || normalized.bitmap.height < 2200) return base

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val full = recognize(client, normalized.bitmap)
            val spatial = SpatialFormParser.parse(full, normalized.bitmap.width, normalized.bitmap.height)
            val fallback = FixedTemplateOcr.parseFallback(full.text)
            var candidate = preferValid(spatial.parsed, fallback)

            val debtorContacts = extractDebtorContacts(full, normalized.bitmap.height)
            val propertyAddress = extractAddressRow(full, normalized.bitmap.height, "물건소재지")
            val ownerAddress = extractAddressRow(full, normalized.bitmap.height, "소유자주소")
            val branch = extractBranchRow(full, normalized.bitmap.height)
            val footerContacts = extractFooterContacts(full, normalized.bitmap.height)

            candidate = candidate.copy(
                phone = debtorContacts.phone.ifBlank { candidate.phone },
                mobile = debtorContacts.mobile.ifBlank { candidate.mobile },
                propertyAddress = propertyAddress.ifBlank { candidate.propertyAddress },
                ownerAddress = ownerAddress.ifBlank { candidate.ownerAddress },
                branch = branch.ifBlank { candidate.branch },
                branchPhone = footerContacts.phone,
                branchFax = footerContacts.fax
            )

            val rescued = mergeRescue(base.parsed, candidate)
            val beforeScore = semanticScore(base.parsed)
            val afterScore = semanticScore(rescued)
            if (rescued == base.parsed || (afterScore < beforeScore && !hasStructuralCorruption(base.parsed))) {
                base
            } else {
                base.copy(
                    parsed = rescued,
                    rawText = buildString {
                        append(base.rawText)
                        append("\n\n--- 라벨 좌표 최종 복구 v0.35.7 ---\n")
                        append(spatial.diagnostic)
                        append("복구 전 점수 : ").append(beforeScore).append('\n')
                        append("복구 후 점수 : ").append(afterScore).append('\n')
                        append("채무자 : ").append(rescued.debtorName).append('\n')
                        append("전화/핸드폰 : ").append(rescued.phone).append(" / ").append(rescued.mobile).append('\n')
                        append("조사구분 : ").append(rescued.investigationType).append('\n')
                        append("대출종류 : ").append(rescued.loanType).append('\n')
                        append("물건종류 : ").append(rescued.propertyType).append('\n')
                        append("물건소재지 : ").append(rescued.propertyAddress).append('\n')
                        append("소유자 : ").append(rescued.ownerName).append(" / ").append(rescued.ownerResidentNo).append('\n')
                        append("소유자연락처 : ").append(rescued.ownerPhone).append('\n')
                        append("소유자주소 : ").append(rescued.ownerAddress).append('\n')
                        append("영업점 : ").append(rescued.branch).append('\n')
                        append("영업점 전화/Fax : ").append(rescued.branchPhone).append(" / ").append(rescued.branchFax).append('\n')
                    },
                    preprocessMessage = base.preprocessMessage + " / 라벨 좌표 최종 복구 v0.35.7"
                )
            }
        } finally {
            client.close()
        }
    }

    internal fun needsRescue(c: InvestigationCase): Boolean =
        hasStructuralCorruption(c) ||
            (c.branchPhone.isNotBlank() && c.branchPhone == c.branchFax) ||
            semanticScore(c) < 10

    internal fun mergeRescue(current: InvestigationCase, candidate: InvestigationCase): InvestigationCase {
        fun choose(cur: String, fresh: String, valid: (String) -> Boolean): String =
            fresh.takeIf(valid) ?: cur.takeIf(valid).orEmpty()

        val management = choose(current.managementNo, candidate.managementNo, ::validManagement)
        val requestDate = choose(current.requestDate, candidate.requestDate, ::validDate)
        val candidatePhone = candidate.phone.takeIf(::validPhone).orEmpty()
        val currentPhone = current.phone.takeIf(::validPhone).orEmpty()
        val phone = when {
            candidatePhone.isNotBlank() -> candidatePhone
            managementLeak(currentPhone, management) -> ""
            else -> currentPhone
        }

        return current.copy(
            year = requestDate.take(4).toIntOrNull() ?: current.year,
            managementNo = management,
            requestDate = requestDate,
            debtorName = choose(current.debtorName, candidate.debtorName, ::validDebtor),
            phone = phone,
            mobile = choose(current.mobile, candidate.mobile, ::validPhone),
            dueDate = choose(current.dueDate, candidate.dueDate, ::validDate),
            investigationType = choose(current.investigationType, candidate.investigationType, ::validInvestigation),
            loanType = choose(current.loanType, candidate.loanType, ::validLoan),
            propertyType = choose(current.propertyType, candidate.propertyType, ::validPropertyType),
            propertyAddress = choose(current.propertyAddress, candidate.propertyAddress, ::validAddress),
            ownerName = choose(current.ownerName, candidate.ownerName, ::validName),
            ownerResidentNo = choose(current.ownerResidentNo, candidate.ownerResidentNo, ::validResident),
            ownerPhone = choose(current.ownerPhone, candidate.ownerPhone, ::validPhone),
            ownerAddress = choose(current.ownerAddress, candidate.ownerAddress, ::validAddress),
            requestNotes = if (notesScore(candidate.requestNotes) > notesScore(current.requestNotes)) candidate.requestNotes else current.requestNotes,
            branch = choose(current.branch, candidate.branch, ::validBranchLoose),
            branchPhone = choose(current.branchPhone, candidate.branchPhone, ::validPhone),
            branchFax = choose(current.branchFax, candidate.branchFax, ::validPhone),
            requester = choose(current.requester, candidate.requester, ::validName)
        )
    }

    private fun preferValid(a: InvestigationCase, b: InvestigationCase): InvestigationCase = a.copy(
        managementNo = a.managementNo.takeIf(::validManagement) ?: b.managementNo.takeIf(::validManagement).orEmpty(),
        requestDate = a.requestDate.takeIf(::validDate) ?: b.requestDate.takeIf(::validDate).orEmpty(),
        debtorName = a.debtorName.takeIf(::validDebtor) ?: b.debtorName.takeIf(::validDebtor).orEmpty(),
        phone = a.phone.takeIf(::validPhone) ?: b.phone.takeIf(::validPhone).orEmpty(),
        mobile = a.mobile.takeIf(::validPhone) ?: b.mobile.takeIf(::validPhone).orEmpty(),
        dueDate = a.dueDate.takeIf(::validDate) ?: b.dueDate.takeIf(::validDate).orEmpty(),
        investigationType = a.investigationType.takeIf(::validInvestigation) ?: b.investigationType.takeIf(::validInvestigation).orEmpty(),
        loanType = a.loanType.takeIf(::validLoan) ?: b.loanType.takeIf(::validLoan).orEmpty(),
        propertyType = a.propertyType.takeIf(::validPropertyType) ?: b.propertyType.takeIf(::validPropertyType).orEmpty(),
        propertyAddress = a.propertyAddress.takeIf(::validAddress) ?: b.propertyAddress.takeIf(::validAddress).orEmpty(),
        ownerName = a.ownerName.takeIf(::validName) ?: b.ownerName.takeIf(::validName).orEmpty(),
        ownerResidentNo = a.ownerResidentNo.takeIf(::validResident) ?: b.ownerResidentNo.takeIf(::validResident).orEmpty(),
        ownerPhone = a.ownerPhone.takeIf(::validPhone) ?: b.ownerPhone.takeIf(::validPhone).orEmpty(),
        ownerAddress = a.ownerAddress.takeIf(::validAddress) ?: b.ownerAddress.takeIf(::validAddress).orEmpty(),
        branch = a.branch.takeIf(::validBranchLoose) ?: b.branch.takeIf(::validBranchLoose).orEmpty(),
        requester = a.requester.takeIf(::validName) ?: b.requester.takeIf(::validName).orEmpty()
    )

    private fun extractDebtorContacts(text: Text, imageHeight: Int): DebtorContacts {
        val items = items(text)
        val debtor = items.firstOrNull { compact(it.text).contains("채무자명") } ?: return DebtorContacts()
        val tolerance = max(debtor.h * 1.2f, imageHeight * 0.012f)
        val row = items.filter { abs(it.cy - debtor.cy) <= tolerance }.sortedBy { it.box.left }
        val phoneLabel = row.firstOrNull { compact(it.text).contains("전화번호") }
        val mobileLabel = row.firstOrNull { compact(it.text).contains("핸드폰번호") }
        val phoneItems = row.mapNotNull { item -> firstPhone(item.text).takeIf { it.isNotBlank() }?.let { item to it } }

        val phone = if (phoneLabel != null && mobileLabel != null) {
            phoneItems.firstOrNull { (item, _) -> item.box.left > phoneLabel.box.right - 10 && item.box.right < mobileLabel.box.left + 10 }?.second.orEmpty()
        } else ""
        val mobile = if (mobileLabel != null) {
            phoneItems.firstOrNull { (item, _) -> item.box.left > mobileLabel.box.right - 10 }?.second.orEmpty()
        } else phoneItems.lastOrNull()?.second.orEmpty()
        return DebtorContacts(phone, mobile)
    }

    private fun extractAddressRow(text: Text, imageHeight: Int, label: String): String {
        val row = labeledRow(text, imageHeight, label) ?: return ""
        var s = removeLabel(row, label)
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        val region = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기(?:도)?|강원(?:도)?|충북|충남|전북|전남|경북|경남|제주(?:도)?)")
        val m = region.find(s) ?: return ""
        val prefix = Regex("\\d{5,6}").findAll(s.substring(0, m.range.first)).lastOrNull()?.value
        s = s.substring(m.range.first).trim()
        val result = listOfNotNull(prefix, s.takeIf { it.isNotBlank() }).joinToString(" ")
        return result.takeIf(::validAddress).orEmpty()
    }

    private fun extractBranchRow(text: Text, imageHeight: Int): String {
        val row = labeledRow(text, imageHeight, "농협영업점", minYRatio = 0.70f)
            ?: labeledRow(text, imageHeight, "영업점", minYRatio = 0.70f)
            ?: return ""
        return removeLabel(removeLabel(row, "농협영업점"), "영업점")
            .replace(Regex("^[▷> :：]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .takeIf(::validBranchLoose)
            .orEmpty()
    }

    private fun extractFooterContacts(text: Text, imageHeight: Int): FooterContacts {
        val row = labeledRow(text, imageHeight, "전화번호", minYRatio = 0.70f)
            ?: labeledRow(text, imageHeight, "팩스", minYRatio = 0.70f)
            ?: return FooterContacts()
        val normalized = normalizeDigits(row)
        val faxIndex = compactWithPositions(normalized, "팩스")
        val phoneLabelIndex = compactWithPositions(normalized, "전화번호")
        val all = phonePattern.findAll(normalized).map { normalizePhone(it.value) }.filter { it.isNotBlank() }.toList()

        var phone = ""
        var fax = ""
        if (phoneLabelIndex >= 0 && faxIndex > phoneLabelIndex) {
            val beforeFax = normalized.substring(phoneLabelIndex, faxIndex)
            phone = phonePattern.find(beforeFax)?.value?.let(::normalizePhone).orEmpty()
            val afterFax = normalized.substring(faxIndex)
            fax = phonePattern.find(afterFax)?.value?.let(::normalizePhone).orEmpty()
        }
        if (phone.isBlank() && all.size >= 2) phone = all[0]
        if (fax.isBlank() && all.size >= 2) fax = all[1]
        if (fax.isBlank() && all.size == 1 && faxIndex >= 0) fax = all[0]
        return FooterContacts(phone, fax)
    }

    private fun labeledRow(text: Text, imageHeight: Int, label: String, minYRatio: Float = 0f): String? {
        val its = items(text)
        val target = compact(label)
        val anchor = its.filter { it.cy >= imageHeight * minYRatio && compact(it.text).contains(target) }
            .minByOrNull { it.box.top } ?: return null
        val tolerance = max(anchor.h * 1.2f, imageHeight * 0.012f)
        return its.filter { abs(it.cy - anchor.cy) <= tolerance }
            .sortedBy { it.box.left }
            .joinToString(" | ") { it.text }
    }

    private fun items(text: Text): List<Item> = text.textBlocks.flatMap { it.lines }
        .mapNotNull { line -> line.boundingBox?.let { Item(line.text.trim(), it) } }
        .filter { it.text.isNotBlank() }

    private fun removeLabel(value: String, label: String): String {
        val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        return value.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
    }

    private fun compactWithPositions(value: String, label: String): Int {
        val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
        return Regex(pattern, RegexOption.IGNORE_CASE).find(value)?.range?.first ?: -1
    }

    private fun firstPhone(value: String): String = phonePattern.find(normalizeDigits(value))?.value?.let(::normalizePhone).orEmpty()

    private fun hasStructuralCorruption(c: InvestigationCase): Boolean {
        val badTokens = listOf("완료요청일", "전화번호", "핸드폰번호", "비고", "조사구분", "대출종류", "물건종류", "자조사")
        val sensitive = listOf(c.debtorName, c.ownerName, c.investigationType, c.loanType, c.propertyType)
        return sensitive.any { value -> badTokens.any { value.replace(" ", "").contains(it) } } ||
            (c.investigationType.isNotBlank() && !validInvestigation(c.investigationType))
    }

    private fun semanticScore(c: InvestigationCase): Int = listOf(
        validManagement(c.managementNo), validDate(c.requestDate), validDebtor(c.debtorName),
        validPhone(c.phone), validPhone(c.mobile), validDate(c.dueDate), validInvestigation(c.investigationType),
        validLoan(c.loanType), validPropertyType(c.propertyType), validAddress(c.propertyAddress),
        validName(c.ownerName), validResident(c.ownerResidentNo), validPhone(c.ownerPhone),
        validAddress(c.ownerAddress), validBranchLoose(c.branch), validName(c.requester)
    ).count { it }

    private fun validManagement(v: String) = Regex("[가-힣A-Za-z]{0,10}20\\d{4,6}-?\\d{3,8}").containsMatchIn(v)
    private fun validDate(v: String) = Regex("20\\d{2}-\\d{2}-\\d{2}").matches(v)
    private fun validPhone(v: String): Boolean {
        val d = v.filter(Char::isDigit)
        return when {
            d.length == 12 -> d.startsWith("050")
            d.length == 11 -> d.startsWith("01") || d.substring(0, 3) in threeDigitPrefixes
            d.length == 10 -> d.startsWith("02") || d.substring(0, 3) in threeDigitPrefixes
            d.length == 9 -> d.startsWith("02")
            else -> false
        }
    }
    private fun validDebtor(v: String) = Regex("[가-힣]{2,6}(?:\\(\\d{6}(?:-\\*)?\\))?").matches(v) && validName(v.substringBefore('('))
    private fun validName(v: String): Boolean {
        if (!Regex("[가-힣]{2,6}").matches(v)) return false
        val bad = listOf("완료", "요청", "전화", "번호", "조사", "비고", "소유", "연락", "주소", "영업")
        return bad.none { v.contains(it) }
    }
    private fun validResident(v: String) = Regex("\\d{6}-[1-4*]?[0-9*]{0,6}").matches(v)
    private fun validInvestigation(v: String) = v.contains("조사") && !v.contains("비고") && !Regex("20\\d{2}").containsMatchIn(v) && v.length <= 60
    private fun validLoan(v: String): Boolean {
        val s = v.replace(" ", "")
        return s.length in 3..40 && listOf("대출", "자금", "담보", "전세", "경락").any { s.contains(it) }
    }
    private fun validPropertyType(v: String) = listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택").any { v.contains(it) }
    private fun validAddress(v: String) = v.length >= 8 && Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)").containsMatchIn(v)
    private fun validBranchLoose(v: String): Boolean {
        val s = v.replace(Regex("\\s+"), "").trim()
        if (s.length !in 2..40) return false
        val bad = listOf("전화번호", "팩스", "조사의뢰자", "신청인", "완료요청일")
        return bad.none { s.contains(it) } && s.any { it in '가'..'힣' }
    }

    private fun notesScore(v: String): Int {
        if (v.isBlank()) return 0
        var score = (v.length / 30).coerceAtMost(6)
        if (v.contains("보증금")) score += 3
        if (v.contains("월임차료")) score += 3
        if (v.contains("방문") || v.contains("요청") || v.contains("부탁")) score += 2
        return score
    }

    private fun managementLeak(phone: String, management: String): Boolean {
        val p = phone.filter(Char::isDigit)
        val m = management.filter(Char::isDigit)
        return p.length >= 8 && m.contains(p)
    }

    private fun normalizeDigits(value: String): String = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
    private val phonePattern = Regex("\\(?0\\d{1,3}\\)?[- .]?\\d{3,4}[- .]?\\d{4}")
    private val threeDigitPrefixes = setOf("031", "032", "033", "041", "042", "043", "044", "051", "052", "053", "054", "055", "061", "062", "063", "064", "070", "080")

    private fun normalizePhone(value: String): String {
        val d = value.filter(Char::isDigit)
        return when {
            d.length == 12 && d.startsWith("050") -> "${d.substring(0, 4)}-${d.substring(4, 8)}-${d.substring(8)}"
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 11 && d.substring(0, 3) in threeDigitPrefixes -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 && d.substring(0, 3) in threeDigitPrefixes -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")

    private suspend fun recognize(client: com.google.mlkit.vision.text.TextRecognizer, bitmap: android.graphics.Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }
}
