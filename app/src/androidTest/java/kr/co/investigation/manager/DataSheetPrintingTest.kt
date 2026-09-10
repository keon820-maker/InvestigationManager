package kr.co.investigation.manager

import org.junit.Assert.*
import org.junit.Test

class DataSheetPrintingTest {
    @Test fun wideTablesRepeatIdentifiersAndLongCellsContinueWithoutLoss() {
        val columns = listOf(SheetPrintColumn("번호",58f), SheetPrintColumn("관리번호",170f)) +
            (1..12).map { SheetPrintColumn("검증열$it",310f) }
        val longValue = "한글주소123".repeat(450)
        val rows = (0..3).map { index -> listOf("${index+1}","검사-$index") +
            (1..12).map { if(index==1 && it==4) longValue else "내용 $index/$it" } }
        val snapshot = SheetPrintSnapshot(columns, rows, "합성 자료 인쇄 검증")
        for((width,height) in listOf(790 to 540, 540 to 790, 250 to 300)) {
            val pages = layoutSheetPrint(snapshot,width,height)
            assertTrue(pages.size > 1)
            pages.forEach { page ->
                assertTrue(page.widths.sum() <= width + .01f)
                assertTrue(page.rows.sumOf { it.height.toDouble() } <= height - 120f + .01f)
                assertTrue(page.columns.containsAll(listOf(0,1)))
            }
            for(column in 2 until columns.size) {
                for(row in rows.indices) {
                    val actual = pages.filter { column in it.columns }.flatMap { page ->
                        page.rows.filter { it.sourceIndex == row }.flatMap { it.cells[page.columns.indexOf(column)] }
                    }.joinToString("")
                    assertEquals("Cell $row/$column on $width x $height", rows[row][column], actual)
                }
            }
        }
    }
}
