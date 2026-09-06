package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressTypoRepairV355Test {
    @Test
    fun repairsObservedBuildingAndNeighborhoodTypos() {
        assertEquals(
            "00000 경기도 테스트시 테스트길 1-1 디에뜨르 A 101호",
            AddressTypoRepairV355.normalize("00000 경기도 테스트 시 테스트길 1-1 대이뜨르 A01호")
        )
        assertEquals(
            "00000 서울특별시 테스트구 테스트로 1 (송파동 테스트아파트)",
            AddressTypoRepairV355.normalize("00000 서울특별시 테스트구 테스트로 1 (송피파동 테스트아파트)")
        )
    }

    @Test
    fun leavesUnrelatedAddressTextUntouched() {
        assertEquals(
            "00000 부산광역시 해운대구 테스트로 10 101호",
            AddressTypoRepairV355.normalize("00000 부산광역시 해운대구 테스트로 10 101호")
        )
    }
}
