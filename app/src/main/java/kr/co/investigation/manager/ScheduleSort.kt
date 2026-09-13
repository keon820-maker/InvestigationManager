package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase

internal enum class ScheduleSort(val label: String, val compactLabel: String) {
    ROUTE("동선순", "동선순"),
    NUMBER_ASC("조사번호 오름차순", "조사번호 ↑"),
    NUMBER_DESC("조사번호 내림차순", "조사번호 ↓"),
    REGISTERED_ASC("등록일자 오름차순", "등록일자 ↑"),
    REGISTERED_DESC("등록일자 내림차순", "등록일자 ↓")
}

/** Dates stay chronological (unassigned last); sorting never changes saved route orders. */
internal fun sortScheduleRows(rows: List<InvestigationCase>, sort: ScheduleSort): List<InvestigationCase> =
    rows.sortedWith(compareBy<InvestigationCase> { it.plannedDate.isBlank() }.thenBy { it.plannedDate })
        .groupBy { it.plannedDate }
        .values.flatMap { day ->
            when (sort) {
                ScheduleSort.NUMBER_ASC -> sortDataSheetRows(day, true) { it.managementNo }
                ScheduleSort.NUMBER_DESC -> sortDataSheetRows(day, false) { it.managementNo }
                ScheduleSort.REGISTERED_ASC -> day.sortedWith(compareBy<InvestigationCase> { it.createdAt }.thenBy { it.id })
                ScheduleSort.REGISTERED_DESC -> day.sortedWith(compareByDescending<InvestigationCase> { it.createdAt }.thenBy { it.id })
                ScheduleSort.ROUTE -> day.sortedWith(
                    compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                        .thenBy { when (it.status.trim()) { "진행중" -> 1; "완료" -> 2; else -> 0 } }
                        .thenBy { it.dueDate }
                        .thenByDescending { it.id }
                )
            }
        }
