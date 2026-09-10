package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import java.text.Collator
import java.util.Locale

/** Korean text and embedded numbers sort naturally; empty cells stay last in either direction. */
internal fun sortDataSheetRows(
    rows: List<InvestigationCase>,
    ascending: Boolean,
    value: (InvestigationCase) -> String
): List<InvestigationCase> {
    val collator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.SECONDARY }
    return rows.sortedWith { left, right ->
        val a = value(left).trim()
        val b = value(right).trim()
        val comparison = when {
            a.isEmpty() && b.isNotEmpty() -> 1
            a.isNotEmpty() && b.isEmpty() -> -1
            else -> naturalSheetCompare(a, b, collator) * if (ascending) 1 else -1
        }
        if (comparison == 0) left.id.compareTo(right.id) else comparison
    }
}

private val sheetParts = Regex("[0-9]+|[^0-9]+")

private fun naturalSheetCompare(a: String, b: String, collator: Collator): Int {
    val left = sheetParts.findAll(a).map { it.value }.toList()
    val right = sheetParts.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val comparison = if (x.first() in '0'..'9' && y.first() in '0'..'9') {
            val nx = x.trimStart('0').ifEmpty { "0" }
            val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else collator.compare(x, y)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}
