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
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** 고정 양식 조사의뢰서 전용 OCR. 원본 사진은 수정하지 않는다. */
object FixedTemplateOcr {
    private const val TEMPLATE_W = 2480.0
    private const val TEMPLATE_H = 3508.0

    private data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private data class Oriented(val bitmap: Bitmap, val fullText: Text, val rotation: Int)

    // 실제 제공된 조사의뢰서 양식을 A4 2480x3508로 정규화했을 때의 값 셀 좌표.
    // 라벨/표선이 섞이지 않도록 값 영역 안쪽만 자른다.
    private val boxes = linkedMapOf(
        "의뢰일" to Box(650, 175, 1900, 315),
        "관리번호" to Box(235, 315, 1280, 425),
        "조사담당자" to Box(235, 405, 980, 515),
        "채무자명" to Box(480, 915, 815, 995),
        "전화번호" to Box(1160, 915, 1495, 995),
        "핸드폰번호" to Box(1845, 915, 2360, 995),
        "완료요청일" to Box(480, 1020, 815, 1098),
        "조사구분" to Box(480, 1275, 1440, 1348),
        "대출종류" to Box(1780, 1275, 2370, 1348),
        "물건종류" to Box(480, 1375, 1440, 1450),
        "물건소재지" to Box(480, 1480, 2370, 1558),
        "소유자신원" to Box(795, 1585, 1440, 1665),
        "소유자연락처" to Box(1780, 1585, 2370, 1665),
        "소유자주소" to Box(480, 1695, 2370, 1772),
        "기타요청사항" to Box(165, 2550, 2370, 2818),
        "농협영업점" to Box(900, 2940, 2200, 3045),
        "조사의뢰자" to Box(900, 3050, 2200, 3165)
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrService.OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            if (!normalized.documentDetected || normalized.bitmap.width < 2000 || normalized.bitmap.height < 2800) {
                val full = recognizeText(client, normalized.bitmap)
                val parsed = parseFallback(full.text)
                return OcrService.OcrResult(
                    rawText = full.text,
                    parsed = parsed,
                    normalized = false,
                    preprocessMessage = "${normalized.message} / 고정양식 정렬 실패 - 전체 OCR 보조모드"
                )
            }

            val oriented = chooseUpright(client, normalized.bitmap)
            val fallback = parseFallback(oriented.fullText.text)
            val rawFields = linkedMapOf<String, String>()
            for ((name, box) in boxes) rawFields[name] = recognizeBox(client, oriented.bitmap, box)

            val ownerIdentity = rawFields["소유자신원"].orEmpty()
            val template = InvestigationCase(
                year = normalizeDate(rawFields["의뢰일"].orEmpty()).take(4).toIntOrNull() ?: LocalDate.now().year,
                managementNo = normalizeManagement(rawFields["관리번호"].orEmpty()),
                requestDate = normalizeDate(rawFields["의뢰일"].orEmpty()),
                investigator = personName(stripLabels(rawFields["조사담당자"].orEmpty(), "조사담당자")),
                debtorName = personName(rawFields["채무자명"].orEmpty()),
                phone = normalizePhone(rawFields["전화번호"].orEmpty()),
                mobile = normalizePhone(rawFields["핸드폰번호"].orEmpty()),
                dueDate = normalizeDate(rawFields["완료요청일"].orEmpty()),
                investigationType = valueText(rawFields["조사구분"].orEmpty(), "조사구분"),
                loanType = valueText(rawFields["대출종류"].orEmpty(), "대출종류"),
                propertyType = normalizePropertyType(rawFields["물건종류"].orEmpty()),
                propertyAddress = normalizeAddress(rawFields["물건소재지"].orEmpty()),
                ownerName = personName(ownerIdentity),
                ownerResidentNo = normalizeResident(ownerIdentity),
                ownerPhone = normalizePhone(rawFields["소유자연락처"].orEmpty()),
                ownerAddress = normalizeAddress(rawFields["소유자주소"].orEmpty()),
                requestNotes = normalizeNotes(rawFields["기타요청사항"].orEmpty()),
                branch = valueText(rawFields["농협영업점"].orEmpty(), "농협영업점", "영업점"),
                requester = personName(stripLabels(rawFields["조사의뢰자"].orEmpty(), "조사의뢰자"))
            )

            val merged = mergeTemplateFirst(template, fallback)
            val score = qualityScore(merged)
            val rotationText = if (oriented.rotation == 0) "방향 정상" else "180° 자동보정"

            val diagnostic = buildString {
                append("--- 고정양식 셀별 OCR ---\n")
                rawFields.forEach { (k, v) -> append(k).append(" : ").append(v.replace('\n', ' ')).append('\n') }
                append("\n--- 자동 정리 결과 ---\n")
                append("관리번호 : ${merged.managementNo}\n")
                append("의뢰일 : ${merged.requestDate}\n")
                append("조사담당자 : ${merged.investigator}\n")
                append("채무자명 : ${merged.debtorName}\n")
                append("전화번호 : ${merged.phone}\n")
                append("핸드폰번호 : ${merged.mobile}\n")
                append("완료요청일 : ${merged.dueDate}\n")
                append("조사구분 : ${merged.investigationType}\n")
                append("대출종류 : ${merged.loanType}\n")
                append("물건종류 : ${merged.propertyType}\n")
                append("물건소재지 : ${merged.propertyAddress}\n")
                append("물건소유자 : ${merged.ownerName}\n")
                append("소유자주소 : ${merged.ownerAddress}\n")
                append("기타요청사항 : ${merged.requestNotes.replace('\n', ' ')}\n")
            }

            return OcrService.OcrResult(
                rawText = diagnostic,
                parsed = merged,
                normalized = true,
                preprocessMessage = "${normalized.message} / 고정양식 셀 OCR / $rotationText / 인식 품질 $score/17"
            )
        } finally {
            client.close()
        }
    }

    /** 전체 OCR 보조모드용. 고정 셀 결과가 비었을 때만 사용된다. */
    fun parseFallback(text: String): InvestigationCase {
        val lines = text.lines().map(::cleanLine).filter { it.isNotBlank() }
        fun after(vararg labels: String): String {
            for ((index, line) in lines.withIndex()) {
                val compactLine = compact(line)
                val hit = labels.firstOrNull { compactLine.contains(compact(it)) } ?: continue
                val pattern = hit.map { Regex.escape(it.toString()) }.joinToString("\\s*")
                val same = line.replaceFirst(Regex(".*?$pattern\\s*[:：]?\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (same.isNotBlank() && compact(same) != compact(line)) return same
                val next = lines.getOrNull(index + 1).orEmpty()
                if (next.isNotBlank()) return next
            }
            return ""
        }

        val dates = Regex("20\\d{2}[^\\n]{0,12}?\\d{1,2}[^\\n]{0,8}?\\d{1,2}")
            .findAll(text).mapNotNull { normalizeDate(it.value).takeIf(String::isNotBlank) }.distinct().toList()
        val requestDate = normalizeDate(after("의뢰일")).ifBlank { dates.firstOrNull().orEmpty() }
        val dueDate = normalizeDate(after("완료요청일")).ifBlank { dates.firstOrNull { it != requestDate }.orEmpty() }
        val phones = Regex("01\\d[- )]?\\d{3,4}[- ]?\\d{4}|0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}")
            .findAll(text.uppercase().replace('O','0').replace('I','1').replace('L','1'))
            .mapNotNull { normalizePhone(it.value).takeIf(String::isNotBlank) }.distinct().toList()
        val pairs = Regex("([가-힣]{2,6})\\s*\\(\\s*(\\d{6})[^)]*\\)").findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()

        val debtor = personName(after("채무자명")).ifBlank { pairs.firstOrNull()?.first.orEmpty() }
        val owner = personName(after("성명", "물건소유자")).ifBlank { pairs.getOrNull(1)?.first.orEmpty() }
        val propAddr = normalizeAddress(after("물건소재지")).ifBlank {
            lines.map(::normalizeAddress).filter { looksLikeAddress(it) }.maxByOrNull { it.length }.orEmpty()
        }

        return InvestigationCase(
            year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
            managementNo = normalizeManagement(after("관리번호")),
            requestDate = requestDate,
            investigator = personName(after("조사담당자")),
            debtorName = debtor,
            phone = normalizePhone(after("전화번호")).ifBlank { phones.getOrNull(0).orEmpty() },
            mobile = normalizePhone(after("핸드폰번호")).ifBlank { phones.firstOrNull { it.startsWith("010") }.orEmpty() },
            dueDate = dueDate,
            investigationType = valueText(after("조사구분"), "조사구분"),
            loanType = valueText(after("대출종류"), "대출종류"),
            propertyType = normalizePropertyType(after("물건종류")),
            propertyAddress = propAddr,
            ownerName = owner,
            ownerResidentNo = pairs.getOrNull(1)?.second?.let { "$it-" }.orEmpty(),
            ownerPhone = normalizePhone(after("연락처")).ifBlank { phones.drop(2).firstOrNull().orEmpty() },
            ownerAddress = normalizeAddress(after("소유자주소")),
            requestNotes = normalizeNotes(after("기타요청사항")),
            branch = valueText(after("농협영업점", "영업점"), "농협영업점", "영업점"),
            requester = personName(after("조사의뢰자"))
        )
    }

    private suspend fun chooseUpright(client: TextRecognizer, bitmap: Bitmap): Oriented {
        val base = recognizeText(client, bitmap)
        val baseScore = headerScore(base, bitmap.height)
        if (baseScore >= 5) return Oriented(bitmap, base, 0)
        val rotated = rotate180(bitmap)
        val second = recognizeText(client, rotated)
        val secondScore = headerScore(second, rotated.height)
        return if (secondScore > baseScore) Oriented(rotated, second, 180)
        else {
            if (!rotated.isRecycled) rotated.recycle()
            Oriented(bitmap, base, 0)
        }
    }

    private fun headerScore(text: Text, height: Int): Int {
        val targets = listOf("조사의뢰서", "의뢰일", "관리번호", "조사담당자", "대상자", "의뢰내용")
        var score = 0
        text.textBlocks.flatMap { it.lines }.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            if (box.centerY() > height * 0.48) return@forEach
            val c = compact(line.text)
            targets.forEach { if (c.contains(compact(it))) score++ }
        }
        return score
    }

    private suspend fun recognizeBox(client: TextRecognizer, source: Bitmap, spec: Box): String {
        val r = scaledBox(source, spec)
        val crop = Bitmap.createBitmap(source, r[0], r[1], r[2] - r[0], r[3] - r[1])
        val prepared = prepareForOcr(crop)
        return try { cleanMultiline(recognizeText(client, prepared).text) }
        finally {
            if (prepared !== crop && !prepared.isRecycled) prepared.recycle()
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun scaledBox(bitmap: Bitmap, b: Box): IntArray {
        val sx = bitmap.width / TEMPLATE_W
        val sy = bitmap.height / TEMPLATE_H
        val l = (b.x1 * sx).toInt().coerceIn(0, bitmap.width - 2)
        val t = (b.y1 * sy).toInt().coerceIn(0, bitmap.height - 2)
        val r = (b.x2 * sx).toInt().coerceIn(l + 1, bitmap.width)
        val bot = (b.y2 * sy).toInt().coerceIn(t + 1, bitmap.height)
        return intArrayOf(l, t, r, bot)
    }

    private fun prepareForOcr(src: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return src
        val rgba = Mat(); val gray = Mat(); val enhanced = Mat(); val blurred = Mat(); val sharp = Mat()
        val resized = Mat(); val bordered = Mat(); val outRgba = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, enhanced)
            Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 0.9)
            Core.addWeighted(enhanced, 1.35, blurred, -0.35, 0.0, sharp)
            val scale = max(1.0, 190.0 / src.height.toDouble()).coerceAtMost(2.8)
            Imgproc.resize(sharp, resized, Size((src.width * scale).coerceAtLeast(1.0), (src.height * scale).coerceAtLeast(1.0)), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Core.copyMakeBorder(resized, bordered, 28, 28, 28, 28, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))
            Imgproc.cvtColor(bordered, outRgba, Imgproc.COLOR_GRAY2RGBA)
            val out = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)
            return out
        } finally {
            rgba.release(); gray.release(); enhanced.release(); blurred.release(); sharp.release(); resized.release(); bordered.release(); outRgba.release()
        }
    }

    private suspend fun recognizeText(client: TextRecognizer, bitmap: Bitmap): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    private fun rotate180(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun mergeTemplateFirst(t: InvestigationCase, f: InvestigationCase): InvestigationCase {
        fun p(a: String, b: String) = a.ifBlank { b }
        return t.copy(
            year = p(t.requestDate, f.requestDate).take(4).toIntOrNull() ?: t.year,
            managementNo = p(t.managementNo, f.managementNo), requestDate = p(t.requestDate, f.requestDate),
            investigator = p(t.investigator, f.investigator), debtorName = p(t.debtorName, f.debtorName),
            phone = p(t.phone, f.phone), mobile = p(t.mobile, f.mobile), dueDate = p(t.dueDate, f.dueDate),
            investigationType = p(t.investigationType, f.investigationType), loanType = p(t.loanType, f.loanType),
            propertyType = p(t.propertyType, f.propertyType), propertyAddress = p(t.propertyAddress, f.propertyAddress),
            ownerName = p(t.ownerName, f.ownerName), ownerResidentNo = p(t.ownerResidentNo, f.ownerResidentNo),
            ownerPhone = p(t.ownerPhone, f.ownerPhone), ownerAddress = p(t.ownerAddress, f.ownerAddress),
            requestNotes = p(t.requestNotes, f.requestNotes), branch = p(t.branch, f.branch), requester = p(t.requester, f.requester)
        )
    }

    private fun qualityScore(c: InvestigationCase): Int = listOf(
        c.managementNo, c.requestDate, c.investigator, c.debtorName, c.phone, c.mobile, c.dueDate,
        c.investigationType, c.loanType, c.propertyType, c.propertyAddress, c.ownerName, c.ownerResidentNo,
        c.ownerPhone, c.ownerAddress, c.requestNotes, c.branch
    ).count { it.isNotBlank() }

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

    private fun normalizePhone(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val m = Regex("01\\d[- )]?\\d{3,4}[- ]?\\d{4}|0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}").find(fixed)
        val d = (m?.value ?: fixed).filter(Char::isDigit)
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0,3)}-${d.substring(3,7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "02-${d.substring(2,6)}-${d.substring(6)}"
            d.length == 10 -> "${d.substring(0,3)}-${d.substring(3,6)}-${d.substring(6)}"
            d.length == 9 && d.startsWith("02") -> "02-${d.substring(2,5)}-${d.substring(5)}"
            else -> ""
        }
    }

    private fun normalizeManagement(value: String): String {
        val s = stripLabels(value, "관리번호").replace(Regex("[^가-힣A-Za-z0-9-]"), "")
        return Regex("[가-힣A-Za-z]{0,10}20\\d{4,6}-?\\d{3,8}").find(s)?.value ?: s.takeIf { it.length in 6..40 }.orEmpty()
    }

    private fun personName(value: String): String {
        val s = stripLabels(value, "성명", "채무자명", "물건소유자", "조사담당자", "조사의뢰자")
        return Regex("[가-힣]{2,6}").find(s)?.value.orEmpty()
    }

    private fun normalizeResident(value: String): String {
        val fixed = value.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        val m = Regex("(\\d{6})\\s*[-–]?\\s*([1-4*][0-9*]{0,6})?").find(fixed) ?: return ""
        val tail = m.groupValues.getOrNull(2).orEmpty()
        return if (tail.isBlank()) "${m.groupValues[1]}-" else "${m.groupValues[1]}-$tail"
    }

    private fun normalizeAddress(value: String): String {
        var s = stripLabels(value, "물건소재지", "소유자주소").replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^\\d{5,6}\\s+"), "")
        return s.takeIf { it.length >= 6 }.orEmpty()
    }

    private fun looksLikeAddress(value: String): Boolean = value.length >= 8 &&
        Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)").containsMatchIn(value)

    private fun normalizePropertyType(value: String): String {
        val s = valueText(value, "물건종류")
        val known = listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
        return known.firstOrNull { s.contains(it) }.orEmpty().ifBlank { s.take(30) }
    }

    private fun normalizeNotes(value: String): String = value.lines().map(::cleanLine).filter { it.isNotBlank() }.joinToString("\n").take(700)

    private fun valueText(value: String, vararg labels: String): String = stripLabels(value, *labels)
        .replace(Regex("^[○Oo0 :：|·-]+"), "").replace(Regex("\\s+"), " ").trim().take(180)

    private fun stripLabels(value: String, vararg labels: String): String {
        var out = value
        labels.forEach { label ->
            val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            out = out.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
        }
        return out.trim(' ', ':', '：', '|', '·', '-')
    }

    private fun cleanMultiline(value: String): String = value.lines().map(::cleanLine).filter { it.isNotBlank() }.joinToString("\n")
    private fun cleanLine(value: String): String = value.replace(Regex("[\\t ]+"), " ").trim()
    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")
}
