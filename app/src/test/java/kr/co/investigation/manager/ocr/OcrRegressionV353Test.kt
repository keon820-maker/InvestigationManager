package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRegressionV353Test {
    @Test
    fun investigationTypeKeepsOnSiteQualifier() {
        val base = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(
                year = 2026,
                investigationType = "임대차조사(현장조사)"
            ),
            normalized = true,
            preprocessMessage = ""
        )

        val fixed = CommonResultRepair.repair(base).parsed.investigationType
        assertEquals("임대차조사(현장조사)", fixed)
    }

    @Test
    fun investigationTypeStillRepairsCommonOcrTypoWithoutDroppingQualifier() {
        val base = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(
                year = 2026,
                investigationType = "임대차조시(현장조시)"
            ),
            normalized = true,
            preprocessMessage = ""
        )

        val fixed = CommonResultRepair.repair(base).parsed.investigationType
        assertEquals("임대차조사(현장조사)", fixed)
    }
}
