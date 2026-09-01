package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
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
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
        val h: Int get() = box.height().coerceAtLeast(1)
    }

    private val knownLabels = listOf(
        "관리번호", "조사담당자", "채무자명", "채무자 명", "전화번호", "핸드폰번호",
        "완료요청일", "조사구분", "대출종류", "물건종류", "물건소재지", "물건 소재지",
        "물건소유자", "물건 소유자", "주민번호", "연락처", "소유자주소", "소유자 주소",
        "기타요청사항", "농협영업점", "영업점", "조사의뢰자", "의뢰일"
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val result = recognizeText(normalized.bitmap)
        val parsed = parseStructured(result)
        return OcrResult(
            rawText = result.text,
            parsed = parsed,
            normalized = normalized.documentDetected,
            preprocessMessage = normalized.message + " / 위치·라벨 결합 OCR"
        )
    }

    suspend fun recognize(context: Context, uri: Uri): String = recognizeCase(context, uri).rawText

    private suspend fun recognizeText(bitmap: Bitmap): Text = suspendCancellableCoroutine { c ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        client.process(image)
            .addOnSuccessListener { result ->
                client.close()
                if (c.isActive) c.resume(result)
            }
            .addOnFailureListener { error ->
                client.close()
                if (c.isActive) c.resumeWithException(error)
            }
    }

    private fun parseStructured(result: Text): InvestigationCase {
        val nodes = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { Node(clean(line.text), Rect(it)) }
        }.filter { it.text.isNotBlank() }

        fun compact(s: String) = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")
        fun isLabelText(s: String): Boolean {
            val c = compact(s)
            return knownLabels.any { lab ->
                val l = compact(lab)
                c == l || c.startsWith(l) || (c.length <= l.length + 2 && c.contains(l))
            }
        }

        fun labelRegex(label: String): Regex {
            val body = label.filterNot { it.isWhitespace() }
                .map { Regex.escape(it.toString()) }
                .joinToString("\\s*")
            return Regex(body, RegexOption.IGNORE_CASE)
        }

        fun findAnchor(vararg aliases: String, minTop: Int = Int.MIN_VALUE, maxTop: Int = Int.MAX_VALUE): Node? {
            val norms = aliases.map { compact(it) }
            return nodes.asSequence()
                .filter { it.box.top in minTop..maxTop }
                .mapNotNull { n ->
                    val c = compact(n.text)
                    val score = norms.maxOfOrNull { a ->
                        when {
                            c == a -> 100
                            c.startsWith(a) -> 90
                            c.contains(a) -> 80
                            a.contains(c) && c.length >= 3 -> 60
                            else -> 0
                        }
                    } ?: 0
                    if (score > 0) n to score else null
                }
                .sortedWith(compareByDescending<Pair<Node, Int>> { it.second }.thenBy { it.first.box.top }.thenBy { it.first.box.left })
                .firstOrNull()?.first
        }

        fun stripNextLabel(s: String): String {
            var end = s.length
            for (lab in knownLabels) {
                val m = labelRegex(lab).find(s) ?: continue
                if (m.range.first > 0) end = minOf(end, m.range.first)
            }
            return s.substring(0, end).trim(' ', ':', '：', '|', '-', '·')
        }

        fun inlineAfter(anchor: Node, vararg aliases: String): String {
            for (alias in aliases) {
                val m = labelRegex(alias).find(anchor.text) ?: continue
                val rest = anchor.text.substring(m.range.last + 1)
                    .trim(' ', ':', '：', '|', '-', '·')
                if (rest.isNotBlank()) return stripNextLabel(rest)
            }
            return ""
        }

        fun sameRowRight(anchor: Node, maxGapFactor: Double = 10.0): String {
            val tolerance = max(anchor.h, 34) * 0.8
            val candidates = nodes.filter { n ->
                n !== anchor &&
                    abs(n.cy - anchor.cy) <= tolerance &&
                    n.box.left >= anchor.box.right - 8 &&
                    n.box.left - anchor.box.right <= anchor.h * maxGapFactor &&
                    !isLabelText(n.text)
            }.sortedBy { it.box.left }
            return candidates.joinToString(" ") { it.text }.trim()
        }

        fun nearBelow(anchor: Node): String {
            val candidates = nodes.filter { n ->
                n !== anchor && n.box.top >= anchor.box.bottom - 4 &&
                    n.box.top - anchor.box.bottom <= anchor.h * 2.0 &&
                    abs(n.box.left - anchor.box.left) <= anchor.h * 4 &&
                    !isLabelText(n.text)
            }.sortedWith(compareBy<Node> { it.box.top }.thenBy { it.box.left })
            return candidates.firstOrNull()?.text.orEmpty()
        }

        fun valueFor(vararg aliases: String, maxTop: Int = Int.MAX_VALUE): String {
            val anchor = findAnchor(*aliases, maxTop = maxTop) ?: return ""
            return inlineAfter(anchor, *aliases)
                .ifBlank { sameRowRight(anchor) }
                .ifBlank { nearBelow(anchor) }
                .trim()
        }

        fun multilineAfter(vararg aliases: String, maxLines: Int = 4): String {
            val anchor = findAnchor(*aliases) ?: return ""
            val same = inlineAfter(anchor, *aliases)
            val below = nodes.filter { n ->
                n.box.top >= anchor.box.bottom - 3 &&
                    n.box.top - anchor.box.bottom <= anchor.h * (maxLines + 2) &&
                    n.box.left >= anchor.box.left - anchor.h * 2 &&
                    !isLabelText(n.text)
            }.sortedWith(compareBy<Node> { it.box.top }.thenBy { it.box.left })
                .take(maxLines).joinToString(" ") { it.text }
            return listOf(same, below).filter { it.isNotBlank() }.joinToString(" ").trim()
        }

        fun dateFrom(value: String): String {
            val fixed = value.uppercase().replace('O', '0')
            val m = Regex("(20\\d{2})[^0-9]{0,5}(\\d{1,2})[^0-9]{0,5}(\\d{1,2})").find(fixed) ?: return ""
            val y = m.groupValues[1].toIntOrNull() ?: return ""
            val mo = m.groupValues[2].toIntOrNull() ?: return ""
            val d = m.groupValues[3].toIntOrNull() ?: return ""
            if (mo !in 1..12 || d !in 1..31) return ""
            return "%04d-%02d-%02d".format(y, mo, d)
        }

        fun phoneFrom(value: String): String {
            val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
            val digits = fixed.filter(Char::isDigit)
            return when {
                digits.length == 11 && digits.startsWith("01") -> "${digits.substring(0,3)}-${digits.substring(3,7)}-${digits.substring(7)}"
                digits.length == 10 && digits.startsWith("02") -> "02-${digits.substring(2,6)}-${digits.substring(6)}"
                digits.length == 10 -> "${digits.substring(0,3)}-${digits.substring(3,6)}-${digits.substring(6)}"
                digits.length == 9 && digits.startsWith("02") -> "02-${digits.substring(2,5)}-${digits.substring(5)}"
                else -> ""
            }
        }

        fun cleanValue(v: String, vararg labels: String): String {
            var out = clean(v)
            labels.forEach { out = out.replace(labelRegex(it), "") }
            return out.replace(Regex("^[ :：|·-]+"), "").replace(Regex("\\s+"), " ").trim()
        }

        fun short(v: String, maxLen: Int): String {
            val s = cleanValue(v)
            return if (s.length in 1..maxLen && !isLabelText(s)) s else ""
        }

        fun address(v: String): String {
            val s = cleanValue(v, "물건소재지", "물건 소재지", "소유자주소", "소유자 주소")
                .replace(Regex("\\s+"), " ").trim()
            if (s.length !in 6..180) return ""
            val hint = Regex("(특별시|광역시|특별자치|[가-힣]+도|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+동|[가-힣]+로|[가-힣]+길|번지)")
            return if (hint.containsMatchIn(s)) s else ""
        }

        fun residentNo(v: String): String = Regex("\\d{6}\\s*-?\\s*[1-4*][0-9*]{0,6}")
            .find(v)?.value?.replace(" ", "").orEmpty()

        val raw = result.text
        val requestDate = dateFrom(valueFor("의뢰일")).ifBlank {
            val m = Regex("의뢰일[^0-9]*(20\\d{2})[^0-9]{0,5}(\\d{1,2})[^0-9]{0,5}(\\d{1,2})").find(raw)
            if (m != null) dateFrom(m.value) else ""
        }
        val dueDate = dateFrom(valueFor("완료요청일"))
        val management = cleanValue(valueFor("관리번호"), "관리번호")
            .replace(Regex("^[Oo○]"), "0").takeIf { it.length in 3..40 }.orEmpty()
        val investigator = short(cleanValue(valueFor("조사담당자"), "조사담당자"), 30)
        val debtor = short(cleanValue(valueFor("채무자명", "채무자 명"), "채무자명", "채무자 명"), 30)
        val phone = phoneFrom(valueFor("전화번호"))
        val mobile = phoneFrom(valueFor("핸드폰번호"))
        val investigationType = short(cleanValue(valueFor("조사구분"), "조사구분"), 40)
        val loanType = short(cleanValue(valueFor("대출종류"), "대출종류"), 40)
        val propertyType = short(cleanValue(valueFor("물건종류"), "물건종류"), 30)
        val propertyAddress = address(valueFor("물건소재지", "물건 소재지"))

        val ownerName = short(cleanValue(valueFor("물건소유자", "물건 소유자"), "물건소유자", "물건 소유자"), 30)
        val ownerResident = residentNo(valueFor("주민번호"))
        val ownerPhone = phoneFrom(valueFor("연락처"))
        val ownerAddress = address(valueFor("소유자주소", "소유자 주소"))
        val notes = cleanValue(multilineAfter("기타요청사항", maxLines = 5), "기타요청사항").take(400)
        val branch = short(cleanValue(valueFor("농협영업점", "영업점"), "농협영업점", "영업점"), 60)
        val requester = short(cleanValue(valueFor("조사의뢰자"), "조사의뢰자"), 40)

        return InvestigationCase(
            year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
            managementNo = management,
            requestDate = requestDate,
            investigator = investigator,
            debtorName = debtor,
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
        // 과거 호출 호환용. 신규 등록은 위치 정보를 가진 parseStructured를 사용한다.
        val d = Regex("(20\\d{2})[^0-9]{0,5}(\\d{1,2})[^0-9]{0,5}(\\d{1,2})").find(text)
        val date = d?.let { "%04d-%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.orEmpty()
        return InvestigationCase(year = date.take(4).toIntOrNull() ?: LocalDate.now().year, requestDate = date)
    }

    private fun clean(s: String): String = s
        .replace('｜', '|')
        .replace(Regex("[\\t ]+"), " ")
        .trim()
}
