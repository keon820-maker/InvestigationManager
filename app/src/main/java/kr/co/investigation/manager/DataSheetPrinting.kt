package kr.co.investigation.manager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.print.pdf.PrintedPdfDocument
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.FileOutputStream
import kotlin.math.floor

data class SheetPrintColumn(val title: String, val preferredWidth: Float)
data class SheetPrintSnapshot(val columns: List<SheetPrintColumn>, val rows: List<List<String>>, val description: String)
internal data class SheetPrintRow(val sourceIndex: Int, val cells: List<List<String>>, val height: Float)
internal data class SheetPrintPage(val columns: List<Int>, val widths: List<Float>, val rows: List<SheetPrintRow>, val group: Int, val groupCount: Int)

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

/** Split wide tables into readable column groups, repeating the number/management columns. */
internal fun layoutSheetPrint(snapshot: SheetPrintSnapshot, width: Int, height: Int): List<SheetPrintPage> {
    require(snapshot.columns.isNotEmpty())
    require(snapshot.rows.all { it.size == snapshot.columns.size })
    val anchors = snapshot.columns.indices.filter { snapshot.columns[it].title in setOf("번호", "관리번호") }
    require(width >= 160 && height >= 200) { "Paper content area is too small" }
    val widths = snapshot.columns.map { (it.preferredWidth * .65f).coerceIn(42f, width * .45f) }.toMutableList()
    val anchorWidth = anchors.sumOf { widths[it].toDouble() }.toFloat()
    snapshot.columns.indices.filterNot { it in anchors }.forEach { index ->
        widths[index] = minOf(widths[index], width - anchorWidth)
    }
    val groups = mutableListOf<List<Int>>()
    var current = anchors.toMutableList()
    var usedWidth = anchorWidth
    for(index in snapshot.columns.indices.filterNot { it in anchors }) {
        if(usedWidth + widths[index] > width && current.size > anchors.size) {
            groups += current.toList(); current = anchors.toMutableList(); usedWidth = anchorWidth
        }
        current += index; usedWidth += widths[index]
    }
    if(current.isNotEmpty()) groups += current.toList()
    val paint = sheetPrintPaint()
    val capacity = (height - 120f).coerceAtLeast(60f)
    val pages = mutableListOf<SheetPrintPage>()
    groups.forEachIndexed { groupIndex, columns ->
        var buffer = mutableListOf<SheetPrintRow>()
        var usedHeight = 0f
        fun flush() {
            if(buffer.isNotEmpty()) pages += SheetPrintPage(columns, columns.map { widths[it] }, buffer.toList(), groupIndex + 1, groups.size)
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
                    if(columns[index] in anchors && offset >= cell.size) cell.take(take) else cell.drop(offset).take(take)
                }
                val rowHeight = take * 11f + 8f
                buffer += SheetPrintRow(rowIndex, cells, rowHeight)
                usedHeight += rowHeight; offset += take
                if(offset < count) flush()
            }
        }
        flush()
    }
    return pages
}

internal fun drawSheetPrintPage(canvas: Canvas, snapshot: SheetPrintSnapshot, page: SheetPrintPage, pageNumber: Int) {
    val paint = sheetPrintPaint()
    paint.textSize = 13f
    canvas.drawText("조사 데이터시트", 0f, 17f, paint)
    paint.textSize = 8f
    canvas.drawText("현재 조건 ${snapshot.rows.size}건 · 열 묶음 ${page.group}/${page.groupCount} · ${pageNumber}페이지", 0f, 32f, paint)
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
                        val page = document.startPage(index + 1)
                        page.canvas.translate(document.pageContentRect.left.toFloat(), document.pageContentRect.top.toFloat())
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
