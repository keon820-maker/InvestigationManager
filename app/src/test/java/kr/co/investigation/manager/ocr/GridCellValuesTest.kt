package kr.co.investigation.manager.ocr

import org.junit.Assert.*
import org.junit.Test

class GridCellValuesTest {
    @Test fun blankCellStaysBlank() {
        assertEquals(GridCellValues.Choice("", false), GridCellValues.choose("", "", GridCellValues::phone))
    }
    @Test fun disagreeingDigitsAreNotGuessed() {
        assertEquals(GridCellValues.Choice("", true), GridCellValues.choose("010-0000-0000", "010-0000-0001", GridCellValues::phone))
    }
    @Test fun sameNumberCanBePresentInMultipleRoles() {
        for (role in listOf("phone", "mobile", "owner", "tenant")) {
            assertEquals(role, "010-0000-0000", GridCellValues.phone("01000000000"))
        }
    }
    @Test fun wrappedNumberStaysWithinItsCell() {
        assertEquals("010-0000-0000", GridCellValues.phone("010-0000-\n0000[010-0000-0000]"))
        assertEquals("", GridCellValues.phone("010-0000-0000 010-0000-0001"))
        assertEquals("", GridCellValues.phone("010-0000 [0000]"))
        assertEquals("0503-0000-0000", GridCellValues.phone("(0503-0000-0000)"))
    }
    @Test fun impossibleDateAndLabelAreRejected() {
        assertEquals("", GridCellValues.date("2026-02-31"))
        assertEquals("2026-03-09", GridCellValues.date("2026년 03월 09일"))
        assertEquals("2026-03-09", GridCellValues.date("2026년 3월 9일"))
        assertEquals("", GridCellValues.name("임차인1(성명)"))
    }
    @Test fun addressIsNotChangedToAnUnobservedBuilding() {
        assertEquals("테스트시 20 프아파트", GridCellValues.address("12345 테스트시 20 프아파트"))
    }
    @Test fun birthAndFullLoanLabelArePreserved() {
        assertEquals("가나다(900101-*)", OcrFieldNormalizer.debtorIdentity("가나다 (900101-*)"))
        assertEquals("부동산 담보대출", OcrFieldNormalizer.loanType("부동산담보대출"))
    }
}
