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

/**
 * 현재 사용하는 조사의뢰서 고정 양식 전용 OCR.
 *
 * v0.8까지는 문서 전체 OCR 결과에서 라벨과 값을 다시 조합했기 때문에
 * 표의 여러 행/열이 한 줄로 섞이는 사진에서 오인식이 컸다.
 * 이 클래스는 문서를 A4로 원근 보정한 뒤 실제 인쇄 양식의 값 셀만 잘라
 * 각각 독립적으로 OCR한다. 따라서 임차인 표/전화번호 라벨 등 다른 셀의
 * 글자가 값에 섞이지 않는다.
 *
 * 좌표는 저장되는 원본 이미지가 아니라 OCR용 2480x3508 정규화 Bitmap 기준이다.
 * 원본 증거사진은 변경하지 않는다.
 */
object FixedTemplateOcr {
    private const val TEMPLATE_W = 2480.0
    private const val TEMPLATE_H = 3508.0

    private data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private data class Oriented(val bitmap: Bitmap, val fullText: Text, val rotation: Int)

    private val boxes = linkedMapOf(
        // 상단
        "의뢰일" to Box(650, 175, 1900, 315),
        "관리번호" to Box(235, 315, 1280, 425),
        "조사담당자" to Box(235, 405, 980, 515),

        // 1. 대상자 - 표 선 안쪽 값 영역만 사용
        "채무자명" to Box(480, 915, 815, 995),
        "전화번호" to Box(1160, 915, 1495, 995),
        "핸드폰번호" to Box(1845, 915, 2360, 995),
        "완료요청일" to Box(480, 1020, 815, 1098),

        // 2. 의뢰 내용
        "조사구분" to Box(480, 1275, 1440, 1348),
        "대출종류" to Box(1780, 1275, 2370, 1348),
        "물건종류" to Box(480, 1375, 1440, 1450),
        "물건소재지" to Box(480, 1480, 2370, 1558),
        "소유자신원" to Box(795, 1585, 1440, 1665),
        "소유자연락처" to Box(1780, 1585, 2370, 1665),
        "소유자주소" to Box(480, 1695, 2370, 1772),

        // 3. 기타요청사항 및 하단
        "기타요청사항" to Box(165, 2550, 2370, 2818),
        "농협영업점" to Box(900, 2940, 2200, 3045),
        "조사의뢰자" to Box(900, 3050, 2200, 3165)
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrService.OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)

        // 문서 경계를 못 잡은 사진에 고정 좌표를 쓰면 오히려 잘못된 값이 나온다.
        if (!normalized.documentDetected || normalized.bitmap.width < 2000 || normalized.bitmap.height < 2800) {
            return OcrService.recognizeCase(context, uri)
        }

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val oriented = chooseUpright(client, normalized.bitmap)
            val fallback = OcrService.parse(oriented.fullText.text)
            val rawFields = linkedMapOf<String, String>()

            for ((name, box) in boxes) {
                rawFields[name] = recognizeBox(client, oriented.bitmap, box)
            }

            val requestDate = normalizeDate(rawFields["의뢰일"].orEmpty())
            val managementNo = normalizeManagement(rawFields["관리번호"].orEmpty())
            val investigator = personName(stripLabels(rawFields["조사담당자"].orEmpty(), "조사담당자"))

            val debtorName = personName(rawFields["채무자명"].orEmpty())
            val phone = normalizePhone(rawFields["전화번호"].orEmpty())
            val mobile = normalizePhone(rawFields["핸드폰번호"].orEmpty())
            val dueDate = normalizeDate(rawFields["완료요청일"].orEmpty())

            val investigationType = valueText(rawFields["조사구분"].orEmpty(), "조사구분")
            val loanType = valueText(rawFields["대출종류"].orEmpty(), "대출종류")
            val propertyType = normalizePropertyType(rawFields["물건종류"].orEmpty())
            val propertyAddress = normalizeAddress(rawFields["물건소재지"].orEmpty())

            val ownerIdentity = rawFields["소유자신원"].orEmpty()
            val ownerName = personName(ownerIdentity)
            val ownerResident = normalizeResident(ownerIdentity)
            val ownerPhone = normalizePhone(rawFields["소유자연락처"].orEmpty())
            val ownerAddress = normalizeAddress(rawFields["소유자주소"].orEmpty())

            val notes = normalizeNotes(rawFields["기타요청사항"].orEmpty())
            val branch = valueText(rawFields["농협영업점"].orEmpty(), "농협영업점", "영업점")
            val requester = personName(stripLabels(rawFields["조사의뢰자"].orEmpty(), "조사의뢰자"))

            val template = InvestigationCase(
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
                ownerResidentNo = ownerResident,
                ownerPhone = ownerPhone,
                ownerAddress = ownerAddress,
                requestNotes = notes,
                branch = branch,
                requester = requester
            )

            // 고정 셀 결과가 우선. 셀에서 정말 못 읽은 항목만 전체 문서 OCR로 보완한다.
            val merged = mergeTemplateFirst(template, fallback)
            val score = qualityScore(merged)
            val rotationText = if (oriented.rotation == 0) "방향 정상" else "180° 자동보정"

            val diagnostic = buildString {
                append("--- 고정양식 셀별 OCR ---\n")
                rawFields.forEach { (k, v) ->
                    append(k).append(" : ").append(v.replace('\n', ' ')).append('\n')
                }
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

    /** 문서가 거꾸로 촬영된 경우에만 180도 비교한다. 90/270은 EXIF 단계에서 처리한다. */
    private suspend fun chooseUpright(client: TextRecognizer, bitmap: Bitmap): Oriented {
        val base = recognizeText(client, bitmap)
        val baseScore = headerScore(base, bitmap.height)
        if (baseScore >= 5) return Oriented(bitmap, base, 0)

        val rotated = rotate180(bitmap)
        val second = recognizeText(client, rotated)
        val secondScore = headerScore(second, rotated.height)
        return if (secondScore > baseScore) {
            Oriented(rotated, second, 180)
        } else {
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
        val rect = scaledBox(source, spec)
        val crop = Bitmap.createBitmap(source, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        val prepared = prepareForOcr(crop)
        return try {
            cleanMultiline(recognizeText(client, prepared).text)
        } finally {
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

    /** 셀의 글자만 대비를 올리고 확대한다. 이 Bitmap은 저장하지 않는다. */
    private fun prepareForOcr(src: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return src
        val rgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        val blurred = Mat()
        val sharp = Mat()
        val resized = Mat()
        val bordered = Mat()
        val outRgba = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)
            Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 0.9)
            Core.addWeighted(enhanced, 1.35, blurred, -0.35, 0.0, sharp)

            val scale = max(1.0, 190.0 / src.height.toDouble()).coerceAtMost(2.8)
            Imgproc.resize(
                sharp,
                resized,
                Size((src.width * scale).coerceAtLeast(1.0), (src.height * scale).coerceAtLeast(1.0)),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC
            )
            Core.copyMakeBorder(resized, bordered, 28, 28, 28, 28, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))
            Imgproc.cvtColor(bordered, outRgba, Imgproc.COLOR_GRAY2RGBA)
            val out = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)
            return out
        } finally {
            rgba.release(); gray.release(); enhanced.release(); blurred.release(); sharp.release()
            resized.release(); bordered.release(); outRgba.release()
        }
    }

    private suspend fun recognizeText(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun rotate180(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun mergeTemplateFirst(t: InvestigationCase, f: InvestigationCase): InvestigationCase {
        fun pick(primary: String, fallback: String) = primary.ifBlank { fallback }
        return t.copy(
            year = pick(t.requestDate, f.requestDate).take(4).toIntOrNull() ?: t.year,
            managementNo = pick(t.managementNo, f.managementNo),
            requestDate = pick(t.requestDate, f.requestDate),
            investigator = pick(t.investigator, f.investigator),
            debtorName = pick(t.debtorName, f.debtorName),
            phone = pick(t.phone, f.phone),
            mobile = pick(t.mobile, f.mobile),
            dueDate = pick(t.dueDate, f.dueDate),
            investigationType = pick(t.investigationType, f.investigationType),
            loanType = pick(t.loanType, f.loanType),
            propertyType = pick(t.propertyType, f.propertyType),
            propertyAddress = pick(t.propertyAddress, f.propertyAddress),
            ownerName = pick(t.ownerName, f.ownerName),
            ownerResidentNo = pick(t.ownerResidentNo, f.ownerResidentNo),
            ownerPhone = pick(t.ownerPhone, f.ownerPhone),
            ownerAddress = pick(t.ownerAddress, f.ownerAddress),
            requestNotes = pick(t.requestNotes, f.requestNotes),
            branch = pick(t.branch, f.branch),
            requester = pick(t.requester, f.requester)
        )
    }

    private fun qualityScore(c: InvestigationCase): Int = listOf(
        c.managementNo, c.requestDate, c.investigator, c.debtorName, c.phone, c.mobile, c.dueDate,
        c.investigationType, c.loanType, c.propertyType, c.propertyAddress, c.ownerName,
        c.ownerResidentNo, c.ownerPhone, c.ownerAddress, c.requestNotes, c.branch
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
        val s = stripLabels(value, "관리번호")
            .replace(Regex("[^가-힣A-Za-z0-9-]"), "")
        val m = Regex("[가-힣A-Za-z]{0,10}20\\d{4,6}-?\\d{3,8}").find(s)?.value
        return m?.let { if ('-' in it) it else it } ?: s.takeIf { it.length in 6..40 }.orEmpty()
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
        var s = stripLabels(value, "물건소재지", "소유자주소")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^\\d{5,6}\\s+"), "")
        return s.takeIf { it.length >= 6 }.orEmpty()
    }

    private fun normalizePropertyType(value: String): String {
        val s = valueText(value, "물건종류")
        val known = listOf("아파트", "연립주택", "다세대주택", "단독주택", "다가구주택", "오피스텔", "상가", "공장", "토지", "주택")
        return known.firstOrNull { s.contains(it) }.orEmpty().ifBlank { s.take(30) }
    }

    private fun normalizeNotes(value: String): String = value.lines()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(700)

    private fun valueText(value: String, vararg labels: String): String =
        stripLabels(value, *labels)
            .replace(Regex("^[○Oo0 :：|·-]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)

    private fun stripLabels(value: String, vararg labels: String): String {
        var out = value
        labels.forEach { label ->
            val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            out = out.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
        }
        return out.trim(' ', ':', '：', '|', '·', '-')
    }

    private fun cleanMultiline(value: String): String = value.lines()
        .map { it.replace(Regex("[\\t ]+"), " ").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")
}
