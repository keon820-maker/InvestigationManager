package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val whole = recognizeBitmap(normalized.bitmap)
        val fields = recognizeTemplateFields(normalized.bitmap)
        val parsed = parse(whole, fields)
        return OcrResult(whole, parsed, normalized.documentDetected, normalized.message)
    }

    suspend fun recognize(context: Context, uri: Uri): String = recognizeCase(context, uri).rawText

    private suspend fun recognizeBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { c ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        client.process(image)
            .addOnSuccessListener { result ->
                client.close()
                if (c.isActive) c.resume(result.text)
            }
            .addOnFailureListener { error ->
                client.close()
                if (c.isActive) c.resumeWithException(error)
            }
    }

    private suspend fun recognizeTemplateFields(bitmap: Bitmap): Map<String, String> {
        data class Zone(val key: String, val l: Float, val t: Float, val r: Float, val b: Float)
        val zones = listOf(
            Zone("requestDate", .30f, .055f, .70f, .105f),
            Zone("managementNo", .05f, .115f, .48f, .175f),
            Zone("investigator", .05f, .145f, .58f, .205f),
            Zone("debtorName", .08f, .235f, .32f, .285f),
            Zone("phone", .31f, .235f, .60f, .285f),
            Zone("mobile", .59f, .235f, .93f, .285f),
            Zone("dueDate", .08f, .280f, .34f, .325f),
            Zone("investigationType", .08f, .355f, .52f, .405f),
            Zone("loanType", .56f, .355f, .93f, .405f),
            Zone("propertyType", .08f, .395f, .43f, .445f),
            Zone("propertyAddress", .08f, .435f, .93f, .490f),
            Zone("owner", .08f, .475f, .93f, .535f),
            Zone("ownerAddress", .08f, .515f, .93f, .565f),
            Zone("requestNotes", .06f, .705f, .94f, .825f),
            Zone("footer", .30f, .835f, .86f, .965f)
        )
        val out = linkedMapOf<String, String>()
        for (z in zones) {
            val left = (bitmap.width * z.l).toInt().coerceIn(0, bitmap.width - 1)
            val top = (bitmap.height * z.t).toInt().coerceIn(0, bitmap.height - 1)
            val right = (bitmap.width * z.r).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (bitmap.height * z.b).toInt().coerceIn(top + 1, bitmap.height)
            val rect = Rect(left, top, right, bottom)
            if (rect.width() <= 5 || rect.height() <= 5) continue
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
            val text = runCatching { recognizeBitmap(crop) }.getOrDefault("")
            out[z.key] = clean(text)
            crop.recycle()
        }
        return out
    }

    fun parse(text: String, fields: Map<String, String> = emptyMap()): InvestigationCase {
        val lines = text.lines().map { clean(it) }.filter { it.isNotBlank() }

        fun one(vararg labels: String): String {
            for (i in lines.indices) for (label in labels) {
                val compactLine = lines[i].replace(" ", "")
                val compactLabel = label.replace(" ", "")
                if (compactLine.contains(compactLabel)) {
                    val inline = lines[i].substringAfter(label, "").replace(Regex("^[ :：]+"), "").trim()
                    if (inline.isNotBlank()) return inline
                    if (i + 1 < lines.size) return lines[i + 1]
                }
            }
            return ""
        }

        fun field(key: String, vararg labels: String): String =
            fields[key].orEmpty().takeIf { it.isNotBlank() } ?: one(*labels)

        fun normalizeDate(value: String): String {
            val m = Regex("(20\\d{2})[^0-9]?(\\d{1,2})[^0-9]?(\\d{1,2})").find(value) ?: return ""
            return "%04d-%02d-%02d".format(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()
            )
        }

        fun dateAfter(label: String): String {
            val m = Regex("${Regex.escape(label)}[^0-9]*(20\\d{2})[-년 .]*(\\d{1,2})[-월 .]*(\\d{1,2})").find(text)
                ?: return ""
            return "%04d-%02d-%02d".format(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()
            )
        }

        fun phone(v: String): String {
            val corrected = v.uppercase()
                .replace('O', '0').replace('I', '1').replace('L', '1')
                .replace(Regex("[^0-9-]"), "")
            val digits = corrected.filter { it.isDigit() }
            return when (digits.length) {
                11 -> "${digits.substring(0,3)}-${digits.substring(3,7)}-${digits.substring(7)}"
                10 -> if (digits.startsWith("02")) "02-${digits.substring(2,6)}-${digits.substring(6)}"
                else "${digits.substring(0,3)}-${digits.substring(3,6)}-${digits.substring(6)}"
                9 -> if (digits.startsWith("02")) "02-${digits.substring(2,5)}-${digits.substring(5)}" else corrected
                else -> corrected
            }
        }

        fun stripLabels(v: String, vararg labels: String): String {
            var s = clean(v)
            labels.forEach { s = s.replace(it, "", ignoreCase = true) }
            return s.replace(Regex("^[ :：|]+"), "").trim()
        }

        val requestDate = normalizeDate(fields["requestDate"].orEmpty()).ifBlank { dateAfter("의뢰일") }
        val dueDate = normalizeDate(fields["dueDate"].orEmpty()).ifBlank { dateAfter("완료요청일") }
        val management = stripLabels(field("managementNo", "관리번호"), "관리번호")
        val address = stripLabels(field("propertyAddress", "물건 소재지", "물건소재지"), "물건소재지", "물건 소재지")

        val ownerZone = fields["owner"].orEmpty()
        val ownerName = one("물건 소유자", "물건소유자").ifBlank {
            ownerZone.substringBefore("주민번호").substringBefore("연락처").replace("물건소유자", "").trim()
        }
        val ownerResident = Regex("\\d{6}[- ]?[1-4*]?[0-9*]{0,6}").find(ownerZone)?.value.orEmpty()
        val ownerPhone = Regex("(?:0\\d{1,2}[- ]?)?\\d{3,4}[- ]?\\d{4}").find(ownerZone)?.value.orEmpty()

        val footer = fields["footer"].orEmpty()
        val branch = one("농협영업점", "영업점").ifBlank {
            Regex("영업점[^:：]*[:：]?\\s*([^\\n]+)").find(footer)?.groupValues?.getOrNull(1).orEmpty()
        }
        val requester = one("조사의뢰자").ifBlank {
            Regex("조사의뢰자[^:：]*[:：]?\\s*([^\\n]+)").find(footer)?.groupValues?.getOrNull(1).orEmpty()
        }

        return InvestigationCase(
            year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
            managementNo = management,
            requestDate = requestDate,
            investigator = stripLabels(field("investigator", "조사담당자"), "조사담당자"),
            debtorName = stripLabels(field("debtorName", "채무자 명", "채무자명"), "채무자명", "채무자 명"),
            phone = phone(stripLabels(field("phone", "전화번호"), "전화번호")),
            mobile = phone(stripLabels(field("mobile", "핸드폰번호"), "핸드폰번호")),
            dueDate = dueDate,
            investigationType = stripLabels(field("investigationType", "조사구분"), "조사구분"),
            loanType = stripLabels(field("loanType", "대출종류"), "대출종류"),
            propertyType = stripLabels(field("propertyType", "물건종류"), "물건종류"),
            propertyAddress = address,
            ownerName = ownerName,
            ownerResidentNo = ownerResident.ifBlank { one("주민번호") },
            ownerPhone = phone(ownerPhone.ifBlank { one("연락처") }),
            ownerAddress = stripLabels(field("ownerAddress", "소유자 주소"), "소유자 주소"),
            requestNotes = stripLabels(field("requestNotes", "기타요청사항"), "기타요청사항"),
            branch = branch,
            requester = requester
        )
    }

    private fun clean(s: String): String = s
        .replace('｜', '|')
        .replace(Regex("[\\t ]+"), " ")
        .trim()
}
