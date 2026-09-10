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
        // A complete verified grid needs no second warp/Hough pass. This also avoids
        // resampling legible text after the central table has already been aligned.
        GridFormOcr.recognize(normalizedDocument)?.let { return it }
        val alignedDocument = TemplateAnchorNormalizer.realign(normalizedDocument)
        return try {
            // Once cell ownership is verified, no later regex/anchor repair may replace its blanks.
            GridFormOcr.recognize(alignedDocument)?.let { return it }
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
            val structured = StructuredFieldOcrRepair.repair(alignedDocument, common)
            val targetTenant = TargetTenantOcrRepair.repair(alignedDocument, structured)
            val tenants = TenantResultSanitizer.repair(targetTenant)
            val final = FinalOcrRepairV26.repair(alignedDocument, tenants)
            val notesFixed = NotesTypoRepairV29.repair(final)
            val addressFixed = AddressTypoRepairV355.repair(notesFixed)
            val missingCoreRecovered = MissingCoreFieldRecoveryV3512.repair(alignedDocument, addressFixed)
            val spatialRescued = SpatialRescueRepairV357.repair(alignedDocument, missingCoreRecovered)
            val leakFixed = SpatialLeakRepairV358.repair(alignedDocument, spatialRescued)
            val contactsRecovered = ContactRecoveryRepairV359.repair(alignedDocument, leakFixed)
            val finalNotes = NotesTypoRepairV29.repair(contactsRecovered)
            val finalAddresses = AddressTypoRepairV355.repair(finalNotes)
            val consistent = FinalResultConsistencyV3512.repair(finalAddresses)
            val fixedCellContacts = TemplateCellContactRepairV3516.repair(alignedDocument, consistent)
            val roleMappedContacts = ContactRoleMappingRepairV3517.repair(alignedDocument, fixedCellContacts)

            // v0.35.18: 고정 x/y 좌표에만 의존하지 않고, 이미 정확히 읽힌 채무자/소유자 이름의
            // 실제 OCR 좌표를 앵커로 삼아 해당 행 전체를 다시 읽는다. 사진별 원근 잔차가 있어도
            // 채무자·소유자·임차인의 번호 역할을 마지막 단계에서 다시 검증한다.
            val anchorMappedContacts = AnchorContactRepairV3518.repair(alignedDocument, roleMappedContacts)

            val finalTenants = TenantResultSanitizer.repair(anchorMappedContacts)
            excludeInvestigator(finalTenants)
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
