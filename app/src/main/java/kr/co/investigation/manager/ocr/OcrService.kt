package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    private data class Node(val text: String, val box: Rect) {
        val cy: Int get() = box.centerY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private data class Row(val nodes: List<Node>) {
        val text: String = nodes.sortedBy { it.box.left }.joinToString(" ") { it.text }
        val top: Int = nodes.minOf { it.box.top }
        val bottom: Int = nodes.maxOf { it.box.bottom }
    }

    private val labels = listOf(
        "관리번호", "조사담당자", "채무자명", "채무자 명", "전화번호", "핸드폰번호", "핸드폰 번호",
        "완료요청일", "조사구분", "조사 구분", "대출종류", "대출 종류", "물건종류", "물건 종류",
        "물건소재지", "물건 소재지", "물건소유자", "물건 소유자", "성명", "주민번호", "연락처",
        "소유자주소", "소유자 주소", "기타요청사항", "기타 요청사항", "농협영업점", "영업점",
        "조사의뢰자", "조사 의뢰자", "의뢰일", "비고"
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val whole = recognizeText(client, normalized.bitmap)
            val parsed = parseRowsAndEntities(whole)
            val rowCount = buildRows(whole).size
            return OcrResult(
                rawText = whole.text,
                parsed = parsed,
                normalized = normalized.documentDetected,
                preprocessMessage = normalized.message + " / 행 재구성+문맥 OCR ${rowCount}행"
            )
        } finally {
            client.close()
        }
    }

    suspend fun recognize(context: Context, uri: Uri): String = recognizeCase(context, uri).rawText

    private suspend fun recognizeText(client: TextRecognizer, bitmap: Bitmap): Text = suspendCancellableCoroutine { c ->
        val image = InputImage.fromBitmap(bitmap, 0)
        client.process(image)
            .addOnSuccessListener { result -> if (c.isActive) c.resume(result) }
            .addOnFailureListener { error -> if (c.isActive) c.resumeWithException(error) }
    }

    private fun buildRows(result: Text): List<Row> {
        val nodes = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { Node(clean(line.text), Rect(it)) }
        }.filter { it.text.isNotBlank() }.sortedBy { it.cy }

        if (nodes.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Node>>()
        for (n in nodes) {
            val best = groups.lastOrNull()
            if (best == null) {
                groups += mutableListOf(n)
                continue
            }
            val meanCy = best.map { it.cy }.average()
            val meanH = best.map { it.h }.average().coerceAtLeast(12.0)
            val overlaps = best.any { other ->
                val top = max(other.box.top, n.box.top)
                val bottom = minOf(other.box.bottom, n.box.bottom)
                val inter = (bottom - top).coerceAtLeast(0)
                inter.toDouble() / minOf(other.h, n.h).coerceAtLeast(1) >= 0.28
            }
            if (overlaps || abs(n.cy - meanCy) <= max(meanH * 0.72, n.h * 0.72)) {
                best += n
            } else {
                groups += mutableListOf(n)
            }
        }
        return groups.map { Row(it) }.sortedBy { it.top }
    }

    private fun parseRowsAndEntities(result: Text): InvestigationCase {
        val raw = result.text
        val rows = buildRows(result)
        val rowTexts = rows.map { clean(it.text) }

        fun compact(s: String) = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")
        fun labelRegex(label: String): Regex {
            val body = label.filterNot(Char::isWhitespace)
                .map { Regex.escape(it.toString()) }
                .joinToString("\\s*")
            return Regex(body, RegexOption.IGNORE_CASE)
        }
        fun containsLabel(text: String, alias: String): Boolean = compact(text).contains(compact(alias))
        fun rowFor(vararg aliases: String): String = rowTexts.firstOrNull { row -> aliases.any { containsLabel(row, it) } }.orEmpty()

        fun segmentAfter(row: String, vararg aliases: String): String {
            if (row.isBlank()) return ""
            var start = -1
            for (a in aliases) {
                val m = labelRegex(a).find(row) ?: continue
                start = max(start, m.range.last + 1)
            }
            if (start < 0 || start >= row.length) return ""
            val tail = row.substring(start).trim(' ', ':', '：', '|', '-', '·')
            if (tail.isBlank()) return ""
            var end = tail.length
            for (lab in labels) {
                val m = labelRegex(lab).find(tail) ?: continue
                if (m.range.first > 0) end = minOf(end, m.range.first)
            }
            return tail.substring(0, end).trim(' ', ':', '：', '|', '-', '·')
        }

        fun valueFor(vararg aliases: String): String {
            val row = rowFor(*aliases)
            return segmentAfter(row, *aliases)
        }

        fun lineRegex(pattern: Regex): String = pattern.find(raw)?.groupValues?.getOrNull(1).orEmpty().trim()

        fun dateFrom(v: String): String {
            val fixed = v.uppercase().replace('O', '0')
            Regex("(20\\d{2})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})\\s*일?").find(fixed)?.let { m ->
                val y = m.groupValues[1].toIntOrNull() ?: return@let
                val mo = m.groupValues[2].toIntOrNull() ?: return@let
                val d = m.groupValues[3].toIntOrNull() ?: return@let
                if (mo in 1..12 && d in 1..31) return "%04d-%02d-%02d".format(y, mo, d)
            }
            Regex("(20\\d{2})[-./]?(\\d{2})(\\d{2})").find(fixed)?.let { m ->
                val y = m.groupValues[1].toIntOrNull() ?: return@let
                val mo = m.groupValues[2].toIntOrNull() ?: return@let
                val d = m.groupValues[3].toIntOrNull() ?: return@let
                if (mo in 1..12 && d in 1..31) return "%04d-%02d-%02d".format(y, mo, d)
            }
            return ""
        }

        fun allDates(): List<String> {
            val out = mutableListOf<String>()
            Regex("20\\d{2}[^\\n]{0,12}?\\d{1,2}[^\\n]{0,8}?\\d{1,2}").findAll(raw).forEach { dateFrom(it.value).takeIf(String::isNotBlank)?.let(out::add) }
            Regex("20\\d{2}[-./]?\\d{4}").findAll(raw).forEach { dateFrom(it.value).takeIf(String::isNotBlank)?.let(out::add) }
            return out.distinct()
        }

        fun phoneFrom(v: String): String {
            val fixed = v.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
            val m = Regex("0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}|01\\d[- ]?\\d{3,4}[- ]?\\d{4}").find(fixed)
            val digits = m?.value?.filter(Char::isDigit)
                ?: fixed.filter(Char::isDigit).takeIf { it.length in 9..11 }
                ?: return ""
            return when {
                digits.length == 11 -> "${digits.substring(0,3)}-${digits.substring(3,7)}-${digits.substring(7)}"
                digits.length == 10 && digits.startsWith("02") -> "02-${digits.substring(2,6)}-${digits.substring(6)}"
                digits.length == 10 -> "${digits.substring(0,3)}-${digits.substring(3,6)}-${digits.substring(6)}"
                digits.length == 9 && digits.startsWith("02") -> "02-${digits.substring(2,5)}-${digits.substring(5)}"
                else -> ""
            }
        }

        fun personName(v: String): String {
            val cleaned = clean(v).substringBefore('(').substringBefore("주민").trim()
            return Regex("[가-힣]{2,6}").findAll(cleaned).lastOrNull()?.value.orEmpty()
        }

        fun residentFrom(v: String): String {
            val m = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*]?[0-9*]{0,6})").find(v) ?: return ""
            val tail = m.groupValues[2]
            return if (tail.isBlank()) "${m.groupValues[1]}-" else "${m.groupValues[1]}-$tail"
        }

        fun addressFrom(v: String): String {
            var s = clean(v).replace(Regex("\\s+"), " ").trim()
            s = s.replace(Regex("^\\d{5,6}\\s+"), "")
            val hint = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|특별시|광역시|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+동|번지)")
            return if (s.length >= 8 && hint.containsMatchIn(s)) s else ""
        }

        fun short(v: String, maxLen: Int): String {
            val s = clean(v).replace(Regex("^[○Oo0 :：|·-]+"), "").trim()
            return if (s.length in 1..maxLen) s else ""
        }

        val requestDate = dateFrom(valueFor("의뢰일"))
            .ifBlank { dateFrom(lineRegex(Regex("의뢰\\s*일\\s*[:：]?\\s*([^\\n]+)"))) }
            .ifBlank { allDates().firstOrNull().orEmpty() }

        val dates = allDates()
        val dueDate = dateFrom(valueFor("완료요청일"))
            .ifBlank { dates.firstOrNull { it.isNotBlank() && it != requestDate }.orEmpty() }

        val management = short(
            valueFor("관리번호").ifBlank { lineRegex(Regex("[○Oo0]?\\s*관리\\s*번\\s*호\\s*[:：]?\\s*([^\\n]+)")) },
            40
        ).replace(" ", "")

        val investigator = personName(
            valueFor("조사담당자").ifBlank { lineRegex(Regex("[○Oo0]?\\s*조사담당자\\s*[:：]?\\s*([^\\n]+)")) }
        )

        val personPairs = Regex("([가-힣]{2,6})\\s*\\(\\s*(\\d{6})[^)]*\\)")
            .findAll(raw).map { it.groupValues[1] to it.groupValues[2] }.toList()

        val debtorRow = rowFor("채무자명", "채무자 명")
        var debtorName = personName(segmentAfter(debtorRow, "채무자명", "채무자 명"))
        if (debtorName.isBlank()) debtorName = personPairs.firstOrNull()?.first.orEmpty()

        val targetRow = if (debtorRow.isNotBlank()) debtorRow else rowTexts.firstOrNull { it.contains("010") && it.contains("910") }.orEmpty()
        val phone = phoneFrom(segmentAfter(targetRow, "전화번호"))
            .ifBlank {
                val phonesAfterTarget = collectPhonesAfterMarker(raw, "대상자")
                phonesAfterTarget.getOrNull(0).orEmpty()
            }
        val mobile = phoneFrom(segmentAfter(targetRow, "핸드폰번호", "핸드폰 번호"))
            .ifBlank {
                val phonesAfterTarget = collectPhonesAfterMarker(raw, "대상자")
                phonesAfterTarget.getOrNull(1).orEmpty().ifBlank { phonesAfterTarget.getOrNull(0).orEmpty() }
            }

        val investigationType = short(valueFor("조사구분", "조사 구분"), 60)
            .ifBlank {
                rowTexts.firstOrNull { it.contains("임대차조사") || it.contains("현장조사") }
                    ?.let { Regex("[가-힣]+조사(?:\\([^)]*\\))?").find(it)?.value }.orEmpty()
            }

        val loanType = short(valueFor("대출종류", "대출 종류"), 60)
            .ifBlank {
                Regex("[가-힣]{2,20}(?:담보)?대출").findAll(raw)
                    .map { it.value }.firstOrNull { it != "대출종류" }.orEmpty()
            }

        val propertyType = short(valueFor("물건종류", "물건 종류"), 50)
            .ifBlank {
                listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
                    .firstOrNull { raw.contains(it) }.orEmpty()
            }

        var propertyAddress = addressFrom(valueFor("물건소재지", "물건 소재지"))
        if (propertyAddress.isBlank()) {
            propertyAddress = rowTexts.map(::addressFrom).filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()
        }

        val ownerRow = rowFor("물건소유자", "물건 소유자", "성명")
        var ownerName = personName(segmentAfter(ownerRow, "성명"))
        if (ownerName.isBlank()) ownerName = personPairs.getOrNull(1)?.first.orEmpty()
        if (ownerName.isBlank() && personPairs.size == 1) ownerName = personPairs.first().first

        var ownerResident = residentFrom(ownerRow)
        if (ownerResident.isBlank()) {
            val pair = personPairs.getOrNull(1) ?: personPairs.firstOrNull()
            if (pair != null) ownerResident = "${pair.second}-"
        }

        val allPhones = Regex("01\\d[- ]?\\d{3,4}[- ]?\\d{4}|0\\d{1,2}[- ]?\\d{3,4}[- ]?\\d{4}")
            .findAll(raw).mapNotNull { phoneFrom(it.value).takeIf(String::isNotBlank) }.toList()
        val ownerPhone = phoneFrom(segmentAfter(ownerRow, "연락처"))
            .ifBlank { allPhones.drop(3).firstOrNull().orEmpty() }
            .ifBlank { mobile }

        val ownerAddress = addressFrom(valueFor("소유자주소", "소유자 주소"))

        val notes = extractNotes(rows, raw)
        val branch = short(valueFor("농협영업점", "영업점"), 80)
            .ifBlank { rowTexts.firstOrNull { it.contains("지점") }?.substringAfter(':').orEmpty().ifBlank { rowTexts.firstOrNull { it.contains("지점") }.orEmpty() } }

        val requester = personName(valueFor("조사의뢰자", "조사 의뢰자"))
            .ifBlank {
                lineRegex(Regex("조사\\s*의뢰자\\s*[:：]?\\s*([가-힣]{2,6})"))
            }

        return InvestigationCase(
            year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
            managementNo = management,
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
            ownerResidentNo = ownerResident,
            ownerPhone = ownerPhone,
            ownerAddress = ownerAddress,
            requestNotes = notes,
            branch = branch,
            requester = requester
        )
    }

    private fun collectPhonesAfterMarker(raw: String, marker: String): List<String> {
        val start = raw.indexOf(marker).takeIf { it >= 0 } ?: 0
        val tail = raw.substring(start)
        return Regex("01\\d[- ]?\\d{3,4}[- ]?\\d{4}")
            .findAll(tail)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length == 11 }
            .map { "${it.substring(0,3)}-${it.substring(3,7)}-${it.substring(7)}" }
            .toList()
    }

    private fun extractNotes(rows: List<Row>, raw: String): String {
        val labelIndex = rows.indexOfFirst { compactStatic(it.text).contains("기타요청사항") }
        if (labelIndex >= 0) {
            val same = rows[labelIndex].text
            val after = same.substringAfter("기타요청사항", "").trim(' ', ':', '：')
            val following = rows.drop(labelIndex + 1).take(4).map { it.text }
                .takeWhile { !compactStatic(it).contains("농협영업점") && !compactStatic(it).contains("조사의뢰자") }
            val joined = (listOf(after) + following).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (joined.length >= 15) return joined.take(500)
        }
        val candidates = raw.lines().map(::clean).filter { it.length >= 20 }
        return candidates.maxByOrNull { line ->
            listOf("예정", "14일", "보고서", "금일", "진행").count { line.contains(it) } * 100 + line.length
        }?.take(500).orEmpty()
    }

    fun parse(text: String): InvestigationCase {
        val date = Regex("(20\\d{2})[^0-9]{0,5}(\\d{1,2})[^0-9]{0,5}(\\d{1,2})").find(text)?.let {
            "%04d-%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }.orEmpty()
        return InvestigationCase(year = date.take(4).toIntOrNull() ?: LocalDate.now().year, requestDate = date)
    }

    private fun compactStatic(s: String): String = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")

    private fun clean(s: String): String = s
        .replace('｜', '|')
        .replace(Regex("[\\t ]+"), " ")
        .trim()
}
