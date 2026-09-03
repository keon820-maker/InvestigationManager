package kr.co.investigation.manager.ocr

import android.content.Context
import android.net.Uri
import kr.co.investigation.manager.data.InvestigationCase

/** OCR 진입점. */
object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult =
        AdaptiveOcr.recognizeCase(context, uri)

    suspend fun recognize(context: Context, uri: Uri): String =
        recognizeCase(context, uri).rawText

    fun parse(text: String): InvestigationCase = FixedTemplateOcr.parseFallback(text)
}
