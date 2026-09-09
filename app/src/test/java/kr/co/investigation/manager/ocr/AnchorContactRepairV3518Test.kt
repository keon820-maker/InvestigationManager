package kr.co.investigation.manager.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorContactRepairV3518Test {
    @Test
    fun onlyMiddleTenantTableBandQualifiesAsTenantAnchor() {
        val h = 3508
        assertFalse(AnchorContactRepairV3518.isTenantBandY((h * 0.40).toInt(), h)) // owner row area
        assertTrue(AnchorContactRepairV3518.isTenantBandY((h * 0.52).toInt(), h))  // tenant table area
        assertFalse(AnchorContactRepairV3518.isTenantBandY((h * 0.78).toInt(), h)) // notes/footer area
    }
}
