package kr.co.investigation.manager.ocr

import kotlin.math.abs
import kotlin.math.min

/** Geometry only: a value is owned by one printed cell, never by a nearby person's name. */
internal object GridFormLayout {
    data class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2.0
    }
    data class Layout(val fields: Map<String, Cell>, val labelCells: Map<String, Cell>, val tenants: List<Pair<Cell, Cell>>, val notes: Cell)

    fun resolve(cells: List<Cell>): Layout? {
        val rows = mutableListOf<MutableList<Cell>>()
        for (cell in cells.filter { it.width > 0 && it.height > 0 }.sortedBy { it.centerY }) {
            val row = rows.firstOrNull {
                abs(it.first().centerY - cell.centerY) < min(it.first().height, cell.height) * 0.35
            }
            if (row == null) rows += mutableListOf(cell) else row += cell
        }
        val ordered = rows.map { it.sortedBy(Cell::left) }
        val pattern = listOf(6, 4, 4, 4, 2, 5, 2, 8, 8, 8, 8, 8, 1)
        val candidates = ordered.windowed(pattern.size).filter { r ->
            r.map { it.size } == pattern && consistentEdges(r) &&
                // Distinguish the two target rows from the main table and the notes box.
                r[2].first().top - r[1].first().bottom > r[1].first().height * 0.35 &&
                r[12][0].top - r[11][0].bottom > r[11][0].height * 0.35
        }
        val r = candidates.singleOrNull() ?: return null
        return Layout(
            fields = linkedMapOf(
                "debtorName" to r[0][1], "phone" to r[0][3], "mobile" to r[0][5],
                "dueDate" to r[1][1], "investigationType" to r[2][1], "loanType" to r[2][3],
                "propertyType" to r[3][1], "propertyAddress" to r[4][1],
                "ownerIdentity" to r[5][2], "ownerPhone" to r[5][4], "ownerAddress" to r[6][1]
            ),
            labelCells = linkedMapOf("debtor" to r[0][0], "property" to r[4][0], "tenant" to r[7][0]),
            tenants = (7..11).flatMap { row -> listOf(r[row][1] to r[row][3], r[row][5] to r[row][7]) },
            notes = r[12][0]
        )
    }

    private fun consistentEdges(rows: List<List<Cell>>): Boolean {
        val left = rows[0].first().left
        val right = rows[0].last().right
        val width = right - left
        if (width <= 0) return false
        return rows.all { row ->
            abs(row.first().left - left) < width * 0.025 && abs(row.last().right - right) < width * 0.025 &&
                row.zipWithNext().all { (a, b) ->
                    // Missing separators/merged cells must not silently shift the column mapping.
                    b.left - a.right in (-width * 0.01).toInt()..(width * 0.02).toInt()
                }
        }
    }
}
