package kr.co.investigation.manager.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * ML Kit의 문자열 순서만 믿지 않고 실제 bounding box를 이용해
 * '라벨 -> 같은 셀/오른쪽 값' 관계를 복원한다.
 *
 * 한글 라벨이 1~3글자 정도 틀리거나 공백으로 분리되어도 edit distance로 anchor를 찾는다.
 * 원근 보정 후 A4 좌표를 쓰므로 촬영 위치/각도가 달라도 상대적인 섹션 위치가 유지된다.
 */
object SpatialFieldExtractor {
    data class Result(
        val case: InvestigationCase,
        val anchorCount: Int,
        val message: String
    )

    private data class Token(val text: String, val box: Rect)
    private data class LineTokens(val tokens: List<Token>, val box: Rect)
    private data class Anchor(
        val lineIndex: Int,
        val start: Int,
        val end: Int,
        val box: Rect,
        val score: Int,
        val alias: String
    )

    private val aliases = listOf(
        "관리번호", "조사담당자", "의뢰일",
        "채무자명", "전화번호", "핸드폰번호", "완료요청일",
        "조사구분", "대출종류", "물건종류", "물건소재지",
        "물건소유자", "성명", "주민번호", "연락처", "소유자주소",
        "기타요청사항", "농협영업점", "영업점", "조사의뢰자"
    )

    fun extract(result: Text, imageWidth: Int, imageHeight: Int): Result {
        val lines = buildLines(result)
        val raw = result.text
        var usedAnchors = 0

        fun y(min: Double, max: Double): IntRange =
            (imageHeight * min).toInt()..(imageHeight * max).toInt()

        fun anchor(vararg names: String, range: IntRange = 0..imageHeight): Anchor? {
            val a = findAnchor(lines, names.toList(), range)
            if (a != null) usedAnchors++
            return a
        }

        fun value(a: Anchor?, below: Boolean = true): String {
            if (a == null) return ""
            val right = valueRight(lines, a)
            if (right.isNotBlank()) return right
            return if (below) valueBelow(lines, a, imageWidth) else ""
        }

        val requestDate = normalizeDate(value(anchor("의뢰일", range = y(0.0, 0.30))))
            .ifBlank { findDates(raw).firstOrNull().orEmpty() }

        val management = normalizeManagement(value(anchor("관리번호", range = y(0.0, 0.32))))
        val investigator = personName(value(anchor("조사담당자", range = y(0.0, 0.35))))

        val debtorName = personName(value(anchor("채무자명", range = y(0.12, 0.48))))
        val debtorPhone = normalizePhone(value(anchor("전화번호", range = y(0.12, 0.48))))
        val debtorMobile = normalizePhone(value(anchor("핸드폰번호", range = y(0.12, 0.48))))
        val dueDate = normalizeDate(value(anchor("완료요청일", range = y(0.12, 0.52))))
            .ifBlank { findDates(raw).firstOrNull { it != requestDate }.orEmpty() }

        val investigationType = cleanShort(value(anchor("조사구분", range = y(0.20, 0.62))), 70)
        val loanType = cleanShort(value(anchor("대출종류", range = y(0.20, 0.62))), 70)
            .ifBlank { Regex("[가-힣]{2,20}(?:담보)?대출").find(raw)?.value.orEmpty() }
        val propertyType = cleanShort(value(anchor("물건종류", range = y(0.20, 0.64))), 50)
            .ifBlank {
                listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
                    .firstOrNull { raw.contains(it) }.orEmpty()
            }

        val propertyAddress = normalizeAddress(
            value(anchor("물건소재지", range = y(0.24, 0.70)))
        )

        // 물건소유자 행 내부의 세부 라벨을 각각 독립 anchor로 읽는다.
        val ownerName = personName(
            value(anchor("성명", "물건소유자", range = y(0.30, 0.74)))
        )
        val ownerResident = normalizeResident(
            value(anchor("주민번호", range = y(0.30, 0.76)))
        )
        val ownerPhone = normalizePhone(
            value(anchor("연락처", range = y(0.30, 0.76)))
        )
        val ownerAddress = normalizeAddress(
            value(anchor("소유자주소", range = y(0.34, 0.82)))
        )

        val notesAnchor = anchor("기타요청사항", range = y(0.45, 0.92))
        val branchAnchor = anchor("농협영업점", "영업점", range = y(0.62, 1.0))
        val requesterAnchor = anchor("조사의뢰자", range = y(0.62, 1.0))

        val notes = extractMultiline(lines, notesAnchor, listOfNotNull(branchAnchor, requesterAnchor), imageWidth)
            .take(700)
        val branch = cleanShort(value(branchAnchor), 100)
        val requester = personName(value(requesterAnchor))

        return Result(
            case = InvestigationCase(
                year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
                managementNo = management,
                requestDate = requestDate,
                investigator = investigator,
                debtorName = debtorName,
                phone = debtorPhone,
                mobile = debtorMobile,
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
            ),
            anchorCount = usedAnchors,
            message = "공간 anchor ${usedAnchors}개"
        )
    }

    private fun buildLines(result: Text): List<LineTokens> {
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val lineBox = line.boundingBox ?: return@mapNotNull null
            val tokens = line.elements.mapNotNull { e ->
                e.boundingBox?.let { Token(clean(e.text), Rect(it)) }
            }.filter { it.text.isNotBlank() }
            val actual = if (tokens.isNotEmpty()) tokens else listOf(Token(clean(line.text), Rect(lineBox)))
            LineTokens(actual.sortedBy { it.box.left }, Rect(lineBox))
        }.sortedWith(compareBy<LineTokens> { it.box.top }.thenBy { it.box.left })
    }

    private fun findAnchor(lines: List<LineTokens>, names: List<String>, yRange: IntRange): Anchor? {
        var best: Anchor? = null
        for ((li, line) in lines.withIndex()) {
            if (line.box.centerY() !in yRange) continue
            val t = line.tokens
            for (i in t.indices) {
                for (len in 1..minOf(4, t.size - i)) {
                    val seq = t.subList(i, i + len)
                    val candidate = compact(seq.joinToString("") { it.text })
                    if (candidate.isBlank()) continue
                    for (name in names) {
                        val score = matchScore(candidate, compact(name))
                        if (score < 76) continue
                        val rect = union(seq.map { it.box })
                        val a = Anchor(li, i, i + len - 1, rect, score, name)
                        if (best == null || a.score > best!!.score ||
                            (a.score == best!!.score && a.box.top < best!!.box.top)) {
                            best = a
                        }
                    }
                }
            }
        }
        return best
    }

    private fun valueRight(lines: List<LineTokens>, anchor: Anchor): String {
        val line = lines.getOrNull(anchor.lineIndex) ?: return ""
        if (anchor.end >= line.tokens.lastIndex) return ""
        val out = mutableListOf<String>()
        var i = anchor.end + 1
        while (i < line.tokens.size && out.size < 16) {
            if (labelStartsAt(line.tokens, i)) break
            val token = line.tokens[i]
            // 아주 멀리 떨어진 것은 같은 OCR line으로 잘못 합쳐진 다른 표 영역일 가능성이 높다.
            if (out.isEmpty() && token.box.left - anchor.box.right > max(anchor.box.height() * 12, 700)) break
            out += token.text
            i++
        }
        return clean(out.joinToString(" "))
    }

    private fun valueBelow(lines: List<LineTokens>, anchor: Anchor, imageWidth: Int): String {
        val ah = anchor.box.height().coerceAtLeast(20)
        val maxVertical = ah * 4
        val candidate = lines.asSequence()
            .filter { it.box.top >= anchor.box.bottom - 4 && it.box.top - anchor.box.bottom <= maxVertical }
            .sortedBy { it.box.top }
            .mapNotNull { line ->
                val tokens = line.tokens.filter { t ->
                    t.box.right >= anchor.box.left - ah * 2 &&
                        t.box.left <= minOf(imageWidth, anchor.box.right + max(ah * 12, imageWidth / 3)) &&
                        !looksLikeLabel(t.text)
                }
                clean(tokens.joinToString(" ") { it.text }).takeIf { it.isNotBlank() }
            }
            .firstOrNull()
        return candidate.orEmpty()
    }

    private fun extractMultiline(
        lines: List<LineTokens>,
        start: Anchor?,
        stops: List<Anchor>,
        imageWidth: Int
    ): String {
        if (start == null) return ""
        val inline = valueRight(lines, start)
        val stopY = stops.map { it.box.top }.filter { it > start.box.bottom }.minOrNull() ?: Int.MAX_VALUE
        val below = lines.asSequence()
            .filter { it.box.top >= start.box.bottom - 3 && it.box.top < stopY }
            .filter { it.box.left < imageWidth * 0.97 }
            .map { line -> line.tokens.filterNot { looksLikeLabel(it.text) }.joinToString(" ") { it.text } }
            .map(::clean)
            .filter { it.isNotBlank() }
            .take(8)
            .toList()
        return (listOf(inline) + below).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    private fun labelStartsAt(tokens: List<Token>, index: Int): Boolean {
        for (len in 1..minOf(4, tokens.size - index)) {
            val c = compact(tokens.subList(index, index + len).joinToString("") { it.text })
            if (aliases.any { matchScore(c, compact(it)) >= 80 }) return true
        }
        return false
    }

    private fun looksLikeLabel(text: String): Boolean {
        val c = compact(text)
        return aliases.any { matchScore(c, compact(it)) >= 82 }
    }

    private fun matchScore(candidate: String, target: String): Int {
        if (candidate.isBlank() || target.isBlank()) return 0
        if (candidate == target) return 100
        if (candidate.contains(target)) return 96
        if (target.contains(candidate) && candidate.length >= max(3, target.length - 2)) return 88
        val d = editDistance(candidate, target)
        return when {
            d == 1 && minOf(candidate.length, target.length) >= 3 -> 88
            d == 2 && minOf(candidate.length, target.length) >= 5 -> 79
            else -> 0
        }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun union(rects: List<Rect>): Rect {
        return Rect(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom }
        )
    }

    private fun normalizeDate(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val patterns = listOf(
            Regex("(20\\d{2})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})\\s*일?"),
            Regex("(20\\d{2})[-./]?(\\d{2})(\\d{2})")
        )
        for (r in patterns) {
            val m = r.find(fixed) ?: continue
            val yy = m.groupValues[1].toIntOrNull() ?: continue
            val mm = m.groupValues[2].toIntOrNull() ?: continue
            val dd = m.groupValues[3].toIntOrNull() ?: continue
            if (mm in 1..12 && dd in 1..31) return "%04d-%02d-%02d".format(yy, mm, dd)
        }
        return ""
    }

    private fun findDates(raw: String): List<String> {
        val found = mutableListOf<String>()
        Regex("20\\d{2}[^\\n]{0,14}?\\d{1,2}[^\\n]{0,10}?\\d{1,2}")
            .findAll(raw).forEach { normalizeDate(it.value).takeIf(String::isNotBlank)?.let(found::add) }
        return found.distinct()
    }

    private fun normalizePhone(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val matched = Regex("0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}|01\\d[- ]?\\d{3,4}[- ]?\\d{4}")
            .find(fixed)?.value ?: fixed
        val d = matched.filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 && d.startsWith("0") -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2, 5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun normalizeResident(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val m = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*][0-9*]{0,6})").find(fixed) ?: return ""
        return "${m.groupValues[1]}-${m.groupValues[2]}"
    }

    private fun personName(value: String): String {
        val s = clean(value).substringBefore('(').substringBefore("주민").substringBefore("연락")
        return Regex("[가-힣]{2,6}").findAll(s).lastOrNull()?.value.orEmpty()
    }

    private fun normalizeManagement(value: String): String {
        val s = clean(value).replace(Regex("[^가-힣A-Za-z0-9_./-]"), "")
        return s.takeIf { it.length in 2..45 }.orEmpty()
    }

    private fun normalizeAddress(value: String): String {
        var s = clean(value).replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^\\d{5,6}\\s+"), "")
        if (s.length !in 7..220) return ""
        val hint = Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|특별시|광역시|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+동|[가-힣]+로|[가-힣]+길|번지)")
        return if (hint.containsMatchIn(s)) s else ""
    }

    private fun cleanShort(value: String, maxLen: Int): String {
        val s = clean(value).replace(Regex("^[○Oo0 :：|·-]+"), "")
        return s.takeIf { it.length in 1..maxLen && !looksLikeLabel(it) }.orEmpty()
    }

    private fun compact(s: String): String = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")
    private fun clean(s: String): String = s.replace('｜', '|').replace(Regex("[\\t ]+"), " ").trim()
}
