package kr.co.investigation.manager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.print.pdf.PrintedPdfDocument
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.FileOutputStream
import kotlin.math.floor
import kotlin.math.min

data class SheetPrintColumn(val title: String, val preferredWidth: Float)
data class SheetPrintSnapshot(val columns: List<SheetPrintColumn>, val rows: List<List<String>>, val description: String)
internal data class SheetPrintRow(val sourceIndex: Int, val cells: List<List<String>>, val height: Float)
internal data class SheetPrintPage(val columns: List<Int>, val widths: List<Float>, val rows: List<SheetPrintRow>, val scale: Float)

private fun sheetPrintPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.BLACK; textSize = 9f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
}

private fun wrappedSheetText(value: String, width: Float, paint: Paint): List<String> = buildList {
    value.lines().forEach { line ->
        var rest = line
        if(rest.isEmpty()) add("")
        while(rest.isNotEmpty()) {
            val count = paint.breakText(rest, true, width.coerceAtLeast(1f), null).coerceAtLeast(1)
            add(rest.take(count)); rest = rest.drop(count)
        }
    }
}

/** Fit every column across one landscape sheet; continue vertically only when rows do not fit. */
internal fun layoutSheetPrint(snapshot: SheetPrintSnapshot, width: Int, height: Int): List<SheetPrintPage> {
    require(snapshot.columns.isNotEmpty())
    require(snapshot.rows.all { it.size == snapshot.columns.size })
    require(width >= 160 && height >= 200) { "Paper content area is too small" }
    val columns = snapshot.columns.indices.toList()
    val widths = snapshot.columns.map { (it.preferredWidth * .65f).coerceAtLeast(42f) }
    val scale = min(1f, width / widths.sum())
    val paint = sheetPrintPaint()
    val logicalHeight = height / scale
    val capacity = (logicalHeight - 120f).coerceAtLeast(60f)
    val pages = mutableListOf<SheetPrintPage>()
    var buffer = mutableListOf<SheetPrintRow>()
    var usedHeight = 0f
    fun flush() {
        if(buffer.isNotEmpty()) pages += SheetPrintPage(columns, widths, buffer.toList(), scale)
        buffer = mutableListOf(); usedHeight = 0f
    }
    snapshot.rows.forEachIndexed { rowIndex, row ->
            val lines = columns.map { wrappedSheetText(row[it], widths[it] - 8f, paint) }
            val count = lines.maxOf { it.size }
            val fullHeight = count * 11f + 8f
            if(fullHeight <= capacity && usedHeight + fullHeight > capacity) flush()
            var offset = 0
            while(offset < count) {
                if(capacity - usedHeight < 19f) flush()
                val take = minOf(count - offset, floor((capacity - usedHeight - 8f) / 11f).toInt().coerceAtLeast(1))
                val cells = lines.mapIndexed { index, cell ->
                    cell.drop(offset).take(take)
                }
                val rowHeight = take * 11f + 8f
                buffer += SheetPrintRow(rowIndex, cells, rowHeight)
                usedHeight += rowHeight; offset += take
                if(offset < count) flush()
            }
    }
    flush()
    return pages
}

internal fun drawSheetPrintPage(canvas: Canvas, snapshot: SheetPrintSnapshot, page: SheetPrintPage, pageNumber: Int) {
    canvas.save()
    canvas.scale(page.scale, page.scale)
    val paint = sheetPrintPaint()
    paint.textSize = 13f
    canvas.drawText("조사 데이터시트", 0f, 17f, paint)
    paint.textSize = 8f
    canvas.drawText("현재 조건 ${snapshot.rows.size}건 · 전체 열 · ${pageNumber}페이지", 0f, 32f, paint)
    val totalWidth = page.widths.sum()
    wrappedSheetText(snapshot.description, totalWidth, paint).take(2).forEachIndexed { i, text -> canvas.drawText(text, 0f, 44f + 10f*i, paint) }
    var x = 0f
    val border = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = .4f }
    val fill = Paint().apply { color = Color.rgb(236, 240, 246) }
    paint.textSize = 9f
    page.columns.forEachIndexed { index, column ->
        val w = page.widths[index]
        canvas.drawRect(x, 65f, x+w, 95f, fill)
        canvas.drawRect(x, 65f, x+w, 95f, border)
        wrappedSheetText(snapshot.columns[column].title, w-8f, paint).take(2).forEachIndexed { i, text -> canvas.drawText(text,x+4f,77f+11f*i,paint) }
        x += w
    }
    var y = 95f
    page.rows.forEach { row ->
        x = 0f
        row.cells.forEachIndexed { index, lines ->
            val w = page.widths[index]
            canvas.drawRect(x, y, x+w, y+row.height, border)
            lines.forEachIndexed { i, text -> canvas.drawText(text,x+4f,y+12f+11f*i,paint) }
            x += w
        }
        y += row.height
    }
    canvas.restore()
}

internal fun printDataSheet(context: Context, snapshot: SheetPrintSnapshot) {
    if(snapshot.rows.isEmpty()) return
    try {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print("조사 데이터시트", DataSheetPrintAdapter(context, snapshot), PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setMinMargins(PrintAttributes.Margins(350, 350, 350, 350)).build())
    } catch (_: Exception) { Toast.makeText(context,"인쇄 화면을 열 수 없습니다. 인쇄 서비스를 확인해주세요.",Toast.LENGTH_LONG).show() }
}

internal fun startSheetPrintPage(document: PrintedPdfDocument, pageNumber: Int): PdfDocument.Page {
    // Use a full-page canvas and apply the printer margins exactly once ourselves.
    val page = document.startPage(PdfDocument.PageInfo.Builder(document.pageWidth, document.pageHeight, pageNumber).create())
    page.canvas.translate(document.pageContentRect.left.toFloat(), document.pageContentRect.top.toFloat())
    return page
}

private class DataSheetPrintAdapter(private val context: Context, private val snapshot: SheetPrintSnapshot): PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null
    private var pages = emptyList<SheetPrintPage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancellation: CancellationSignal,
                          callback: LayoutResultCallback, extras: Bundle?) {
        if(cancellation.isCanceled) { callback.onLayoutCancelled(); return }
        try {
            val document = PrintedPdfDocument(context, new)
            try { pages = layoutSheetPrint(snapshot, document.pageContentRect.width(), document.pageContentRect.height()) }
            finally { document.close() }
            attributes = new
            if(cancellation.isCanceled) callback.onLayoutCancelled()
            else callback.onLayoutFinished(PrintDocumentInfo.Builder("조사 데이터시트.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages.size).build(), old != new)
        } catch (_: Exception) { callback.onLayoutFailed("인쇄 페이지를 만들지 못했습니다.") }
    }

    override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, cancellation: CancellationSignal,
                         callback: WriteResultCallback) {
        val settings = attributes ?: return callback.onWriteFailed("용지 설정이 없습니다.")
        val layout = pages.toList()
        scope.launch {
            val document = PrintedPdfDocument(context, settings)
            try {
                val written = mutableListOf<PageRange>()
                layout.forEachIndexed { index, content ->
                    ensureActive()
                    if(cancellation.isCanceled) throw CancellationException()
                    if(ranges.any { index in it.start..it.end }) {
                        val page = startSheetPrintPage(document, index + 1)
                        drawSheetPrintPage(page.canvas,snapshot,content,index+1)
                        document.finishPage(page)
                        written += PageRange(index,index)
                    }
                }
                FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                if(cancellation.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(written.toTypedArray())
            } catch (_: CancellationException) { callback.onWriteCancelled() }
              catch (_: Exception) { callback.onWriteFailed("인쇄 파일을 작성하지 못했습니다.") }
            finally { document.close() }
        }
    }

    override fun onFinish() { scope.cancel() }
}
