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
        // 실제 종이 외곽이 잘 잡혀도 인쇄 위치/여백 차이 때문에 고정 셀 좌표가 밀릴 수 있다.
        // 모든 OCR/보정 패스가 동일한 중앙표 기준 bitmap을 사용하도록 마지막으로 한 번 정렬한다.
        val alignedDocument = TemplateAnchorNormalizer.realign(normalizedDocument)
        return try {
            val base = AdaptiveOcr.recognizeCase(alignedDocument)

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

            val footer = FooterOcrRepair.repair(alignedDocument, base)
            val notes = NotesOcrRepair.repair(alignedDocument, footer)
            val common = CommonResultRepair.repair(notes)
            // 조사담당자 영역은 고정 로컬 프로필을 사용하므로 추가 OCR 패스에서 완전히 제외한다.
            val structured = StructuredFieldOcrRepair.repair(alignedDocument, common)
            val targetTenant = TargetTenantOcrRepair.repair(alignedDocument, structured)
            val tenants = TenantResultSanitizer.repair(targetTenant)
            val final = FinalOcrRepairV26.repair(alignedDocument, tenants)
            val notesFixed = NotesTypoRepairV29.repair(final)
            val addressFixed = AddressTypoRepairV355.repair(notesFixed)
            // 좌표 보정이 잘못된 표를 잡아 한 행씩 밀린 경우에는 마지막에 전체 페이지 라벨 좌표로 복구한다.
            val spatialRescued = SpatialRescueRepairV357.repair(alignedDocument, addressFixed)
            excludeInvestigator(spatialRescued)
        } finally {
            if (alignedDocument.bitmap !== normalizedDocument.bitmap && !alignedDocument.bitmap.isRecycled) {
                alignedDocument.bitmap.recycle()
            }
        }
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
