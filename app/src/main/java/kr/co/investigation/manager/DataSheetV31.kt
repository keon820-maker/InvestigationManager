@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.investigation.manager.data.InvestigationCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 모든 연도와 완료 건을 포함하는 스프레드시트형 조회 화면. */
@Composable
fun DataSheetScreenV31(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpen: (InvestigationCase) -> Unit
) {
    val allCases by vm.allCases.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var yearFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var statusFilter by rememberSaveable { mutableStateOf(DATA_ALL_V31) }
    var scheduleFilter by rememberSaveable { mutableStateOf(DATA_SCHEDULE_ALL_V31) }
    var yearMenu by remember { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val today = LocalDate.now()
    val horizontalState = rememberScrollState()

    fun changeZoom(value: Float) {
        zoom = value.coerceIn(DATA_MIN_ZOOM_V31, DATA_MAX_ZOOM_V31)
    }

    val years = remember(allCases) { allCases.map { it.year }.distinct().sortedDescending() }
    val filtered = remember(allCases, query, yearFilter, statusFilter, scheduleFilter, today) {
        val needle = query.trim()
        allCases.filter { c ->
            val matchesQuery = needle.isBlank() || dataSheetSearchValuesV31(c).any { it.contains(needle, ignoreCase = true) }
            val matchesYear = yearFilter == null || c.year == yearFilter
            val matchesStatus = statusFilter == DATA_ALL_V31 || normalizedStatusV31(c.status) == statusFilter
            val matchesSchedule = when (scheduleFilter) {
                DATA_SCHEDULE_ASSIGNED_V31 -> c.plannedDate.isNotBlank()
                DATA_SCHEDULE_UNASSIGNED_V31 -> c.plannedDate.isBlank()
                DATA_SCHEDULE_DELAYED_V31 -> isDelayedV31(c, today)
                else -> true
            }
            matchesQuery && matchesYear && matchesStatus && matchesSchedule
        }
    }

    val columns = remember {
        listOf(
            DataColumnV31("번호", 58.dp) { "" },
            DataColumnV31("연도", 70.dp) { it.year.toString() },
            DataColumnV31("관리번호", 170.dp) { it.managementNo },
            DataColumnV31("진행도", 90.dp) { normalizedStatusV31(it.status) },
            DataColumnV31("의뢰일", 110.dp) { it.requestDate },
            DataColumnV31("조사 예정일", 120.dp) { it.plannedDate },
            DataColumnV31("완료 요청일", 120.dp) { it.dueDate },
            DataColumnV31("방문순서", 86.dp) { if (it.routeOrder > 0) it.routeOrder.toString() else "" },
            DataColumnV31("채무자", 120.dp) { it.debtorName },
            DataColumnV31("채무자 연락처", 145.dp) { listOf(it.mobile, it.phone).filter(String::isNotBlank).distinct().joinToString(" / ") },
            DataColumnV31("물건 종류", 110.dp) { it.propertyType },
            DataColumnV31("물건 소재지", 310.dp) { it.propertyAddress },
            DataColumnV31("소유자", 115.dp) { it.ownerName },
            DataColumnV31("소유자 연락처", 140.dp) { it.ownerPhone },
            DataColumnV31("소유자 주소", 280.dp) { it.ownerAddress },
            DataColumnV31("조사 종류", 125.dp) { it.investigationType },
            DataColumnV31("대출 종류", 120.dp) { it.loanType },
            DataColumnV31("영업점", 135.dp) { it.branch },
            DataColumnV31("조사담당자", 120.dp) { it.investigator },
            DataColumnV31("의뢰자", 110.dp) { it.requester },
            DataColumnV31("요청사항", 260.dp) { it.requestNotes },
            DataColumnV31("조사메모", 240.dp) { it.investigationMemo },
            DataColumnV31("조사 시작", 145.dp) { formatTimestampV31(it.startedAt) },
            DataColumnV31("조사 완료", 145.dp) { formatTimestampV31(it.completedAt) }
        )
    }
    val tableWidth = columns.fold(0.dp) { total, column -> total + column.width * zoom } + columns.size.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("전체 데이터시트")
                        Text("전체 연도 · 완료 포함", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = { Text("${filtered.size}/${allCases.size}건", modifier = Modifier.padding(end = 14.dp)) }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("관리번호, 이름, 주소, 영업점, 메모 등 검색") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("지우기") }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        FilterChip(
                            selected = yearFilter != null,
                            onClick = { yearMenu = true },
                            label = { Text(yearFilter?.let { "${it}년" } ?: "전체 연도") }
                        )
                        DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("전체 연도") },
                                onClick = { yearFilter = null; yearMenu = false }
                            )
                            years.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text("${year}년") },
                                    onClick = { yearFilter = year; yearMenu = false }
                                )
                            }
                        }
                    }
                    listOf(DATA_ALL_V31, DATA_NEW_V31, DATA_PROGRESS_V31, DATA_DONE_V31).forEach { status ->
                        FilterChip(
                            selected = statusFilter == status,
                            onClick = { statusFilter = status },
                            label = { Text(status) }
                        )
                    }
                    VerticalDivider(Modifier.height(30.dp))
                    listOf(
                        DATA_SCHEDULE_ALL_V31,
                        DATA_SCHEDULE_ASSIGNED_V31,
                        DATA_SCHEDULE_UNASSIGNED_V31,
                        DATA_SCHEDULE_DELAYED_V31
                    ).forEach { schedule ->
                        FilterChip(
                            selected = scheduleFilter == schedule,
                            onClick = { scheduleFilter = schedule },
                            label = { Text(schedule) }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "행을 누르면 상세화면 · 표는 좌우 이동/두 손가락 확대축소",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { changeZoom(zoom - .1f) }, enabled = zoom > DATA_MIN_ZOOM_V31) { Text("−") }
                    TextButton(onClick = { changeZoom(1f) }) { Text("${(zoom * 100).toInt()}%") }
                    TextButton(onClick = { changeZoom(zoom + .1f) }, enabled = zoom < DATA_MAX_ZOOM_V31) { Text("＋") }
                }
            }
            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (allCases.isEmpty()) "저장된 조사 데이터가 없습니다." else "필터 조건에 맞는 데이터가 없습니다.")
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoomChange = event.calculateZoom()
                                        if (zoomChange != 1f) changeZoom(zoom * zoomChange)
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (event.changes.all { !it.pressed }) break
                                }
                            }
                        }
                        .horizontalScroll(horizontalState)
                ) {
                    Column(Modifier.width(tableWidth).fillMaxHeight()) {
                        DataSheetHeaderV31(columns, zoom)
                        HorizontalDivider()
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            itemsIndexed(filtered, key = { _, c -> c.id }) { index, c ->
                                DataSheetRowV31(index, c, columns, zoom, onOpen)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .75f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DataColumnV31(
    val label: String,
    val width: Dp,
    val value: (InvestigationCase) -> String
)

@Composable
private fun DataSheetHeaderV31(columns: List<DataColumnV31>, zoom: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp * zoom)
            .background(MaterialTheme.colorScheme.primaryContainer),
        verticalAlignment = Alignment.CenterVertically
    ) {
        columns.forEach { column ->
            DataSheetCellV31(
                text = column.label,
                width = column.width * zoom,
                zoom = zoom,
                header = true
            )
        }
    }
}

@Composable
private fun DataSheetRowV31(
    index: Int,
    c: InvestigationCase,
    columns: List<DataColumnV31>,
    zoom: Float,
    onOpen: (InvestigationCase) -> Unit
) {
    val background = when {
        normalizedStatusV31(c.status) == DATA_DONE_V31 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
        index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .20f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp * zoom)
            .background(background)
            .clickable { onOpen(c) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        columns.forEachIndexed { columnIndex, column ->
            DataSheetCellV31(
                text = if (columnIndex == 0) (index + 1).toString() else column.value(c),
                width = column.width * zoom,
                zoom = zoom,
                header = false
            )
        }
    }
}

@Composable
private fun DataSheetCellV31(text: String, width: Dp, zoom: Float, header: Boolean) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 7.dp * zoom, vertical = 5.dp * zoom),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text.ifBlank { "-" },
            fontSize = (if (header) 13f else 12.5f).times(zoom).sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = if (header) 2 else 3,
            overflow = TextOverflow.Ellipsis
        )
    }
    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
}

private fun dataSheetSearchValuesV31(c: InvestigationCase): List<String> = listOf(
    c.year.toString(), c.managementNo, c.requestDate, c.plannedDate, c.dueDate,
    c.debtorName, c.phone, c.mobile, c.propertyType, c.propertyAddress,
    c.ownerName, c.ownerPhone, c.ownerAddress, c.investigationType, c.loanType,
    c.branch, c.branchPhone, c.investigator, c.investigatorPhone, c.requester,
    c.requestNotes, c.investigationMemo, c.status
)

private fun normalizedStatusV31(value: String): String = when (value.trim()) {
    DATA_PROGRESS_V31 -> DATA_PROGRESS_V31
    DATA_DONE_V31 -> DATA_DONE_V31
    else -> DATA_NEW_V31
}

private fun isDelayedV31(c: InvestigationCase, today: LocalDate): Boolean {
    if (normalizedStatusV31(c.status) == DATA_DONE_V31) return false
    val planned = runCatching { LocalDate.parse(c.plannedDate) }.getOrNull()
    val due = runCatching { LocalDate.parse(c.dueDate) }.getOrNull()
    return planned?.isBefore(today) == true || due?.isBefore(today) == true
}

private fun formatTimestampV31(value: Long?): String = value?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DATA_TIMESTAMP_V31)
}.orEmpty()

private const val DATA_ALL_V31 = "전체"
private const val DATA_NEW_V31 = "신규"
private const val DATA_PROGRESS_V31 = "진행중"
private const val DATA_DONE_V31 = "완료"
private const val DATA_SCHEDULE_ALL_V31 = "전체 일정"
private const val DATA_SCHEDULE_ASSIGNED_V31 = "예정 있음"
private const val DATA_SCHEDULE_UNASSIGNED_V31 = "미지정"
private const val DATA_SCHEDULE_DELAYED_V31 = "지연"
private const val DATA_MIN_ZOOM_V31 = .7f
private const val DATA_MAX_ZOOM_V31 = 1.6f
private val DATA_TIMESTAMP_V31 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREAN)
