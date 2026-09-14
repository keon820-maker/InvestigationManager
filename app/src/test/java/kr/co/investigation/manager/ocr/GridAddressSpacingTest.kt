package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class GridAddressSpacingTest {
    @Test fun joinsSplitNumberedRoadSuffixWithoutChangingBuildingOrUnitNumbers() {
        assertEquals("테스트시 가상로21길 37 103동 2005호", GridCellValues.address(
            "12345 테스트시 가상로21 길 37 103동 2005호"))
        assertEquals("테스트시 가상로21번길 37", GridCellValues.address("테스트시 가상로21\n번길 37"))
    }

    @Test fun separateRoadAndBuildingNumbersRemainSeparate() {
        assertEquals("테스트시 가상로 21 103동 2005호", GridCellValues.address("테스트시 가상로 21 103동 2005호"))
    }
}
