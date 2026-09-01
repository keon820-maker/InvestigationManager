package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
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

object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    private val knownLabels = listOf(
        "관리번호", "조사담당자", "채무자명", "채무자 명", "전화번호", "핸드폰번호",
        "완료요청일", "조사구분", "대출종류", "물건종류", "물건소재지", "물건 소재지",
        "물건소유자", "물건 소유자", "주민번호", "연락처", "소유자주소", "소유자 주소",
        "기타요청사항", "농협영업점", "영업점", "조사의뢰자", "의뢰일"
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalized = DocumentNormalizer.normalize(context, uri)
        val result = recognizeText(normalized.bitmap)
        val parsed = parse(result.text)
        return OcrResult(
            rawText = result.text,
            parsed = parsed,
            normalized = normalized.documentDetected,
            preprocessMessage = normalized.message + " / 라벨 기준 OCR"
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

    fun parse(text: String): InvestigationCase {
        val lines = text.lines().map { clean(it) }.filter { it.isNotBlank() }

        fun compact(s: String) = s.replace(Regex("[\\s:：|·ㆍ]+"), "")

        fun looksLikeLabel(s: String): Boolean {
            val c = compact(s)
            return knownLabels.any { c.contains(compact(it)) }
        }

        fun afterLabel(label: String, vararg aliases: String): String {
            val all = listOf(label) + aliases
            for (i in lines.indices) {
                val line = lines[i]
                val compactLine = compact(line)
                val matched = all.firstOrNull { compactLine.contains(compact(it)) } ?: continue

                // 같은 OCR 라인 안에서 라벨 뒤의 실제 값을 먼저 사용한다.
                val pattern = Regex("${Regex.escape(matched)}\\s*[:：]?\\s*(.+)$")
                val same = pattern.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (same.isNotBlank() && !looksLikeLabel(same)) return same

                // 표 셀이 별도 라인으로 인식된 경우 바로 다음 1~2줄까지만 허용한다.
                for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                    val candidate = lines[j].trim()
                    if (candidate.isBlank()) continue
                    if (looksLikeLabel(candidate)) break
                    return candidate
                }
            }
            return ""
        }

        fun dateFrom(value: String): String {
            val normalized = value
                .replace('O', '0')
                .replace('o', '0')
            val m = Regex("(20\\d{2})[^0-9]{0,4}(\\d{1,2})[^0-9]{0,4}(\\d{1,2})").find(normalized)
                ?: return ""
            val y = m.groupValues[1].toIntOrNull() ?: return ""
            val mo = m.groupValues[2].toIntOrNull() ?: return ""
            val d = m.groupValues[3].toIntOrNull() ?: return ""
            if (mo !in 1..12 || d !in 1..31) return ""
            return "%04d-%02d-%02d".format(y, mo, d)
        }

        fun phoneFrom(value: String): String {
            val fixed = value.uppercase()
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')
            val digits = fixed.filter { it.isDigit() }
            if (digits.length !in 9..11) return ""
            return when (digits.length) {
                11 -> "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
                10 -> if (digits.startsWith("02")) {
                    "02-${digits.substring(2, 6)}-${digits.substring(6)}"
                } else {
                    "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
                }
                9 -> if (digits.startsWith("02")) {
                    "02-${digits.substring(2, 5)}-${digits.substring(5)}"
                } else ""
                else -> ""
            }
        }

        fun cleanValue(v: String, vararg labels: String): String {
            var out = clean(v)
            labels.forEach { out = out.replace(it, "", ignoreCase = true) }
            return out.replace(Regex("^[ :：|]+"), "").trim()
        }

        fun shortValue(v: String, max: Int = 40): String {
            val s = cleanValue(v)
            return if (s.length in 1..max && !looksLikeLabel(s)) s else ""
        }

        fun plausibleAddress(v: String): String {
            val s = cleanValue(v, "물건소재지", "물건 소재지")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (s.length < 6 || s.length > 180) return ""
            val addressHint = Regex("(특별시|광역시|특별자치|[가-힣]+도|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+동|[가-힣]+로|[가-힣]+길|번지)")
            return if (addressHint.containsMatchIn(s)) s else ""
        }

        fun residentNo(v: String): String {
            val m = Regex("\\d{6}\\s*[-]?\\s*[1-4*][0-9*]{0,6}").find(v) ?: return ""
            return m.value.replace(" ", "")
        }

        val requestDateRaw = afterLabel("의뢰일")
        val requestDate = dateFrom(requestDateRaw).ifBlank {
            val m = Regex("의뢰일[^0-9]*(20\\d{2})[^0-9]{0,4}(\\d{1,2})[^0-9]{0,4}(\\d{1,2})").find(text)
            if (m != null) dateFrom(m.value) else ""
        }
        val dueRaw = afterLabel("완료요청일")
        val dueDate = dateFrom(dueRaw)

        val management = cleanValue(afterLabel("관리번호"), "관리번호")
            .takeIf { it.length in 3..40 } ?: ""
        val investigator = shortValue(cleanValue(afterLabel("조사담당자"), "조사담당자"), 30)
        val debtor = shortValue(cleanValue(afterLabel("채무자명", "채무자 명"), "채무자명", "채무자 명"), 30)
        val phone = phoneFrom(afterLabel("전화번호"))
        val mobile = phoneFrom(afterLabel("핸드폰번호"))
        val investigationType = shortValue(cleanValue(afterLabel("조사구분"), "조사구분"), 40)
        val loanType = shortValue(cleanValue(afterLabel("대출종류"), "대출종류"), 40)
        val propertyType = shortValue(cleanValue(afterLabel("물건종류"), "물건종류"), 30)
        val address = plausibleAddress(afterLabel("물건소재지", "물건 소재지"))

        val ownerRaw = afterLabel("물건소유자", "물건 소유자")
        val ownerName = shortValue(
            ownerRaw.substringBefore("주민번호").substringBefore("연락처")
                .replace("물건소유자", "").replace("물건 소유자", ""),
            30
        )
        val ownerResident = residentNo(afterLabel("주민번호")).ifBlank { residentNo(ownerRaw) }
        val ownerPhone = phoneFrom(afterLabel("연락처")).ifBlank { phoneFrom(ownerRaw) }
        val ownerAddress = plausibleAddress(afterLabel("소유자 주소", "소유자주소"))

        val notes = cleanValue(afterLabel("기타요청사항"), "기타요청사항")
            .takeIf { it.length <= 300 } ?: ""
        val branch = shortValue(cleanValue(afterLabel("농협영업점", "영업점"), "농협영업점", "영업점"), 60)
        val requester = shortValue(cleanValue(afterLabel("조사의뢰자"), "조사의뢰자"), 40)

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
            propertyAddress = address,
            ownerName = ownerName,
            ownerResidentNo = ownerResident,
            ownerPhone = ownerPhone,
            ownerAddress = ownerAddress,
            requestNotes = notes,
            branch = branch,
            requester = requester
        )
    }

    private fun clean(s: String): String = s
        .replace('｜', '|')
        .replace(Regex("[\\t ]+"), " ")
        .trim()
}
