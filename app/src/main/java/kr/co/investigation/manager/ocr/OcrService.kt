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

            // v0.35.17: 실기기에서 채무자 번호가 소유자 연락처로 들어간 사례를 역할별 셀 재OCR로 교정한다.
            // 기존 값이 비어 있을 때만 채우는 것이 아니라, 소유자 번호가 채무자 모바일과 같으면 교차누수로 보고 교체한다.
            val roleMappedContacts = ContactRoleMappingRepairV3517.repair(alignedDocument, fixedCellContacts)

            val finalTenants = TenantResultSanitizer.repair(roleMappedContacts)
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
