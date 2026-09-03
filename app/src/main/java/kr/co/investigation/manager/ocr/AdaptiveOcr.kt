package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.11 OCR 라우터.
 *
 * 1) 기존 고정 A4 정렬 + 셀 crop이 정상 동작하면 그대로 사용한다.
 * 2) 종이/표 외곽 검출이 실패하면 전체 OCR 문자열 순서를 파싱하지 않는다.
 *    ML Kit boundingBox를 이용한 SpatialFormParser로 같은 행의 셀을 X좌표 순서로 재구성한다.
 */
object AdaptiveOcr {
    suspend fun recognizeCase(context: Context, uri: Uri): OcrService.OcrResult {
        val primary = FixedTemplateOcr.recognizeCase(context, uri)
        if (primary.normalized) return primary

        // 정렬 실패일 때만 두 번째 패스를 수행한다. DocumentNormalizer의 실패 결과 bitmap은
        // EXIF 회전이 적용된 원본 작업용 복사본이며 증거 원본 파일에는 손대지 않는다.
        val source = DocumentNormalizer.normalize(context, uri)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val full = recognizeText(client, source.bitmap)
            val spatial = SpatialFormParser.parse(full, source.bitmap.width, source.bitmap.height)
            val fallback = FixedTemplateOcr.parseFallback(full.text)
            val merged = mergeValidated(spatial.parsed, fallback)
            val score = qualityScore(merged)

            if (score >= 5) {
                OcrService.OcrResult(
                    rawText = buildString {
                        append(spatial.diagnostic)
                        append("\n--- 전체 OCR 원문(참고) ---\n")
                        append(full.text)
                    },
                    parsed = merged,
                    normalized = false,
                    preprocessMessage = "${source.message} / 라벨 좌표기반 표 OCR / 인식 품질 $score/17"
                )
            } else {
                primary.copy(
                    preprocessMessage = primary.preprocessMessage + " / 위치기반 보조도 품질 부족"
                )
            }
        } finally {
            client.close()
        }
    }

    private suspend fun recognizeText(
        client: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    private fun mergeValidated(s: InvestigationCase, f: InvestigationCase): InvestigationCase {
        fun validManagement(v: String) = Regex("[가-힣A-Za-z]{0,10}20\\d{4}-?\\d{3,8}").containsMatchIn(v)
        fun validDate(v: String) = Regex("20\\d{2}-\\d{2}-\\d{2}").matches(v)
        fun validPhone(v: String) = Regex("0\\d{1,2}-\\d{3,4}-\\d{4}").matches(v)
        fun validName(v: String) = Regex("[가-힣]{2,6}").matches(v)
        fun validAddress(v: String) = v.length >= 8 && Regex(
            "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+시|[가-힣]+군|[가-힣]+구|[가-힣]+로|[가-힣]+길|[가-힣]+동)"
        ).containsMatchIn(v)

        fun p(a: String, b: String, validator: (String) -> Boolean): String =
            a.ifBlank { b.takeIf(validator).orEmpty() }

        val requestDate = p(s.requestDate, f.requestDate, ::validDate)
        return s.copy(
            year = requestDate.take(4).toIntOrNull() ?: s.year,
            managementNo = p(s.managementNo, f.managementNo, ::validManagement),
            requestDate = requestDate,
            investigator = p(s.investigator, f.investigator, ::validName),
            debtorName = p(s.debtorName, f.debtorName, ::validName),
            phone = p(s.phone, f.phone, ::validPhone),
            mobile = p(s.mobile, f.mobile, ::validPhone),
            dueDate = p(s.dueDate, f.dueDate, ::validDate),
            investigationType = s.investigationType.ifBlank {
                f.investigationType.takeIf { it.contains("조사") && it.length <= 60 }.orEmpty()
            },
            loanType = s.loanType.ifBlank {
                f.loanType.takeIf { it.contains("대출") && it.length <= 50 }.orEmpty()
            },
            propertyType = s.propertyType.ifBlank {
                f.propertyType.takeIf { it.length in 2..20 }.orEmpty()
            },
            propertyAddress = p(s.propertyAddress, f.propertyAddress, ::validAddress),
            ownerName = p(s.ownerName, f.ownerName, ::validName),
            ownerResidentNo = s.ownerResidentNo.ifBlank {
                f.ownerResidentNo.takeIf { Regex("\\d{6}-.*").matches(it) }.orEmpty()
            },
            ownerPhone = p(s.ownerPhone, f.ownerPhone, ::validPhone),
            ownerAddress = p(s.ownerAddress, f.ownerAddress, ::validAddress),
            requestNotes = s.requestNotes.ifBlank { f.requestNotes },
            branch = s.branch.ifBlank { f.branch },
            requester = p(s.requester, f.requester, ::validName)
        )
    }

    private fun qualityScore(c: InvestigationCase): Int = listOf(
        c.managementNo, c.requestDate, c.investigator, c.debtorName, c.phone, c.mobile,
        c.dueDate, c.investigationType, c.loanType, c.propertyType, c.propertyAddress,
        c.ownerName, c.ownerResidentNo, c.ownerPhone, c.ownerAddress, c.requestNotes, c.branch
    ).count { it.isNotBlank() }
}
