package kr.co.investigation.manager.ocr

import android.content.Context
import android.net.Uri
import kr.co.investigation.manager.data.InvestigationCase

/**
 * OCR 진입점.
 * v0.9부터 현재 조사의뢰서가 거의 동일한 고정 양식이라는 전제에 맞춰
 * FixedTemplateOcr를 기본 엔진으로 사용한다.
 */
object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult =
        FixedTemplateOcr.recognizeCase(context, uri)

    suspend fun recognize(context: Context, uri: Uri): String =
        recognizeCase(context, uri).rawText

    fun parse(text: String): InvestigationCase = FixedTemplateOcr.parseFallback(text)
}
