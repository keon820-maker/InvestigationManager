package kr.co.investigation.manager.ocr

import android.content.Context
import android.net.Uri
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate

/** OCR 진입점. */
object OcrService {
    data class OcrResult(
        val rawText: String,
        val parsed: InvestigationCase,
        val normalized: Boolean,
        val preprocessMessage: String
    )

    suspend fun recognizeCase(context: Context, uri: Uri): OcrResult {
        val normalizedDocument = DocumentNormalizer.normalize(context, uri)
        return try {
            recognizeCase(normalizedDocument)
        } finally {
            if (!normalizedDocument.bitmap.isRecycled) normalizedDocument.bitmap.recycle()
        }
    }

    private suspend fun recognizeCase(normalizedDocument: DocumentNormalizer.Result): OcrResult {
        val base = AdaptiveOcr.recognizeCase(normalizedDocument)

        if (looksLikeAppScreenshot(base.rawText)) {
            return OcrResult(
                rawText = buildString {
                    append("--- 선택 이미지 오류 v0.16 ---\n")
                    append("조사의뢰서 원본 사진이 아니라 앱 화면 캡처로 판단되었습니다.\n")
                    append("갤러리에서 실제 종이 조사의뢰서 사진을 다시 선택하세요.\n\n")
                    append(base.rawText)
                },
                parsed = InvestigationCase(year = LocalDate.now().year),
                normalized = false,
                preprocessMessage = "선택 오류: 앱 화면 캡처가 선택되었습니다. 실제 조사의뢰서 원본 사진을 다시 선택하세요."
            )
        }

        val footer = FooterOcrRepair.repair(normalizedDocument, base)
        val notes = NotesOcrRepair.repair(normalizedDocument, footer)
        val common = CommonResultRepair.repair(notes)
        // 조사담당자 영역은 고정 로컬 프로필을 사용하므로 추가 OCR 패스에서 완전히 제외한다.
        val structured = StructuredFieldOcrRepair.repair(normalizedDocument, common)
        val targetTenant = TargetTenantOcrRepair.repair(normalizedDocument, structured)
        val tenants = TenantResultSanitizer.repair(targetTenant)
        val final = FinalOcrRepairV26.repair(normalizedDocument, tenants)
        return excludeInvestigator(NotesTypoRepairV29.repair(final))
    }

    private fun looksLikeAppScreenshot(text: String): Boolean {
        val markers = listOf(
            "OCR 조사의뢰서 등록",
            "검수 완료 및 저장",
            "OCR 원문 보기",
            "OCR 원문 숨기기",
            "OCR 원문(진단용)",
            "자동인식 결과",
            "물건소재지 (지도 기준)"
        )
        return markers.count { text.contains(it, ignoreCase = true) } >= 2
    }

    suspend fun recognize(context: Context, uri: Uri): String = recognizeCase(context, uri).rawText

    fun parse(text: String): InvestigationCase = FixedTemplateOcr.parseFallback(text)

    private fun excludeInvestigator(result: OcrResult): OcrResult = result.copy(
        parsed = result.parsed.copy(
            investigator = "",
            investigatorPhone = "",
            investigatorFax = ""
        ),
        rawText = OcrFieldNormalizer.redactInvestigatorSection(result.rawText),
        preprocessMessage = result.preprocessMessage + " / 조사담당자 OCR 제외"
    )
}
