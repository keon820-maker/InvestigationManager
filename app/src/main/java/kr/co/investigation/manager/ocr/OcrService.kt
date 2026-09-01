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
import kotlin.math.min

object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    private data class CellText(val text: String, val box: Rect)
    private data class LineNode(val text: String, val box: Rect) {
        val cy: Int get() = box.centerY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private val labels = listOf(
        "관리번호", "조사담당자", "채무자명", "채무자 명", "전화번호", "핸드폰번호",
        "완료요청일", "조사구분", "대출종류", "물건종류", "물건소재지", "물건 소재지",
        "물건소유자", "물건 소유자", "성명", "주민번호", "연락처", "소유자주소", "소유자 주소",
        "기타요청사항", "농협영업점", "영업점", "조사의뢰자", "의뢰일"
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val whole = recognizeText(client, normalized.bitmap)
            val rects = TableCellDetector.detect(normalized.bitmap)
            val cells = mutableListOf<CellText>()

            // 표 셀은 별도로 확대/대비 보정해서 다시 OCR한다.
            for (rect in rects.take(70)) {
                val crop = runCatching { TableCellDetector.prepareCrop(normalized.bitmap, rect) }.getOrNull() ?: continue
                val text = runCatching { recognizeText(client, crop).text }.getOrDefault("")
                crop.recycle()
                val cleaned = clean(text.replace('\n', ' '))
                if (cleaned.isNotBlank()) cells += CellText(cleaned, rect)
            }

            val parsed = parseHybrid(whole, cells, normalized.bitmap.width, normalized.bitmap.height)
            return OcrResult(
                rawText = whole.text,
                parsed = parsed,
                normalized = normalized.documentDetected,
                preprocessMessage = normalized.message + " / 표 셀 ${cells.size}개 재인식"
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

    private fun parseHybrid(whole: Text, cells: List<CellText>, width: Int, height: Int): InvestigationCase {
        val raw = whole.text
        val lineNodes = whole.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { LineNode(clean(line.text), Rect(it)) }
        }.filter { it.text.isNotBlank() }

        fun compact(s: String): String = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")

        fun levenshtein(a: String, b: String): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val cur = IntArray(b.length + 1)
                cur[0] = i + 1
                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
                }
                prev = cur
            }
            return prev[b.length]
        }

        fun labelScore(text: String, label: String): Int {
            val t = compact(text)
            val l = compact(label)
            if (t.isBlank() || l.isBlank()) return 0
            if (t == l) return 100
            if (t.startsWith(l)) return 96
            if (t.contains(l)) return 92
            if (l.contains(t) && t.length >= 3) return 78
            val d = levenshtein(t.take(l.length + 3), l)
            val allowed = max(1, l.length / 3)
            return if (d <= allowed) 74 - d * 5 else 0
        }

        fun isLabel(s: String): Boolean = labels.any { labelScore(s, it) >= 72 }

        fun findCell(vararg aliases: String, minY: Double = 0.0, maxY: Double = 1.0): CellText? {
            val y0 = (height * minY).toInt()
            val y1 = (height * maxY).toInt()
            return cells.asSequence()
                .filter { it.box.centerY() in y0..y1 }
                .map { c -> c to aliases.maxOf { labelScore(c.text, it) } }
                .filter { it.second >= 68 }
                .sortedWith(compareByDescending<Pair<CellText, Int>> { it.second }.thenBy { it.first.box.top }.thenBy { it.first.box.left })
                .firstOrNull()?.first
        }

        fun inlineAfter(text: String, vararg aliases: String): String {
            for (a in aliases) {
                val chars = a.filterNot(Char::isWhitespace).map { Regex.escape(it.toString()) }.joinToString("\\s*")
                val m = Regex(chars, RegexOption.IGNORE_CASE).find(text) ?: continue
                val rest = text.substring(m.range.last + 1).trim(' ', ':', '：', '|', '-', '·')
                if (rest.isNotBlank()) return rest
            }
            return ""
        }

        fun overlapY(a: Rect, b: Rect): Double {
            val top = max(a.top, b.top)
            val bottom = min(a.bottom, b.bottom)
            if (bottom <= top) return 0.0
            return (bottom - top).toDouble() / min(a.height(), b.height()).coerceAtLeast(1)
        }

        fun rightValue(anchor: CellText): String {
            val candidates = cells.filter { c ->
                c !== anchor &&
                    c.box.left >= anchor.box.right - 10 &&
                    c.box.left - anchor.box.right <= width * 0.48 &&
                    overlapY(anchor.box, c.box) >= 0.55 &&
                    !isLabel(c.text)
            }.sortedBy { it.box.left }
            return candidates.firstOrNull()?.text.orEmpty()
        }

        fun valueFromCells(vararg aliases: String, minY: Double = 0.0, maxY: Double = 1.0): String {
            val anchor = findCell(*aliases, minY = minY, maxY = maxY) ?: return ""
            val inline = inlineAfter(anchor.text, *aliases)
            return inline.ifBlank { rightValue(anchor) }.trim()
        }

        fun valueFromLines(vararg aliases: String): String {
            val normAliases = aliases.map { compact(it) }
            val anchor = lineNodes.asSequence().map { n ->
                val c = compact(n.text)
                n to normAliases.maxOf { a -> when {
                    c == a -> 100
                    c.startsWith(a) -> 92
                    c.contains(a) -> 84
                    else -> 0
                } }
            }.filter { it.second > 0 }.sortedByDescending { it.second }.firstOrNull()?.first ?: return ""

            val inline = inlineAfter(anchor.text, *aliases)
            if (inline.isNotBlank()) return inline
            val tol = max(anchor.h, 28) * 0.9
            return lineNodes.filter { n ->
                n !== anchor && abs(n.cy - anchor.cy) <= tol && n.box.left >= anchor.box.right - 6
            }.sortedBy { it.box.left }.firstOrNull()?.text.orEmpty()
        }

        fun regexLine(pattern: Regex): String = pattern.find(raw)?.groupValues?.getOrNull(1).orEmpty().trim()

        fun dateFrom(v: String): String {
            val fixed = v.uppercase().replace('O', '0')
            val m = Regex("(20\\d{2})[^0-9]{0,6}(\\d{1,2})[^0-9]{0,6}(\\d{1,2})").find(fixed) ?: return ""
            val y = m.groupValues[1].toIntOrNull() ?: return ""
            val mo = m.groupValues[2].toIntOrNull() ?: return ""
            val d = m.groupValues[3].toIntOrNull() ?: return ""
            if (mo !in 1..12 || d !in 1..31) return ""
            return "%04d-%02d-%02d".format(y, mo, d)
        }

        fun phoneFrom(v: String): String {
            val fixed = v.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
            val groups = Regex("0\\d{1,2}[- ]?\\d{3,4}[- ]?\\d{4}|01\\d[- ]?\\d{3,4}[- ]?\\d{4}")
                .findAll(fixed).map { it.value.filter(Char::isDigit) }.toList()
            val digits = groups.firstOrNull { it.length in 9..11 }
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

        fun cleanValue(v: String): String = clean(v)
            .replace(Regex("^[○Oo0]+"), "")
            .replace(Regex("^[ :：|·-]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun personName(v: String): String {
            val s = cleanValue(v).substringBefore('(').substringBefore("주민").trim()
            val m = Regex("[가-힣]{2,6}").findAll(s).lastOrNull()?.value.orEmpty()
            return m
        }

        fun address(v: String): String {
            val s = cleanValue(v).replace(Regex("\\s+"), " ")
            if (s.length !in 6..180) return ""
            val hint = Regex("(특별시|광역시|특별자치|[가-힣]+도|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+동|[가-힣]+로|[가-힣]+길|번지)")
            return if (hint.containsMatchIn(s)) s else ""
        }

        fun resident(v: String): String = Regex("\\d{6}\\s*-?\\s*[1-4*]?[0-9*]{0,6}")
            .find(v)?.value?.replace(" ", "").orEmpty()

        fun short(v: String, maxLen: Int): String {
            val s = cleanValue(v)
            return if (s.length in 1..maxLen && !isLabel(s)) s else ""
        }

        val requestDate = dateFrom(regexLine(Regex("의뢰일\\s*[:：]?\\s*([^\\n]+)")))
            .ifBlank { dateFrom(valueFromLines("의뢰일")) }
        val dueDate = dateFrom(valueFromCells("완료요청일", minY = 0.18, maxY = 0.42))
            .ifBlank { dateFrom(valueFromLines("완료요청일")) }

        val management = cleanValue(
            regexLine(Regex("[○Oo0]?\\s*관리\\s*번\\s*호\\s*[:：]?\\s*([^\\n]+)"))
                .ifBlank { valueFromLines("관리번호") }
        ).replace(" ", "").take(40)

        val investigator = short(
            cleanValue(regexLine(Regex("[○Oo0]?\\s*조사담당자\\s*[:：]?\\s*([^\\n]+)")))
                .ifBlank { valueFromLines("조사담당자") }, 30
        )

        val debtorRaw = valueFromCells("채무자명", "채무자 명", minY = 0.18, maxY = 0.42)
        val debtorName = personName(debtorRaw).ifBlank { personName(valueFromLines("채무자명", "채무자 명")) }
        val phone = phoneFrom(valueFromCells("전화번호", minY = 0.18, maxY = 0.42))
            .ifBlank { phoneFrom(valueFromLines("전화번호")) }
        val mobile = phoneFrom(valueFromCells("핸드폰번호", minY = 0.18, maxY = 0.42))
            .ifBlank { phoneFrom(valueFromLines("핸드폰번호")) }

        val investigationType = short(valueFromCells("조사구분", minY = 0.30, maxY = 0.56), 50)
            .ifBlank { short(valueFromLines("조사구분"), 50) }
        val loanType = short(valueFromCells("대출종류", minY = 0.30, maxY = 0.56), 50)
            .ifBlank { short(valueFromLines("대출종류"), 50) }
        val propertyType = short(valueFromCells("물건종류", minY = 0.32, maxY = 0.58), 40)
            .ifBlank { short(valueFromLines("물건종류"), 40) }
        val propertyAddress = address(valueFromCells("물건소재지", "물건 소재지", minY = 0.34, maxY = 0.62))
            .ifBlank { address(valueFromLines("물건소재지", "물건 소재지")) }

        // 소유자 행은 '성명' 셀 오른쪽을 우선 사용한다. 임차인 표보다 위쪽만 검색한다.
        val ownerRaw = valueFromCells("성명", minY = 0.38, maxY = 0.59)
            .ifBlank { valueFromCells("물건소유자", "물건 소유자", minY = 0.36, maxY = 0.59) }
        val ownerName = personName(ownerRaw)
        val ownerResident = resident(ownerRaw).ifBlank {
            resident(valueFromCells("주민번호", minY = 0.38, maxY = 0.60))
        }
        val ownerPhone = phoneFrom(valueFromCells("연락처", minY = 0.38, maxY = 0.60))
        val ownerAddress = address(valueFromCells("소유자주소", "소유자 주소", minY = 0.40, maxY = 0.66))
            .ifBlank { address(valueFromLines("소유자주소", "소유자 주소")) }

        val notes = regexLine(
            Regex("기타요청사항[\\s\\S]{0,80}?\\n([\\s\\S]{0,450}?)(?=농협영업점|영업점|조사의뢰자|$)")
        ).replace(Regex("\\s+"), " ").trim().take(400)

        val branch = short(regexLine(Regex("(?:농협영업점|영업점)\\s*[:：]?\\s*([^\\n]+)")), 70)
        val requester = short(regexLine(Regex("조사의뢰자\\s*[:：]?\\s*([^\\n]+)")), 40)

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

    fun parse(text: String): InvestigationCase {
        val d = Regex("(20\\d{2})[^0-9]{0,5}(\\d{1,2})[^0-9]{0,5}(\\d{1,2})").find(text)
        val date = d?.let {
            "%04d-%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }.orEmpty()
        return InvestigationCase(year = date.take(4).toIntOrNull() ?: LocalDate.now().year, requestDate = date)
    }

    private fun clean(s: String): String = s
        .replace('｜', '|')
        .replace(Regex("[\\t ]+"), " ")
        .trim()
}
