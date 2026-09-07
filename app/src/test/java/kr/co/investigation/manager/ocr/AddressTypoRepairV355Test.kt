package kr.co.investigation.manager.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressTypoRepairV355Test {
    @Test
    fun repairsObservedBuildingAndNeighborhoodTyposAndRemovesPostalCode() {
        assertEquals(
            "경기도 테스트시 테스트길 1-1 디에뜨르 A 101호",
            AddressTypoRepairV355.normalize("00000 경기도 테스트 시 테스트길 1-1 대이뜨르 A01호")
        )
        assertEquals(
            "서울특별시 테스트구 테스트로 1 (송파동 테스트아파트)",
            AddressTypoRepairV355.normalize("00000 서울특별시 테스트구 테스트로 1 (송피파동 테스트아파트)")
        )
        assertEquals(
            "경기도 테스트시 테스트길 2-7 주원하우스 4층",
            AddressTypoRepairV355.normalize("00000 경기도 테스트시 테스트길 2-7 주원히우스 4층")
        )
        assertEquals(
            "경기도 테스트시 테스트로 43 센트럴 푸르지오아파트 118동 604호",
            AddressTypoRepairV355.normalize("00000 경기도 테스트시 테스트로 43 센트럴 푸르지오오피트 118동 604호")
        )
    }

    @Test
    fun removesRealisticFiveDigitPostalCodeOnlyBeforeKoreanRegion() {
        assertEquals(
            "경기도 광주시 경충대로 1461번길 43 센트럴 푸르지오 118동 604호",
            AddressTypoRepairV355.normalize("12791 경기도 광주시 경충대로 1461번길 43 센트럴 푸르지오 118동 604호")
        )
        assertEquals(
            "경기도 성남시 수정구 산성대로393번길 2-7 주원하우스 4층",
            AddressTypoRepairV355.normalize("13147 경기도 성남시 수정구 산성대로393번길 2-7 주원하우스 4층")
        )
    }

    @Test
    fun leavesUnrelatedLeadingNumbersUntouched() {
        assertEquals(
            "12345 테스트건물 1층",
            AddressTypoRepairV355.normalize("12345 테스트건물 1층")
        )
    }
}
