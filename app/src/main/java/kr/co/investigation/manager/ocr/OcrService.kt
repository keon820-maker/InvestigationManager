package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

/**
 * 조사의뢰서 OCR 파이프라인.
 *
 * v0.8은 한 가지 추출법에만 의존하지 않는다.
 * 1) EXIF + 문서 외곽/원근 보정
 * 2) 문서 방향 자동 확인
 * 3) 전체 문맥 OCR
 * 4) ML Kit bounding box 기반 fuzzy 공간 anchor OCR
 * 5) 품질이 낮을 때 표 선 제거/대비 보정 이미지로 2차 OCR
 * 6) 필드별로 가장 타당한 후보를 병합
 *
 * 모든 전처리는 메모리 Bitmap에만 수행하며 원본 증거사진은 수정하지 않는다.
 */
object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    private data class OrientationChoice(
        val bitmap: Bitmap,
        val text: Text,
        val rotation: Int,
        val labelScore: Int
    )

    private val labels = listOf(
        "관리번호", "조사담당자", "의뢰일", "채무자명", "전화번호", "핸드폰번호", "완료요청일",
        "조사구분", "대출종류", "물건종류", "물건소재지", "물건소유자", "성명", "주민번호",
        "연락처", "소유자주소", "기타요청사항", "농협영업점", "영업점", "조사의뢰자"
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

        try {
            val orientation = chooseOrientation(client, normalized.bitmap)
            val semantic = parseRaw(orientation.text.text)
            val spatial = SpatialFieldExtractor.extract(
                orientation.text,
                orientation.bitmap.width,
                orientation.bitmap.height
            )
            var merged = mergePreferAnchored(semantic, spatial.case)
            var quality = qualityScore(merged)
            var enhancedUsed = false
            var enhancedRaw = ""

            // 필드가 충분히 채워지지 않았을 때만 표 선 제거 2차 OCR을 실행한다.
            // 정상 문서는 불필요하게 두 번 OCR하지 않아 처리시간을 줄인다.
            if (quality < 12 || criticalMissing(merged)) {
                val enhanced = OcrImageEnhancer.enhance(orientation.bitmap)
                try {
                    val enhancedText = recognizeText(client, enhanced)
                    enhancedRaw = enhancedText.text
                    val enhancedSemantic = parseRaw(enhancedText.text)
                    val enhancedSpatial = SpatialFieldExtractor.extract(
                        enhancedText,
                        enhanced.width,
                        enhanced.height
                    )
                    val enhancedMerged = mergePreferAnchored(enhancedSemantic, enhancedSpatial.case)
                    merged = mergeFallback(merged, enhancedMerged)
                    quality = qualityScore(merged)
                    enhancedUsed = true
                } finally {
                    if (enhanced !== orientation.bitmap && !enhanced.isRecycled) enhanced.recycle()
                }
            }

            val raw = buildString {
                append(orientation.text.text)
                if (enhancedUsed && enhancedRaw.isNotBlank()) {
                    append("\n\n--- OCR 보정 재인식 ---\n")
                    append(enhancedRaw)
                }
            }

            val rotationText = if (orientation.rotation == 0) "방향 정상" else "방향 ${orientation.rotation}° 자동보정"
            val secondPass = if (enhancedUsed) " / 표선 제거 2차 OCR" else ""
            return OcrResult(
                rawText = raw,
                parsed = merged,
                normalized = normalized.documentDetected,
                preprocessMessage = "${normalized.message} / $rotationText / ${spatial.message} / OCR 품질 $quality/17$secondPass"
            )
        } finally {
            client.close()
        }
    }

    suspend fun recognize(context: Context, uri: Uri): String = recognizeCase(context, uri).rawText

    /** 텍스트 라벨이 거의 안 잡히면 90/180/270도를 추가 검사해 가장 자연스러운 방향을 고른다. */
    private suspend fun chooseOrientation(client: TextRecognizer, bitmap: Bitmap): OrientationChoice {
        val baseText = recognizeText(client, bitmap)
        val baseScore = documentLabelScore(baseText.text)
        if (baseScore >= 6) return OrientationChoice(bitmap, baseText, 0, baseScore)

        val candidates = mutableListOf(OrientationChoice(bitmap, baseText, 0, baseScore))
        for (degree in listOf(180, 90, 270)) {
            val rotated = rotate(bitmap, degree)
            val text = recognizeText(client, rotated)
            candidates += OrientationChoice(rotated, text, degree, documentLabelScore(text.text))
            // 180도에서 충분한 라벨이 검출되면 90/270도까지 갈 필요가 없다.
            if (degree == 180 && candidates.last().labelScore >= 8) break
        }

        val best = candidates.maxWithOrNull(
            compareBy<OrientationChoice> { it.labelScore }
                .thenBy { hangulCount(it.text.text) }
        ) ?: candidates.first()

        candidates.filter { it !== best && it.bitmap !== bitmap }.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }
        return best
    }

    private suspend fun recognizeText(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun rotate(src: Bitmap, degree: Int): Bitmap {
        val m = Matrix().apply { postRotate(degree.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun documentLabelScore(text: String): Int {
        val c = compact(text)
        return labels.fold(0) { acc, label ->
            val l = compact(label)
            acc + when {
                c.contains(l) -> 2
                fuzzyContains(c, l) -> 1
                else -> 0
            }
        }
    }

    private fun fuzzyContains(haystack: String, needle: String): Boolean {
        if (needle.length < 4 || haystack.length < needle.length - 1) return false
        val minLen = (needle.length - 1).coerceAtLeast(3)
        val maxLen = (needle.length + 1).coerceAtMost(haystack.length)
        for (len in minLen..maxLen) {
            for (i in 0..haystack.length - len) {
                if (editDistance(haystack.substring(i, i + len), needle) <= 1) return true
            }
        }
        return false
    }

    /**
     * 전체 텍스트 문맥 기반 보조 파서.
     * 공간 anchor 추출이 실패한 필드를 채우기 위한 fallback이며, 값 검증을 강하게 적용한다.
     */
    fun parse(text: String): InvestigationCase = parseRaw(text)

    private fun parseRaw(text: String): InvestigationCase {
        val lines = text.lines().map(::clean).filter { it.isNotBlank() }

        fun labelRegex(label: String): Regex {
            val chars = label.filterNot(Char::isWhitespace).map { Regex.escape(it.toString()) }
            return Regex(chars.joinToString("\\s*"), RegexOption.IGNORE_CASE)
        }

        fun containsLabel(line: String, label: String): Boolean = compact(line).contains(compact(label))

        fun segmentAfter(line: String, names: List<String>): String {
            var start = -1
            names.forEach { name ->
                labelRegex(name).find(line)?.let { start = maxOf(start, it.range.last + 1) }
            }
            if (start < 0 || start >= line.length) return ""
            val tail = line.substring(start).trim(' ', ':', '：', '|', '·', '-')
            var end = tail.length
            for (lab in labels) {
                val m = labelRegex(lab).find(tail) ?: continue
                if (m.range.first > 0) end = minOf(end, m.range.first)
            }
            return tail.substring(0, end).trim(' ', ':', '：', '|', '·', '-')
        }

        fun valueFor(vararg names: String): String {
            val row = lines.firstOrNull { line -> names.any { containsLabel(line, it) } } ?: return ""
            val inline = segmentAfter(row, names.toList())
            if (inline.isNotBlank()) return inline
            val idx = lines.indexOf(row)
            return lines.getOrNull(idx + 1)?.takeIf { next -> labels.none { containsLabel(next, it) } }.orEmpty()
        }

        val dates = findDates(text)
        val requestDate = normalizeDate(valueFor("의뢰일")).ifBlank { dates.firstOrNull().orEmpty() }
        val dueDate = normalizeDate(valueFor("완료요청일"))
            .ifBlank { dates.firstOrNull { it != requestDate }.orEmpty() }

        val management = normalizeManagement(valueFor("관리번호"))
        val investigator = personName(valueFor("조사담당자"))
        var debtor = personName(valueFor("채무자명"))

        val personPairs = Regex("([가-힣]{2,6})\\s*\\(\\s*(\\d{6})[^)]*\\)")
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (debtor.isBlank()) debtor = personPairs.firstOrNull()?.first.orEmpty()

        val phones = findPhones(text)
        val phone = normalizePhone(valueFor("전화번호")).ifBlank { phones.getOrNull(0).orEmpty() }
        val mobile = normalizePhone(valueFor("핸드폰번호")).ifBlank {
            phones.firstOrNull { it.startsWith("010") }.orEmpty().ifBlank { phones.getOrNull(1).orEmpty() }
        }

        val investigationType = cleanShort(valueFor("조사구분"), 70)
            .ifBlank {
                Regex("[가-힣]{2,20}조사(?:\\([^)]{1,30}\\))?").find(text)?.value.orEmpty()
            }
        val loanType = cleanShort(valueFor("대출종류"), 70)
            .ifBlank { Regex("[가-힣]{2,20}(?:담보)?대출").find(text)?.value.orEmpty() }
        val propertyType = cleanShort(valueFor("물건종류"), 50)
            .ifBlank {
                listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
                    .firstOrNull { text.contains(it) }.orEmpty()
            }

        var propertyAddress = normalizeAddress(valueFor("물건소재지"))
        if (propertyAddress.isBlank()) {
            propertyAddress = lines.map(::normalizeAddress).filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()
        }

        var ownerName = personName(valueFor("성명", "물건소유자"))
        if (ownerName.isBlank()) ownerName = personPairs.getOrNull(1)?.first.orEmpty()
        val ownerResident = normalizeResident(valueFor("주민번호"))
            .ifBlank { personPairs.getOrNull(1)?.second?.let { "$it-" }.orEmpty() }
        val ownerPhone = normalizePhone(valueFor("연락처"))
            .ifBlank { phones.drop(2).firstOrNull().orEmpty() }
        val ownerAddress = normalizeAddress(valueFor("소유자주소"))

        val notes = extractNotes(lines)
        val branch = cleanShort(valueFor("농협영업점", "영업점"), 100)
        val requester = personName(valueFor("조사의뢰자"))

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

    /** 공간 anchor가 붙어 있는 값은 단순 문자열 순서보다 신뢰도가 높으므로 우선한다. */
    private fun mergePreferAnchored(semantic: InvestigationCase, anchored: InvestigationCase): InvestigationCase {
        fun a(v: String, fallback: String) = v.ifBlank { fallback }
        return semantic.copy(
            year = anchored.requestDate.take(4).toIntOrNull() ?: semantic.year,
            managementNo = a(anchored.managementNo, semantic.managementNo),
            requestDate = a(anchored.requestDate, semantic.requestDate),
            investigator = a(anchored.investigator, semantic.investigator),
            debtorName = a(anchored.debtorName, semantic.debtorName),
            phone = a(anchored.phone, semantic.phone),
            mobile = a(anchored.mobile, semantic.mobile),
            dueDate = a(anchored.dueDate, semantic.dueDate),
            investigationType = a(anchored.investigationType, semantic.investigationType),
            loanType = a(anchored.loanType, semantic.loanType),
            propertyType = a(anchored.propertyType, semantic.propertyType),
            propertyAddress = betterAddress(anchored.propertyAddress, semantic.propertyAddress),
            ownerName = a(anchored.ownerName, semantic.ownerName),
            ownerResidentNo = a(anchored.ownerResidentNo, semantic.ownerResidentNo),
            ownerPhone = a(anchored.ownerPhone, semantic.ownerPhone),
            ownerAddress = betterAddress(anchored.ownerAddress, semantic.ownerAddress),
            requestNotes = betterNotes(anchored.requestNotes, semantic.requestNotes),
            branch = a(anchored.branch, semantic.branch),
            requester = a(anchored.requester, semantic.requester)
        )
    }

    /** 2차 OCR은 1차의 정상값을 덮지 않고 누락/명백히 더 나은 값만 보완한다. */
    private fun mergeFallback(primary: InvestigationCase, secondary: InvestigationCase): InvestigationCase {
        fun keep(a: String, b: String) = a.ifBlank { b }
        return primary.copy(
            managementNo = keep(primary.managementNo, secondary.managementNo),
            requestDate = keep(primary.requestDate, secondary.requestDate),
            investigator = keep(primary.investigator, secondary.investigator),
            debtorName = keep(primary.debtorName, secondary.debtorName),
            phone = keep(primary.phone, secondary.phone),
            mobile = keep(primary.mobile, secondary.mobile),
            dueDate = keep(primary.dueDate, secondary.dueDate),
            investigationType = keep(primary.investigationType, secondary.investigationType),
            loanType = keep(primary.loanType, secondary.loanType),
            propertyType = keep(primary.propertyType, secondary.propertyType),
            propertyAddress = betterAddress(primary.propertyAddress, secondary.propertyAddress),
            ownerName = keep(primary.ownerName, secondary.ownerName),
            ownerResidentNo = keep(primary.ownerResidentNo, secondary.ownerResidentNo),
            ownerPhone = keep(primary.ownerPhone, secondary.ownerPhone),
            ownerAddress = betterAddress(primary.ownerAddress, secondary.ownerAddress),
            requestNotes = betterNotes(primary.requestNotes, secondary.requestNotes),
            branch = keep(primary.branch, secondary.branch),
            requester = keep(primary.requester, secondary.requester)
        )
    }

    private fun criticalMissing(c: InvestigationCase): Boolean =
        c.debtorName.isBlank() || c.propertyAddress.isBlank() ||
            (c.phone.isBlank() && c.mobile.isBlank()) || c.dueDate.isBlank()

    private fun qualityScore(c: InvestigationCase): Int {
        var s = 0
        if (c.managementNo.isNotBlank()) s++
        if (c.requestDate.isNotBlank()) s++
        if (c.investigator.isNotBlank()) s++
        if (c.debtorName.isNotBlank()) s += 2
        if (c.phone.isNotBlank() || c.mobile.isNotBlank()) s++
        if (c.dueDate.isNotBlank()) s++
        if (c.investigationType.isNotBlank()) s++
        if (c.loanType.isNotBlank()) s++
        if (c.propertyType.isNotBlank()) s++
        if (c.propertyAddress.isNotBlank()) s += 2
        if (c.ownerName.isNotBlank()) s++
        if (c.ownerResidentNo.isNotBlank()) s++
        if (c.requestNotes.isNotBlank()) s++
        if (c.branch.isNotBlank()) s++
        if (c.requester.isNotBlank()) s++
        return s.coerceAtMost(17)
    }

    private fun betterAddress(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        b.length > a.length + 5 -> b
        else -> a
    }

    private fun betterNotes(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        b.length > a.length * 1.25 -> b
        else -> a
    }

    private fun extractNotes(lines: List<String>): String {
        val idx = lines.indexOfFirst { compact(it).contains("기타요청사항") }
        if (idx < 0) return ""
        val first = lines[idx].substringAfter("기타요청사항", "").trim(' ', ':', '：')
        val rest = lines.drop(idx + 1).take(8).takeWhile {
            val c = compact(it)
            !c.contains("농협영업점") && !c.contains("조사의뢰자") && !c.contains("영업점")
        }
        return (listOf(first) + rest).filter { it.isNotBlank() }.joinToString(" ").take(700)
    }

    private fun findDates(text: String): List<String> {
        val out = mutableListOf<String>()
        Regex("20\\d{2}[^\\n]{0,14}?\\d{1,2}[^\\n]{0,10}?\\d{1,2}")
            .findAll(text).forEach { normalizeDate(it.value).takeIf(String::isNotBlank)?.let(out::add) }
        Regex("20\\d{2}[-./]?\\d{4}")
            .findAll(text).forEach { normalizeDate(it.value).takeIf(String::isNotBlank)?.let(out::add) }
        return out.distinct()
    }

    private fun normalizeDate(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val patterns = listOf(
            Regex("(20\\d{2})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})\\s*일?"),
            Regex("(20\\d{2})[-./]?(\\d{2})(\\d{2})")
        )
        for (r in patterns) {
            val m = r.find(fixed) ?: continue
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (mo in 1..12 && d in 1..31) return "%04d-%02d-%02d".format(y, mo, d)
        }
        return ""
    }

    private fun findPhones(text: String): List<String> =
        Regex("01\\d[- )]?\\d{3,4}[- ]?\\d{4}|0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}")
            .findAll(text).mapNotNull { normalizePhone(it.value).takeIf(String::isNotBlank) }.distinct().toList()

    private fun normalizePhone(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val m = Regex("01\\d[- )]?\\d{3,4}[- ]?\\d{4}|0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}").find(fixed)
        val d = (m?.value ?: fixed).filter(Char::isDigit)
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
        val m = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*]?[0-9*]{0,6})").find(fixed) ?: return ""
        return if (m.groupValues[2].isBlank()) "${m.groupValues[1]}-" else "${m.groupValues[1]}-${m.groupValues[2]}"
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
        return s.takeIf { it.length in 1..maxLen && labels.none { lab -> compact(it) == compact(lab) } }.orEmpty()
    }

    private fun hangulCount(text: String): Int = text.count { it in '가'..'힣' }

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

    private fun compact(s: String): String = s.replace(Regex("[^가-힣A-Za-z0-9]"), "")
    private fun clean(s: String): String = s.replace('｜', '|').replace(Regex("[\\t ]+"), " ").trim()
}
