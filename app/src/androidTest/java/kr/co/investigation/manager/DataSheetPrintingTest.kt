package kr.co.investigation.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.pdf.PrintedPdfDocument
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DataSheetPrintingTest {
    @Test fun actualPdfUsesTheRequestedMarginsWithoutMovingOrClippingTheTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setResolution(PrintAttributes.Resolution("test", "test",300,300))
            .setMinMargins(PrintAttributes.Margins(500,500,500,500)).build()
        val file = File(context.cacheDir, "sheet-print-synthetic.pdf")
        val document = PrintedPdfDocument(context, settings)
        val left = document.pageContentRect.left
        val top = document.pageContentRect.top
        val snapshot = SheetPrintSnapshot(listOf(SheetPrintColumn("번호",58f), SheetPrintColumn("관리번호",170f)),
            listOf(listOf("1", "검사-인쇄")), "합성 자료")
        try {
            val layout = layoutSheetPrint(snapshot,document.pageContentRect.width(),document.pageContentRect.height()).single()
            val page = startSheetPrintPage(document,1)
            drawSheetPrintPage(page.canvas,snapshot,layout,1)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        try {
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            // The table header starts at content-origin + (0,65), including its grey fill.
                            assertTrue(Color.red(bitmap.getPixel(left+2,top+67)) < 250)
                            assertEquals(Color.WHITE,bitmap.getPixel(left-2,top+67))
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun wideTablesFitAllColumnsAndLongCellsContinueWithoutLoss() {
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
                assertEquals(columns.indices.toList(), page.columns)
                assertTrue(page.widths.sum() * page.scale <= width + .01f)
                assertTrue(page.rows.sumOf { it.height.toDouble() } <= height / page.scale - 120f + .01f)
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

    @Test fun ordinaryFilteredRowsUseOneLandscapePageWithEveryColumn() {
        val columns = listOf(SheetPrintColumn("번호",58f),SheetPrintColumn("관리번호",170f)) +
            (1..22).map { SheetPrintColumn("열$it",if(it % 4 == 0) 280f else 120f) }
        val rows = (1..4).map { row -> columns.mapIndexed { column, _ -> "자료$row-$column" } }
        val pages = layoutSheetPrint(SheetPrintSnapshot(columns,rows,"현재 필터"),1090,720)
        assertEquals(1,pages.size)
        assertEquals(columns.indices.toList(),pages.single().columns)
    }
}
