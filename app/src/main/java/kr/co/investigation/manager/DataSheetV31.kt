@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    onOpen: (InvestigationCase) -> Unit,
    onPrint: ((SheetPrintSnapshot) -> Unit)? = null
) {
    val context = LocalContext.current
    val allCases by vm.allCases.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(DATA_ALL_V31) }
    var scheduleFilter by rememberSaveable { mutableStateOf(DATA_SCHEDULE_ALL_V31) }
    var sortColumn by rememberSaveable { mutableStateOf("번호") }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var columnFilters by vm.sheetFilters
    var filterDraft by vm.sheetFilterDraft
    var filterColumn by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by vm.sheetSelectionMode
    var selectedIds by vm.sheetSelectedIds
    val deleting by vm.sheetDeleting
    val deleteError by vm.sheetDeleteError
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val today = LocalDate.now()
    val horizontalState = rememberScrollState()

    fun changeZoom(value: Float) {
        zoom = value.coerceIn(DATA_MIN_ZOOM_V31, DATA_MAX_ZOOM_V31)
    }

    val rowNumbers = remember(allCases) { allCases.mapIndexed { index, c -> c.id to (index + 1) }.toMap() }
    val searched = remember(allCases, query, statusFilter, scheduleFilter, today) {
        val needle = query.trim()
        allCases.filter { c ->
            val matchesQuery = needle.isBlank() || dataSheetSearchValuesV31(c).any { it.contains(needle, ignoreCase = true) }
            val matchesStatus = statusFilter == DATA_ALL_V31 || normalizedStatusV31(c.status) == statusFilter
            val matchesSchedule = when (scheduleFilter) {
                DATA_SCHEDULE_ASSIGNED_V31 -> c.plannedDate.isNotBlank()
                DATA_SCHEDULE_UNASSIGNED_V31 -> c.plannedDate.isBlank()
                DATA_SCHEDULE_DELAYED_V31 -> isDelayedV31(c, today)
                else -> true
            }
            matchesQuery && matchesStatus && matchesSchedule
        }
    }

    val columns = remember {
        listOf(
            DataColumnV31("번호", 58.dp) { "" },
            DataColumnV31("관리번호", 170.dp) { it.managementNo },
            DataColumnV31("진행도", 90.dp) { normalizedStatusV31(it.status) },
            DataColumnV31("의뢰일", 110.dp) { it.requestDate },
            DataColumnV31("조사 예정일", 120.dp) { it.plannedDate },
            DataColumnV31("완료 요청일", 120.dp) { it.dueDate },
            DataColumnV31("채무자", 120.dp) { it.debtorName },
            DataColumnV31("채무자 연락처", 145.dp) { listOf(it.mobile, it.phone).filter(String::isNotBlank).distinct().joinToString(" / ") },
            DataColumnV31("물건 종류", 110.dp) { it.propertyType },
            DataColumnV31("물건 소재지", 310.dp) { it.propertyAddress },
            DataColumnV31("소유자", 115.dp) { it.ownerName },
            DataColumnV31("소유자 연락처", 140.dp) { it.ownerPhone },
            DataColumnV31("소유자 주소", 280.dp) { it.ownerAddress },
            DataColumnV31("기본 주소지", 145.dp) { it.defaultAddressLabel() },
            DataColumnV31("직접입력 주소", 280.dp) { it.customMapAddress },
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
    fun cellValue(c: InvestigationCase, column: DataColumnV31): String =
        if(column.label == "번호") rowNumbers.getValue(c.id).toString() else column.value(c)
    val filtered = remember(searched, columns, columnFilters, rowNumbers) {
        searched.filter { c -> columnFilters.all { (label, values) ->
            val column = columns.firstOrNull { it.label == label }
            column == null || cellValue(c, column).trim() in values
        } }
    }
    val sorted = remember(filtered, columns, rowNumbers, sortColumn, ascending) {
        val column = columns.firstOrNull { it.label == sortColumn } ?: columns.first()
        sortDataSheetRows(filtered, ascending) {
            if (column.label == "번호") rowNumbers.getValue(it.id).toString() else column.value(it)
        }
    }
    val visibleIds = remember(sorted) { sorted.map { it.id }.toSet() }
    val selectedVisible = selectedIds.intersect(visibleIds)
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    fun cancelSelection() {
        if (deleting) return
        selectionMode = false; selectedIds = emptySet(); confirmDelete = false
        vm.sheetDeleteError.value = ""
    }
    BackHandler(selectionMode) { cancelSelection() }
    if (confirmDelete && selectionMode) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("선택한 조사건 삭제") },
        text = { Text("선택한 ${selectedVisible.size}건을 휴지통으로 이동합니다. 휴지통에서 복원할 수 있습니다.") },
        confirmButton = { TextButton(enabled = selectedVisible.isNotEmpty() && !deleting,
            onClick = { confirmDelete = false; vm.deleteSheetSelection(selectedVisible.toSet()) },
            modifier = Modifier.testTag("sheet-confirm-delete")) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } })
    fun optionsFor(column: DataColumnV31) = allCases.map { cellValue(it, column).trim() }.distinct().sorted()
    columns.firstOrNull { it.label == filterColumn }?.let { column ->
        val options = optionsFor(column)
        SheetColumnFilterDialog(column.label, options, filterDraft,
            onChange = { filterDraft = it },
            onApply = {
                columnFilters = if(filterDraft.containsAll(options)) columnFilters - column.label
                    else columnFilters + (column.label to filterDraft.toSet())
                filterColumn = null
            },
            onClear = { columnFilters = columnFilters - column.label; filterColumn = null },
            onDismiss = { filterColumn = null })
    }
    fun printVisibleRows() {
        val snapshot = SheetPrintSnapshot(
            columns.map { SheetPrintColumn(it.label, it.width.value) },
            sorted.map { c -> columns.map { cellValue(c, it) } },
            buildString {
                append("정렬: $sortColumn ${if(ascending) "오름차순" else "내림차순"}")
                if(query.isNotBlank()) append(" · 검색: ").append(query.take(40))
                append(" · $statusFilter · $scheduleFilter · 열 필터 ${columnFilters.size}개")
            })
        if(onPrint != null) onPrint(snapshot) else printDataSheet(context, snapshot)
    }
    val tableWidth = columns.fold(0.dp) { total, column -> total + column.width * zoom } + columns.size.dp +
        if(selectionMode) 52.dp else 0.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if(selectionMode) "삭제할 건 선택" else "전체 데이터시트")
                        Text("전체 데이터 · 완료 포함", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { TextButton(enabled = !deleting,
                    onClick = { if(selectionMode) cancelSelection() else onBack() }) { Text(if(selectionMode) "취소" else "뒤로") } },
                actions = {
                    Text("${filtered.size}/${allCases.size}건", modifier = Modifier.padding(end = 4.dp))
                    if(selectionMode) TextButton(onClick = { confirmDelete = true },
                        enabled = selectedVisible.isNotEmpty() && !deleting, modifier = Modifier.testTag("sheet-delete-selected")) {
                        Text(if(deleting) "삭제 중…" else "삭제(${selectedVisible.size})")
                    } else {
                        TextButton(onClick = { selectedIds = emptySet(); selectionMode = true }, enabled = sorted.isNotEmpty()) { Text("삭제") }
                        TextButton(onClick = ::printVisibleRows, enabled = sorted.isNotEmpty(), modifier = Modifier.testTag("sheet-print")) { Text("인쇄") }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if(selectionMode) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visibleIds.isNotEmpty() && selectedVisible == visibleIds, enabled = !deleting,
                        onCheckedChange = { selectedIds = if(it) visibleIds else emptySet() },
                        modifier = Modifier.testTag("sheet-select-all"))
                    Text("현재 표시 ${visibleIds.size}건 전체 선택 · ${selectedVisible.size}건 선택됨", style = MaterialTheme.typography.bodySmall)
                }
                if(deleteError.isNotBlank()) Text(deleteError, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("관리번호, 이름, 주소, 영업점, 메모 등 검색") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("지우기") }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("sheet-search")
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        "열 제목: 정렬 · 열 필터: 값 선택 · 인쇄: 현재 조건의 행",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { changeZoom(zoom - .1f) }, enabled = zoom > DATA_MIN_ZOOM_V31) { Text("−") }
                    TextButton(onClick = { changeZoom(1f) }) { Text("${(zoom * 100).toInt()}%") }
                    TextButton(onClick = { changeZoom(zoom + .1f) }, enabled = zoom < DATA_MAX_ZOOM_V31) { Text("＋") }
                }
                if(columnFilters.isNotEmpty()) TextButton(onClick = { columnFilters = emptyMap() }) {
                    Text("열 필터 ${columnFilters.size}개 적용 중 · 모두 해제")
                }
            }
            HorizontalDivider()

            if (allCases.isEmpty()) {
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
                        DataSheetHeaderV31(columns, zoom, sortColumn, ascending, columnFilters.keys, selectionMode,
                            onFilter = { label ->
                                val column = columns.first { it.label == label }
                                filterDraft = columnFilters[label] ?: optionsFor(column).toSet()
                                filterColumn = label
                            }) { label ->
                            if (sortColumn == label) ascending = !ascending
                            else { sortColumn = label; ascending = true }
                        }
                        HorizontalDivider()
                        if(sorted.isEmpty()) Text("필터 조건에 맞는 데이터가 없습니다.", modifier = Modifier.padding(16.dp))
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            itemsIndexed(sorted, key = { _, c -> c.id }) { index, c ->
                                DataSheetRowV31(index, rowNumbers.getValue(c.id), c, columns, zoom,
                                    selectionMode, c.id in selectedVisible, !deleting) { row ->
                                    if(selectionMode) selectedIds = if(row.id in selectedIds) selectedIds - row.id else selectedIds + row.id
                                    else onOpen(row)
                                }
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
private fun DataSheetHeaderV31(columns: List<DataColumnV31>, zoom: Float, sortColumn: String, ascending: Boolean,
    activeFilters: Set<String>, selectionMode: Boolean, onFilter: (String) -> Unit, onSort: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height((76.dp * zoom).coerceAtLeast(68.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if(selectionMode) Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { Text("선택", fontSize = 12.sp) }
        columns.forEach { column ->
            Column(Modifier.width(column.width * zoom).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().clickable { onSort(column.label) }
                    .testTag("sheet-header-${column.label}").semantics {
                        stateDescription = if(column.label != sortColumn) "정렬 안 함" else if(ascending) "오름차순" else "내림차순"
                    }.padding(horizontal = 7.dp * zoom), contentAlignment = Alignment.CenterStart) {
                    Text(column.label + if(column.label == sortColumn) { if(ascending) " ↑" else " ↓" } else " ↕",
                        fontSize = (12f * zoom).sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onFilter(column.label) }, modifier = Modifier.fillMaxWidth().height(30.dp)
                    .testTag("sheet-filter-${column.label}"), contentPadding = PaddingValues(0.dp)) {
                    Text(if(column.label in activeFilters) "필터 ●" else "필터", fontSize = 10.sp)
                }
            }
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        }
    }
}

@Composable
private fun DataSheetRowV31(
    index: Int,
    rowNumber: Int,
    c: InvestigationCase,
    columns: List<DataColumnV31>,
    zoom: Float,
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onOpen: (InvestigationCase) -> Unit
) {
    val background = when {
        selectionMode && selected -> MaterialTheme.colorScheme.secondaryContainer
        normalizedStatusV31(c.status) == DATA_DONE_V31 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
        index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .20f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp * zoom)
            .background(background)
            .testTag("sheet-row-${c.id}")
            .clickable(enabled = enabled) { onOpen(c) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if(selectionMode) Checkbox(selected, onCheckedChange = { onOpen(c) }, enabled = enabled,
            modifier = Modifier.width(52.dp).testTag("sheet-select-${c.id}"))
        columns.forEachIndexed { columnIndex, column ->
            DataSheetCellV31(
                text = if (columnIndex == 0) rowNumber.toString() else column.value(c),
                width = column.width * zoom,
                zoom = zoom,
                header = false
            )
        }
    }
}

@Composable
private fun DataSheetCellV31(text: String, width: Dp, zoom: Float, header: Boolean,
    onClick: (() -> Unit)? = null, tag: String = "", sortDescription: String = "") {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
                .testTag(tag).semantics { stateDescription = sortDescription })
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
    c.managementNo, c.requestDate, c.plannedDate, c.dueDate,
    c.debtorName, c.phone, c.mobile, c.propertyType, c.propertyAddress,
    c.ownerName, c.ownerPhone, c.ownerAddress, c.customMapAddress, c.investigationType, c.loanType,
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
