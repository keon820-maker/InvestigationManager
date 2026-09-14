package kr.co.investigation.manager.ocr

/** Legacy fallback uses the same footer-only reader as the verified-grid route. */
object FooterOcrRepair {
    suspend fun repair(normalized: DocumentNormalizer.Result, base: OcrService.OcrResult): OcrService.OcrResult {
        val before = base.parsed
        val notes = cleanNotes(before.requestNotes)
        val existing = FooterFields.Values(
            FooterFields.branch(before.branch), FooterFields.requester(before.requester),
            GridCellValues.phone(before.branchPhone), GridCellValues.phone(before.branchFax)
        )
        if (existing.complete) return if (notes == before.requestNotes) base
            else base.copy(parsed = before.copy(requestNotes = notes))

        // No minimum image-size gate: small camera images are exactly the ones needing a
        // local enlarged read. Values still require an actual branch/requester footer label.
        val footer = GridFooterRecovery.read(normalized.bitmap, (normalized.bitmap.height * 0.68f).toInt())
        val recovered = footer.values
        val fixed = before.copy(
            branch = existing.branch.ifBlank { recovered.branch },
            requester = existing.requester.ifBlank { recovered.requester },
            branchPhone = existing.phone.ifBlank { recovered.phone },
            branchFax = existing.fax.ifBlank { recovered.fax },
            requestNotes = notes
        )
        return base.copy(
            parsed = fixed,
            sourceText = listOf(base.sourceText, footer.sourceText).filter(String::isNotBlank).joinToString("\n"),
            rawText = base.rawText + "\n\n--- 하단 영역 재검증 v0.35.32 ---\n" +
                "확인 필요: " + recovered.review.joinToString(),
            preprocessMessage = base.preprocessMessage + " / 하단 영역 확대 재검증"
        )
    }

    private fun cleanNotes(value: String): String = value
        .replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
        .replace(Regex("보증금\\s*[:：]\\s*0I\\b", RegexOption.IGNORE_CASE), "보증금:0")
        .replace(Regex("월임차료\\s*[:：]\\s*[oO]\\b"), "월임차료:0")
        .trim()
}
