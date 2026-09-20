@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable as rememberUiState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.ocr.OcrService
import kr.co.investigation.manager.storage.OriginalFileStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

/** v0.32: 카카오맵 + 조절 가능한 태블릿 지도 영역 + 전체 데이터시트. */
@Composable
fun InvestigationAppV29(vm: AppViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("investigation_ui", Context.MODE_PRIVATE) }
    var screen by rememberUiState { mutableStateOf("main") }
    var formReturn by rememberUiState { mutableStateOf("main") }
    var detailReturn by rememberUiState { mutableStateOf("main") }
    var selectedCaseId by rememberUiState { mutableStateOf<Long?>(null) }
    var viewingAttachmentId by rememberUiState { mutableStateOf<Long?>(null) }
    var returnFocusCaseId by rememberUiState { mutableStateOf<Long?>(null) }
    val screenStates = rememberSaveableStateHolder()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val attachments by remember(selected?.id) {
        selected?.let { vm.db.attachments().observe(it.id) } ?: flowOf(emptyList<Attachment>())
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(selectedCaseId) {
        selectedCaseId?.let { id ->
            if (vm.selected.value?.id != id) vm.db.cases().get(id)?.let(vm::select)
        }
    }
    fun selectCase(value: InvestigationCase) { selectedCaseId = value.id; vm.select(value) }
    var confirmExit by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(!prefs.getBoolean("v029_guide_seen", false)) }

    fun closeGuide() {
        prefs.edit().putBoolean("v029_guide_seen", true).apply()
        showGuide = false
    }

    fun goBack() {
        if (screen == "ocr" && vm.ocrDraft.saving.value) return
        if (screen == "ocr") vm.ocrDraft.reset()
        screen = when (screen) {
            "attachment" -> "detail"
            "form" -> formReturn
            "detail" -> detailReturn
            "ocr", "settings", "patches", "calendar", "datasheet" -> "main"
            else -> "main"
        }
    }

    BackHandler(enabled = true) {
        when {
            showGuide -> closeGuide()
            confirmExit -> confirmExit = false
            screen == "main" -> confirmExit = true
            else -> goBack()
        }
    }

    if (showGuide) {
        UsageGuideDialogV29(onClose = ::closeGuide)
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("앱 종료") },
            text = { Text("조사관리 앱을 종료하시겠습니까?") },
            confirmButton = {
                Button(onClick = {
                    confirmExit = false
                    (context as? Activity)?.finishAffinity()
                }) { Text("예") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmExit = false }) { Text("아니요") } }
        )
    }

    screenStates.SaveableStateProvider(screen) {
    Box(Modifier.fillMaxSize().testTag("screen-$screen")) {
    when (screen) {
        "main" -> MainScreenV29(
            vm = vm,
            onNew = { vm.ocrDraft.reset(); screenStates.removeState("ocr"); screen = "ocr" },
            onEdit = {
                returnFocusCaseId = it.id
                selectCase(it)
                detailReturn = "main"
                screen = "detail"
            },
            onForm = { selectCase(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" },
            onPatchHistory = { screen = "patches" },
            onCalendar = { screen = "calendar" },
            onDataSheet = { screen = "datasheet" },
            onGuide = { showGuide = true },
            focusCaseId = returnFocusCaseId,
            onFocusConsumed = { id -> if (returnFocusCaseId == id) returnFocusCaseId = null }
        )
        "calendar" -> CalendarScreenV29(
            vm = vm,
            onBack = { screen = "main" },
            onOpen = { selectCase(it); detailReturn = "calendar"; screen = "detail" }
        )
        "datasheet" -> DataSheetScreenV31(
            vm = vm,
            onBack = { screen = "main" },
            onOpen = {
                vm.setYear(it.year)
                returnFocusCaseId = it.id
                selectCase(it)
                detailReturn = "main"
                screen = "detail"
            }
        )
        "ocr" -> OcrRegisterScreenV29(vm,
            onDone = { vm.ocrDraft.reset(); screen = "main" },
            onCancel = { vm.ocrDraft.reset(); screen = "main" })
        "detail" -> vm.selected.collectAsStateWithLifecycle().value?.let {
            DetailScreen(
                vm = vm,
                c0 = it,
                onBack = { screen = detailReturn },
                onForm = { formReturn = "detail"; screen = "form" },
                onAttachment = { att -> viewingAttachmentId = att.id; screen = "attachment" }
            )
        }
        "form" -> vm.selected.collectAsStateWithLifecycle().value?.let {
            RequestFormScreen(it, onBack = { screen = formReturn })
        }
        "attachment" -> attachments.firstOrNull { it.id == viewingAttachmentId }?.let {
            AttachmentViewerScreen(it, onBack = { screen = "detail" })
        }
        "settings" -> SettingsScreen(vm, onBack = { screen = "main" })
        "patches" -> PatchHistoryScreen(onBack = { screen = "main" })
    }
    }
    }
}

@Composable
private fun UsageGuideDialogV29(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("조사관리 사용방법") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("문서 → 일정 → 지도/내비 순서로 사용합니다.", fontWeight = FontWeight.SemiBold)
                Text("1. 신규 등록에서 기존 사진을 선택하거나 카메라로 조사의뢰서를 촬영합니다.")
                Text("2. OCR 결과를 확인하고 조사 예정일과 진행도를 지정합니다.")
                Text("3. 일정 화면에서 날짜 기준과 진행상황 기준 필터를 각각 선택해 원하는 조사건만 빠르게 좁힐 수 있고, 필터 초기화로 한 번에 전체보기로 돌아갈 수 있습니다.")
                Text("4. 진행중 건은 카카오맵에 표시되며 마커의 간단정보로 대상을 구분할 수 있습니다.")
                Text("5. 편집 화면의 주소지 변경에서 지도 표시 위치를 지정합니다. 직접입력 주소는 조사의뢰서에도 표시됩니다.")
                Text("6. 전화는 임차인·물건 소유자·채무자 중 저장된 번호를 선택합니다.")
                Text("7. 캘린더의 일정을 누르면 편집하거나 길찾기를 시작할 수 있습니다.")
                Text("8. 전체 데이터시트에서는 모든 연도의 저장 건을 필터링하고 화면 크기를 조절해 확인합니다.")
                Text("9. 태블릿 가로 분할 화면에서는 지도 위 ‘지도 폭’ 버튼으로 지도 크기를 조절합니다.")
                Text("10. 데이터 및 동기화에서 같은 Google 계정으로 로그인하면 조사 데이터와 원본이 기기 간 자동 동기화됩니다.")
                Text("조사의뢰서/원본과 지도는 두 번 터치 및 두 손가락 확대·축소를 지원합니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("확인") } }
    )
}

@Composable
private fun MainScreenV29(
    vm: AppViewModel,
    onNew: () -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onSettings: () -> Unit,
    onPatchHistory: () -> Unit,
    onCalendar: () -> Unit,
    onDataSheet: () -> Unit,
    onGuide: () -> Unit,
    focusCaseId: Long?,
    onFocusConsumed: (Long) -> Unit
) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mobileTab by rememberSaveable { mutableIntStateOf(0) }
    var dateFilter by rememberSaveable { mutableStateOf(FILTER_ALL_V29) }
    var statusFilter by rememberSaveable { mutableStateOf(FILTER_ALL_V29) }
    var scheduleViewMode by rememberSaveable { mutableStateOf(SCHEDULE_VIEW_DATE_V36) }
    var periodStart by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var periodEnd by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showPeriodPicker by remember { mutableStateOf(false) }
    var scheduleSortName by rememberUiState { mutableStateOf(ScheduleSort.NUMBER_ASC.name) }
    val scheduleSort = ScheduleSort.entries.firstOrNull { it.name == scheduleSortName } ?: ScheduleSort.NUMBER_ASC
    var moreMenu by remember { mutableStateOf(false) }
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var callCase by remember { mutableStateOf<InvestigationCase?>(null) }
    val context = LocalContext.current
    val layoutPrefs = remember(context) { context.getSharedPreferences("investigation_ui", Context.MODE_PRIVATE) }
    var mapSizeLevel by rememberSaveable {
        mutableIntStateOf(layoutPrefs.getInt("wide_map_size", MAP_SIZE_NORMAL_V31).coerceIn(MAP_SIZE_SMALL_V31, MAP_SIZE_LARGE_V31))
    }
    fun setMapSizeLevel(value: Int) {
        mapSizeLevel = value.coerceIn(MAP_SIZE_SMALL_V31, MAP_SIZE_LARGE_V31)
        layoutPrefs.edit().putInt("wide_map_size", mapSizeLevel).apply()
    }
    val configuration = LocalConfiguration.current
    // Fold 펼침/일반 태블릿을 단순 600dp로 구분하지 않는다.
    // 960dp 이상인 충분히 넓은 창에서만 좌우 2분할하고, 그 외에는 일정/지도 단일 화면을 사용한다.
    val splitLayout = configuration.screenWidthDp >= 960 && configuration.screenHeightDp >= 600
    val todayDate = LocalDate.now()
    val today = todayDate.toString()

    LaunchedEffect(focusCaseId) {
        if (focusCaseId != null) mobileTab = 0
    }

    if (showPeriodPicker) {
        SchedulePeriodDialogV33(
            initialStart = when (dateFilter) {
                FILTER_TODAY_V29 -> today
                FILTER_TOMORROW_V36 -> todayDate.plusDays(1).toString()
                FILTER_PERIOD_V29 -> periodStart
                else -> today
            },
            initialEnd = when (dateFilter) {
                FILTER_TODAY_V29 -> today
                FILTER_TOMORROW_V36 -> todayDate.plusDays(1).toString()
                FILTER_PERIOD_V29 -> periodEnd
                else -> today
            },
            onDismiss = { showPeriodPicker = false },
            onApply = { start, end ->
                periodStart = start
                periodEnd = end
                dateFilter = FILTER_PERIOD_V29
                // 새 기간 조회는 전체 진행상황에서 시작한다.
                // 이후 사용자가 진행중/지연/완료 등을 눌러 다시 좁힐 수 있다.
                statusFilter = FILTER_ALL_V29
                showPeriodPicker = false
            }
        )
    }

    navCase?.let { c ->
        NavigationFlowDialogV29(c = c, onDismiss = { navCase = null })
    }
    callCase?.let { c ->
        PhoneChoiceDialogV29(c = c, onDismiss = { callCase = null })
    }
    val searched = remember(cases, query) {
        cases.filter { c ->
            query.isBlank() || listOf(
                c.managementNo, c.debtorName, c.propertyAddress, c.ownerAddress,
                c.phone, c.mobile, c.plannedDate, c.branch, c.status
            ).any { it.contains(query, true) }
        }
    }
    val tomorrow = todayDate.plusDays(1)
    val weekStart = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong())
    val weekEnd = weekStart.plusDays(6)

    val dateFiltered = remember(searched, dateFilter, today, periodStart, periodEnd, weekStart, weekEnd) {
        searched.filter { c ->
            when (dateFilter) {
                FILTER_ALL_V29 -> true
                FILTER_TODAY_V29 -> c.plannedDate == today
                FILTER_TOMORROW_V36 -> c.plannedDate == tomorrow.toString()
                FILTER_THIS_WEEK_V36 -> c.plannedDate.toDateV29()?.let { date ->
                    !date.isBefore(weekStart) && !date.isAfter(weekEnd)
                } == true
                FILTER_UNASSIGNED_V36 -> c.plannedDate.isBlank()
                FILTER_PERIOD_V29 -> c.plannedDate.toDateV29()?.let { date ->
                    val start = periodStart.toDateV29() ?: todayDate
                    val end = periodEnd.toDateV29() ?: start
                    !date.isBefore(minOf(start, end)) && !date.isAfter(maxOf(start, end))
                } == true
                else -> true
            }
        }
    }
    val statusCounts = remember(dateFiltered, todayDate) {
        scheduleStatusCountsV36(dateFiltered, todayDate)
    }
    val statusFiltered = remember(dateFiltered, statusFilter, todayDate) {
        dateFiltered.filter { c -> scheduleMatchesStatusV36(c, statusFilter, todayDate) }
    }
    val listItems = statusFiltered

    LaunchedEffect(focusCaseId, listItems, cases) {
        val id = focusCaseId ?: return@LaunchedEffect
        val existsInYear = cases.any { it.id == id }
        if (!existsInYear) {
            onFocusConsumed(id)
        } else if (listItems.none { it.id == id }) {
            query = ""
            dateFilter = FILTER_ALL_V29
            statusFilter = FILTER_ALL_V29
        }
    }

    val mapItems = remember(statusFiltered) {
        statusFiltered.filter { it.status.normalizedStatusV29() == STATUS_IN_PROGRESS_V29 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("조사 일정", fontWeight = FontWeight.SemiBold)
                        Text("${year}년 · ${displayDateV29(today)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹") }
                    TextButton(onClick = { vm.setYear(year + 1) }) { Text("›") }
                    Box {
                        TextButton(onClick = { moreMenu = true },modifier = Modifier.testTag("main-menu")) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("캘린더") }, onClick = { moreMenu = false; onCalendar() })
                            DropdownMenuItem(text = { Text("전체 데이터시트") }, onClick = { moreMenu = false; onDataSheet() })
                            DropdownMenuItem(text = { Text("사용방법") }, onClick = { moreMenu = false; onGuide() })
                            DropdownMenuItem(text = { Text("패치내역") }, onClick = { moreMenu = false; onPatchHistory() }, modifier = Modifier.testTag("main-patch-history"))
                            DropdownMenuItem(text = { Text("데이터·동기화") }, onClick = { moreMenu = false; onSettings() })
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!splitLayout) {
                NavigationBar {
                    NavigationBarItem(
                        selected = mobileTab == 0,
                        onClick = { mobileTab = 0 },
                        icon = { Text("≡", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("일정") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = onCalendar,
                        icon = { Text("▦", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("캘린더") }
                    )
                    NavigationBarItem(
                        selected = mobileTab == 1,
                        onClick = { mobileTab = 1 },
                        icon = { Text("⌖", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("지도") }
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, text = { Text("신규 등록") }, icon = { Text("＋") }, modifier = Modifier.testTag("new-registration"))
        }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            // 실제 사용 가능한 창 폭을 다시 확인한다. 폴드 펼침은 단일 화면,
            // 큰 태블릿 가로모드처럼 충분히 넓을 때만 안정적으로 좌우 분할한다.
            val wide = maxWidth >= 960.dp && maxHeight >= 560.dp
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                SchedulePaneV29(
                    items = listItems,
                    query = query,
                    onQuery = { query = it },
                    dateFilter = dateFilter,
                    onDateFilter = { dateFilter = it },
                    statusFilter = statusFilter,
                    onStatusFilter = { statusFilter = it },
                    statusCounts = statusCounts,
                    periodLabel = schedulePeriodLabelV36(dateFilter, periodStart, periodEnd, todayDate),
                    onPeriod = { showPeriodPicker = true },
                    sort = scheduleSort,
                    onSort = { scheduleSortName = it.name },
                    viewMode = scheduleViewMode,
                    onViewMode = { scheduleViewMode = it },
                    focusCaseId = focusCaseId,
                    onFocusConsumed = onFocusConsumed,
                    onLocate = { vm.select(it); if (!wide) mobileTab = 1 },
                    onEdit = onEdit,
                    onForm = onForm,
                    onNavigate = { navCase = it },
                    onCall = { callCase = it },
                    onStatus = { c, status -> vm.changeStatus(c, status) },
                    onSchedule = { c, date -> vm.changePlannedDate(c, date) },
                    modifier = modifier
                )
            }

            if (wide) {
                val maximumMapWidth = (maxWidth - 400.dp - 1.dp).coerceAtLeast(320.dp)
                val mapWidth = (maxWidth * MAP_WIDTH_FRACTIONS_V31[mapSizeLevel])
                    .coerceIn(320.dp, maximumMapWidth)
                Row(Modifier.fillMaxSize()) {
                    listPane(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    NativeMapPaneV29(
                        items = mapItems,
                        selected = selected,
                        onNavigate = { navCase = it },
                        modifier = Modifier.width(mapWidth).fillMaxHeight(),
                        mapSizeLevel = mapSizeLevel,
                        onMapSizeLevel = ::setMapSizeLevel
                    )
                }
            } else {
                if (mobileTab == 0) listPane(Modifier.fillMaxSize())
                else NativeMapPaneV29(mapItems, selected, { navCase = it }, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SchedulePaneV29(
    items: List<InvestigationCase>,
    query: String,
    onQuery: (String) -> Unit,
    dateFilter: String,
    onDateFilter: (String) -> Unit,
    statusFilter: String,
    onStatusFilter: (String) -> Unit,
    statusCounts: Map<String, Int>,
    periodLabel: String,
    onPeriod: () -> Unit,
    sort: ScheduleSort,
    onSort: (ScheduleSort) -> Unit,
    viewMode: String,
    onViewMode: (String) -> Unit,
    focusCaseId: Long?,
    onFocusConsumed: (Long) -> Unit,
    onLocate: (InvestigationCase) -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onNavigate: (InvestigationCase) -> Unit,
    onCall: (InvestigationCase) -> Unit,
    onStatus: (InvestigationCase, String) -> Unit,
    onSchedule: (InvestigationCase, String) -> Unit,
    modifier: Modifier
) {
    var menuCaseId by remember { mutableStateOf<Long?>(null) }
    var scheduleCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var statusCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()

    scheduleCase?.let { c ->
        PlannedDateDialogV29(c.plannedDate, { scheduleCase = null }) { date ->
            scheduleCase = null
            onSchedule(c, date)
        }
    }
    statusCase?.let { c ->
        StatusDialogV29(c.status, { statusCase = null }) { status ->
            statusCase = null
            onStatus(c, status)
        }
    }

    val grouped = remember(items, sort) {
        sortScheduleRows(items, sort).groupBy { it.plannedDate.ifBlank { NO_DATE_V29 } }
    }
    val numberRows = remember(items) {
        sortDataSheetRows(items, true) { it.managementNo }
    }
    val listState = rememberLazyListState()
    var highlightedCaseId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(focusCaseId, grouped, numberRows, viewMode) {
        val id = focusCaseId ?: return@LaunchedEffect
        val targetIndex = if (viewMode == SCHEDULE_VIEW_NUMBER_V36) {
            numberRows.indexOfFirst { it.id == id }
        } else {
            var base = 0
            var found = -1
            for ((_, rows) in grouped) {
                val rowIndex = rows.indexOfFirst { it.id == id }
                if (rowIndex >= 0) {
                    found = base + 1 + rowIndex
                    break
                }
                base += 1 + rows.size
            }
            found
        }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
            highlightedCaseId = id
            delay(1800)
            highlightedCaseId = null
            onFocusConsumed(id)
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            val dateSummary = when (dateFilter) {
                FILTER_ALL_V29 -> "전체"
                FILTER_PERIOD_V29 -> periodLabel
                else -> dateFilter
            }
            val statusSummary = if (statusFilter == FILTER_ALL_V29) "전체" else statusFilter
            val filterSummary = buildString {
                append(dateSummary).append(" · ").append(statusSummary).append(" · ").append(viewMode)
                if (query.isNotBlank()) append(" · 검색")
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "필터",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    filterSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.testTag("schedule-filter-toggle"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(if (filtersExpanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (filtersExpanded) {
                Spacer(Modifier.height(6.dp))
                Text("날짜 기준", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                DateFiltersV36(dateFilter, periodLabel, onDateFilter, onPeriod)
                Spacer(Modifier.height(9.dp))
                Text("진행상황 기준", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                StatusFiltersV36(statusFilter, statusCounts, onStatusFilter)
                Spacer(Modifier.height(10.dp))
                Text("표시 방식", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    FilterChip(
                        selected = viewMode == SCHEDULE_VIEW_DATE_V36,
                        onClick = { onViewMode(SCHEDULE_VIEW_DATE_V36) },
                        label = { Text("날짜별") },
                        modifier = Modifier.testTag("schedule-view-date")
                    )
                    FilterChip(
                        selected = viewMode == SCHEDULE_VIEW_NUMBER_V36,
                        onClick = { onViewMode(SCHEDULE_VIEW_NUMBER_V36) },
                        label = { Text("조사번호") },
                        modifier = Modifier.testTag("schedule-view-number")
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("관리번호, 채무자, 주소, 예정일 검색") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                val hasActiveFilters = query.isNotBlank() ||
                    dateFilter != FILTER_ALL_V29 ||
                    statusFilter != FILTER_ALL_V29
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (viewMode == SCHEDULE_VIEW_DATE_V36) {
                            TextButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("schedule-sort")) {
                                Text("정렬: ${sort.compactLabel}")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                ScheduleSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text((if (option == sort) "✓ " else "") + option.label) },
                                        onClick = { onSort(option); sortMenu = false },
                                        modifier = Modifier.testTag("schedule-sort-${option.name}")
                                    )
                                }
                            }
                        } else {
                            Text("조사번호 오름차순", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    TextButton(
                        onClick = {
                            onQuery("")
                            onDateFilter(FILTER_ALL_V29)
                            onStatusFilter(FILTER_ALL_V29)
                        },
                        enabled = hasActiveFilters,
                        modifier = Modifier.testTag("schedule-filter-reset")
                    ) {
                        Text("필터 초기화")
                    }
                }
            }
        }
        HorizontalDivider()

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("표시할 일정이 없습니다.") }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("schedule-list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewMode == SCHEDULE_VIEW_NUMBER_V36) {
                    items(numberRows, key = { it.id }) { c ->
                        CaseCardV29(
                            c = c,
                            today = today,
                            highlighted = highlightedCaseId == c.id,
                            menuExpanded = menuCaseId == c.id,
                            onMenu = { menuCaseId = c.id },
                            onDismissMenu = { menuCaseId = null },
                            onOpen = { onEdit(c) },
                            onLocate = { menuCaseId = null; onLocate(c) },
                            onForm = { menuCaseId = null; onForm(c) },
                            onEdit = { menuCaseId = null; onEdit(c) },
                            onStatus = { menuCaseId = null; statusCase = c },
                            onCancel = { menuCaseId = null; onStatus(c, STATUS_CANCELLED_V29) },
                            onSchedule = { menuCaseId = null; scheduleCase = c },
                            onStatusChip = { statusCase = c },
                            onNavigate = { onNavigate(c) },
                            onCall = { onCall(c) }
                        )
                    }
                } else {
                    grouped.forEach { (date, rows) ->
                        item(key = "head-$date") {
                            DateHeaderV29(
                                date = date,
                                count = rows.size
                            )
                        }
                        items(rows, key = { it.id }) { c ->
                            CaseCardV29(
                                c = c,
                                today = today,
                                highlighted = highlightedCaseId == c.id,
                                menuExpanded = menuCaseId == c.id,
                                onMenu = { menuCaseId = c.id },
                                onDismissMenu = { menuCaseId = null },
                                onOpen = { onEdit(c) },
                                onLocate = { menuCaseId = null; onLocate(c) },
                                onForm = { menuCaseId = null; onForm(c) },
                                onEdit = { menuCaseId = null; onEdit(c) },
                                onStatus = { menuCaseId = null; statusCase = c },
                                onCancel = { menuCaseId = null; onStatus(c, STATUS_CANCELLED_V29) },
                                onSchedule = { menuCaseId = null; scheduleCase = c },
                                onStatusChip = { statusCase = c },
                                onNavigate = { onNavigate(c) },
                                onCall = { onCall(c) }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun DateFiltersV36(
    value: String,
    periodLabel: String,
    onChange: (String) -> Unit,
    onPeriod: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        listOf(
            FILTER_ALL_V29,
            FILTER_TODAY_V29,
            FILTER_TOMORROW_V36,
            FILTER_THIS_WEEK_V36,
            FILTER_UNASSIGNED_V36
        ).forEach { filter ->
            FilterChip(
                selected = value == filter,
                onClick = { onChange(filter) },
                label = { Text(if (filter == FILTER_ALL_V29) "전체" else filter) },
                modifier = Modifier.testTag("schedule-date-filter-$filter")
            )
        }
        FilterChip(
            selected = value == FILTER_PERIOD_V29,
            onClick = onPeriod,
            label = {
                Text(
                    if (value == FILTER_PERIOD_V29) "기간 · $periodLabel" else "기간",
                    maxLines = 1
                )
            },
            modifier = Modifier.testTag("schedule-date-filter-period")
        )
    }
}

@Composable
private fun StatusFiltersV36(
    value: String,
    counts: Map<String, Int>,
    onChange: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        listOf(
            FILTER_ALL_V29,
            FILTER_NEW_V36,
            FILTER_IN_PROGRESS_V29,
            FILTER_DELAYED_V29,
            FILTER_CANCELLED_V29,
            FILTER_DONE_V29
        ).forEach { filter ->
            val chipColors = when (filter) {
                FILTER_NEW_V36 -> FilterChipDefaults.filterChipColors(
                    containerColor = statusContainerColorV36(CASE_STATUS_NEW_V36).copy(alpha = .30f),
                    labelColor = statusContentColorV36(CASE_STATUS_NEW_V36),
                    selectedContainerColor = statusContainerColorV36(CASE_STATUS_NEW_V36),
                    selectedLabelColor = statusContentColorV36(CASE_STATUS_NEW_V36)
                )
                FILTER_IN_PROGRESS_V29 -> FilterChipDefaults.filterChipColors(
                    containerColor = statusContainerColorV36(CASE_STATUS_PROGRESS_V36).copy(alpha = .30f),
                    labelColor = statusContentColorV36(CASE_STATUS_PROGRESS_V36),
                    selectedContainerColor = statusContainerColorV36(CASE_STATUS_PROGRESS_V36),
                    selectedLabelColor = statusContentColorV36(CASE_STATUS_PROGRESS_V36)
                )
                FILTER_CANCELLED_V29 -> FilterChipDefaults.filterChipColors(
                    containerColor = statusContainerColorV36(CASE_STATUS_CANCELLED_V36).copy(alpha = .30f),
                    labelColor = statusContentColorV36(CASE_STATUS_CANCELLED_V36),
                    selectedContainerColor = statusContainerColorV36(CASE_STATUS_CANCELLED_V36),
                    selectedLabelColor = statusContentColorV36(CASE_STATUS_CANCELLED_V36)
                )
                FILTER_DONE_V29 -> FilterChipDefaults.filterChipColors(
                    containerColor = statusContainerColorV36(CASE_STATUS_DONE_V36).copy(alpha = .30f),
                    labelColor = statusContentColorV36(CASE_STATUS_DONE_V36),
                    selectedContainerColor = statusContainerColorV36(CASE_STATUS_DONE_V36),
                    selectedLabelColor = statusContentColorV36(CASE_STATUS_DONE_V36)
                )
                else -> FilterChipDefaults.filterChipColors()
            }
            FilterChip(
                selected = value == filter,
                onClick = { onChange(filter) },
                label = {
                    val label = if (filter == FILTER_ALL_V29) "전체" else filter
                    Text("$label ${counts[filter] ?: 0}")
                },
                colors = chipColors,
                modifier = Modifier.testTag("schedule-status-filter-$filter")
            )
        }
    }
}

@Composable
private fun DateHeaderV29(date: String, count: Int) {
    val title = when (date) {
        NO_DATE_V29 -> "예정일 미지정"
        LocalDate.now().toString() -> "오늘 · ${displayDateV29(date)}"
        else -> displayDateV29(date)
    }
    Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 3.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${count}건", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CaseCardV29(
    c: InvestigationCase,
    today: LocalDate,
    highlighted: Boolean = false,
    menuExpanded: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    onForm: () -> Unit,
    onEdit: () -> Unit,
    onStatus: () -> Unit,
    onCancel: () -> Unit,
    onSchedule: () -> Unit,
    onStatusChip: () -> Unit,
    onNavigate: () -> Unit,
    onCall: () -> Unit
) {
    val status = c.status.normalizedStatusV29()
    val hasMarker = status == STATUS_IN_PROGRESS_V29 && c.propertyLatitude != null && c.propertyLongitude != null
    val hasNav = c.propertyAddress.isNotBlank() || c.ownerAddress.isNotBlank()
    val hasPhone = phoneTargetsV29(c).isNotEmpty()
    val warnings = caseWarningsV29(c, today)

    ElevatedCard(
        Modifier.fillMaxWidth().testTag("schedule-case-${c.id}").clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                statusContainerColorV36(status).copy(alpha = .42f)
            }
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.managementNo.ifBlank { "관리번호 없음" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(
                    onClick = onStatusChip,
                    label = { Text(status) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = statusContainerColorV36(status),
                        labelColor = statusContentColorV36(status)
                    )
                )
                Box {
                    TextButton(onClick = onMenu, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("schedule-menu-${c.id}")) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(text = { Text("상세 / 편집") }, onClick = onEdit)
                        DropdownMenuItem(text = { Text("조사의뢰서 보기") }, onClick = onForm)
                        DropdownMenuItem(text = { Text("진행도 변경") }, onClick = onStatus)
                        DropdownMenuItem(text = { Text("의뢰취소") }, onClick = onCancel, modifier = Modifier.testTag("schedule-cancel-${c.id}"))
                        DropdownMenuItem(text = { Text("조사 예정일 변경") }, onClick = onSchedule)
                        DropdownMenuItem(text = { Text(if (hasMarker) "지도에서 보기" else "지도 표시 불가") }, enabled = hasMarker, onClick = onLocate)
                    }
                }
            }

            if (c.defaultAddress().isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(c.defaultAddressLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(c.defaultAddress(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    warnings.forEach { WarningPillV29(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("예정 ${c.plannedDate.takeIf { it.isNotBlank() }?.let(::displayDateV29) ?: "미지정"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (c.dueDate.isNotBlank()) Text("완료요청 ${c.dueDate}", style = MaterialTheme.typography.labelSmall)
            }
            if (c.startedAt != null || c.completedAt != null) {
                Text(
                    buildString {
                        c.startedAt?.let { append("시작 ${displayTimestampV29(it)}") }
                        if (c.startedAt != null && c.completedAt != null) append(" · ")
                        c.completedAt?.let { append("완료 ${displayTimestampV29(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onCall, enabled = hasPhone, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("전화") }
                TextButton(onClick = onForm, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("의뢰서") }
                TextButton(onClick = onLocate, enabled = hasMarker, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("지도") }
                TextButton(onClick = onNavigate, enabled = hasNav, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("길안내") }
            }
        }
    }
}

@Composable
private fun WarningPillV29(text: String) {
    val severe = text.contains("초과") || text.contains("지남")
    Surface(
        shape = RoundedCornerShape(50),
        color = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    ) { Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
}

@Composable
internal fun NavigationFlowDialogV29(c: InvestigationCase, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember(c.id) { mutableStateOf<NavigationTargetV29?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun resolve(addressType: String) {
        busy = true
        error = ""
        scope.launch {
            target = resolveNavigationTargetV29(context, c, addressType)
            if (target == null) error = "주소를 지도 좌표로 변환하지 못했습니다. 주소를 확인하세요."
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target == null) "길안내 목적지 선택" else "길안내 앱 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.managementNo.ifBlank { c.debtorName }, fontWeight = FontWeight.SemiBold)
                if (target == null) {
                    Text("어느 주소로 이동할지 선택하세요.", style = MaterialTheme.typography.bodySmall)
                    if (c.propertyAddress.isNotBlank()) {
                        if (c.normalizedDefaultAddressType() == DEFAULT_ADDRESS_TENANT) {
                            FilledTonalButton(onClick = { resolve(DEFAULT_ADDRESS_TENANT) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("✓ 기본 · 물건소재지", fontWeight = FontWeight.SemiBold)
                                    Text(c.propertyAddress, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { resolve(DEFAULT_ADDRESS_TENANT) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("물건소재지", fontWeight = FontWeight.SemiBold)
                                    Text(c.propertyAddress, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (c.ownerAddress.isNotBlank()) {
                        if (c.normalizedDefaultAddressType() == DEFAULT_ADDRESS_OWNER) {
                            FilledTonalButton(onClick = { resolve(DEFAULT_ADDRESS_OWNER) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("✓ 기본 · 소유자 주소", fontWeight = FontWeight.SemiBold)
                                    Text(c.ownerAddress, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { resolve(DEFAULT_ADDRESS_OWNER) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("소유자 주소", fontWeight = FontWeight.SemiBold)
                                    Text(c.ownerAddress, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (c.customMapAddress.isNotBlank()) {
                        OutlinedButton(onClick = { resolve(DEFAULT_ADDRESS_CUSTOM) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text((if(c.normalizedDefaultAddressType()==DEFAULT_ADDRESS_CUSTOM) "✓ 기본 · " else "") + "직접입력 주소")
                                Text(c.customMapAddress, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${target!!.label} · ${target!!.address}", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { openTmapTargetV29(context, target!!); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("TMAP 길안내") }
                    OutlinedButton(
                        onClick = { openKakaoTargetV29(context, target!!); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("카카오 길안내") }
                    TextButton(onClick = { target = null }, modifier = Modifier.fillMaxWidth()) { Text("주소 다시 선택") }
                }
                HorizontalDivider()
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PhoneChoiceDialogV29(c: InvestigationCase, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val targets = remember(c.id, c.tenantsJson, c.ownerPhone, c.phone, c.mobile) { phoneTargetsV29(c) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전화 대상 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("전화할 대상을 선택하세요.", style = MaterialTheme.typography.bodySmall)
                if (targets.isEmpty()) {
                    Text("저장된 전화번호가 없습니다.")
                } else {
                    targets.forEach { target ->
                        OutlinedButton(
                            onClick = { dialPhoneTargetV29(context, target); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(target.label, fontWeight = FontWeight.SemiBold)
                                Text(target.number, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun CalendarScreenV29(vm: AppViewModel, onBack: () -> Unit, onOpen: (InvestigationCase) -> Unit) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    var month by rememberUiState(stateSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })) { mutableStateOf(YearMonth.now()) }
    var selectedDate by rememberUiState(stateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })) { mutableStateOf(LocalDate.now()) }
    val byDate = remember(cases) { cases.filter { it.plannedDate.isNotBlank() }.groupBy { it.plannedDate } }
    val today = LocalDate.now()
    var actionCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }

    actionCase?.let { c ->
        AlertDialog(
            onDismissRequest = { actionCase = null },
            title = { Text("일정 작업 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } }, fontWeight = FontWeight.SemiBold)
                    if (c.debtorName.isNotBlank()) Text(c.debtorName)
                    Text(c.defaultAddress().ifBlank { "저장된 주소가 없습니다." }, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { actionCase = null; onOpen(c) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("편집") }
                    OutlinedButton(
                        onClick = { actionCase = null; navCase = c },
                        enabled = c.propertyAddress.isNotBlank() || c.ownerAddress.isNotBlank() || c.customMapAddress.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("길찾기") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionCase = null }) { Text("닫기") } }
        )
    }
    navCase?.let { c -> NavigationFlowDialogV29(c = c, onDismiss = { navCase = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("전체 일정 캘린더") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = {
                    TextButton(onClick = { month = month.minusMonths(1); selectedDate = month.atDay(1) }) { Text("‹") }
                    Text("${month.year}년 ${month.monthValue}월", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { month = month.plusMonths(1); selectedDate = month.atDay(1) }) { Text("›") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium) }
            }
            CalendarGridV29(month, selectedDate, today, byDate) { selectedDate = it }
            HorizontalDivider(Modifier.padding(top = 6.dp))
            Text(
                "${displayDateV29(selectedDate.toString())} · ${byDate[selectedDate.toString()].orEmpty().size}건",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(12.dp)
            )
            val rows = byDate[selectedDate.toString()].orEmpty().sortedWith(
                compareBy<InvestigationCase> { statusOrderV29(it.status) }
                    .thenBy { it.managementNo }
                    .thenBy { it.id }
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("이 날짜의 조사 일정이 없습니다.") }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(rows, key = { it.id }) { c ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { actionCase = c }) {
                            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } }, fontWeight = FontWeight.SemiBold)
                                    if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.bodySmall)
                                    Text("${c.defaultAddressLabel()} · ${c.defaultAddress()}", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text(c.status.normalizedStatusV29()) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = statusContainerColorV36(c.status),
                                        labelColor = statusContentColorV36(c.status)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarGridV29(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    byDate: Map<String, List<InvestigationCase>>,
    onSelect: (LocalDate) -> Unit
) {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val total = offset + month.lengthOfMonth()
    val rows = ceil(total / 7.0).toInt()
    Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { col ->
                    val cell = row * 7 + col
                    val day = cell - offset + 1
                    val date = if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                    val cases = date?.let { byDate[it.toString()] }.orEmpty()
                    val active = cases.count { it.status.normalizedStatusV29() !in setOf(STATUS_DONE_V29, STATUS_CANCELLED_V29) }
                    val done = cases.count { it.status.normalizedStatusV29() == STATUS_DONE_V29 }
                    val cancelled = cases.count { it.status.normalizedStatusV29() == STATUS_CANCELLED_V29 }
                    Surface(
                        modifier = Modifier.weight(1f).height(76.dp).clickable(enabled = date != null) { if (date != null) onSelect(date) },
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            date == selected -> MaterialTheme.colorScheme.secondaryContainer
                            date == today -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)
                        }
                    ) {
                        if (date != null) {
                            Column(Modifier.padding(6.dp)) {
                                Text(day.toString(), fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.weight(1f))
                                if (active > 0) Text("진행 $active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (done > 0) Text("완료 $done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (cancelled > 0) Text("취소 $cancelled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrRegisterScreenV29(vm: AppViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = vm.viewModelScope
    val draft = vm.ocrDraft
    var profile by remember(ctx) { mutableStateOf(InvestigatorProfileStore.load(ctx)) }
    var showProfileDialog by remember { mutableStateOf(!profile.isConfigured) }
    var raw by draft.raw
    var showRaw by remember { mutableStateOf(false) }
    var parsed by draft.parsed
    var busy by draft.busy
    var saving by draft.saving
    var source by draft.source
    var cameraFile by draft.cameraFile
    var cameraSource by draft.cameraSource
    var preprocess by draft.preprocess
    var statusMessage by draft.statusMessage
    var showDiagnostics by rememberUiState { mutableStateOf(false) }
    var duplicates by remember { mutableStateOf<List<InvestigationCase>?>(null) }
    var chooseDefaultAddress by rememberUiState { mutableStateOf(false) }

    LaunchedEffect(draft.saved.value) { if (draft.saved.value) onDone() }

    suspend fun persist() {
        saving = true
        statusMessage = "저장 중…"
        var createdCaseId: Long? = null
        var copiedOriginalPath: String? = null
        var cameraOriginalFinalized = false
        try {
            val uri = source ?: error("조사의뢰서 원본을 다시 선택해주세요.")
            val finalCase = profile.applyTo(parsed).copy(status = parsed.status.normalizedStatusV29())
            val id = vm.create(finalCase, scheduleAfterCreate = false)
            createdCaseId = id
            val attachment = if (cameraSource && cameraFile != null) {
                OriginalFileStore.finalizeCamera(cameraFile!!, id, "ORIGINAL_REQUEST").attachment.also {
                    cameraOriginalFinalized = true
                }
            } else {
                OriginalFileStore.copyOriginal(ctx, uri, id, parsed.year, "ORIGINAL_REQUEST").attachment
            }
            copiedOriginalPath = attachment.localPath
            vm.addAttachment(attachment)
            cameraFile = null
            statusMessage = "저장 완료"
            draft.saved.value = true
        } catch (cancelled: CancellationException) {
            createdCaseId?.let { runCatching { vm.rollbackNewCase(it) } }
            copiedOriginalPath?.let { runCatching { File(it).delete() } }
            throw cancelled
        } catch (error: Exception) {
            createdCaseId?.let { runCatching { vm.rollbackNewCase(it) } }
            copiedOriginalPath?.let { runCatching { File(it).delete() } }
            if (cameraOriginalFinalized) {
                cameraFile = null
                source = null
                cameraSource = false
                statusMessage = "저장하지 못했습니다. 카메라 사진을 다시 촬영해주세요."
            } else {
                statusMessage = "저장하지 못했습니다. 원본 파일과 저장공간을 확인한 뒤 다시 시도해주세요."
            }
            preprocess = "저장 실패: ${error.message.orEmpty()}"
        } finally {
            saving = false
        }
    }

    fun checkDuplicatesAndPersist() {
        scope.launch {
            saving = true
            try {
                val found = vm.findDuplicates(parsed)
                if (found.isNotEmpty()) {
                    duplicates = found
                    saving = false
                } else {
                    persist()
                }
            } catch (cancelled: CancellationException) {
                saving = false
                throw cancelled
            } catch (error: Exception) {
                saving = false
                statusMessage = "중복 확인 중 오류가 발생했습니다. 다시 시도해주세요."
                preprocess = "중복 확인 실패: ${error.message.orEmpty()}"
            }
        }
    }

    fun acceptOcr(uri: Uri, fromCamera: Boolean, file: File? = null) {
        draft.job?.cancel()
        val generation = ++draft.generation
        if (cameraFile != file) cameraFile?.delete()
        source = uri
        cameraSource = fromCamera
        cameraFile = file
        busy = true
        raw = ""
        showRaw = false
        showDiagnostics = false
        preprocess = ""
        statusMessage = "문서 분석 중…"
        draft.job = scope.launch {
            runCatching { OcrService.recognizeCase(ctx, uri) }
                .onSuccess { result ->
                    if (generation == draft.generation) {
                        raw = result.rawText
                        parsed = profile.applyTo(result.parsed).copy(status = result.parsed.status.normalizedStatusV29())
                        preprocess = result.preprocessMessage
                        statusMessage = if (result.preprocessMessage.startsWith("선택 오류:")) {
                            result.preprocessMessage.substringBefore(" / ")
                        } else {
                            "인식 완료. 입력 내용을 확인해주세요."
                        }
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (generation == draft.generation) {
                        statusMessage = "문서를 읽지 못했습니다. 사진을 다시 선택해주세요."
                        preprocess = "OCR 실패: ${it.message.orEmpty()}"
                    }
                }
            if (generation == draft.generation) busy = false
        }
    }

    if (showProfileDialog) InvestigatorProfileDialog(
        initial = profile,
        onDismiss = { showProfileDialog = false },
        onSave = { value ->
            InvestigatorProfileStore.save(ctx, value)
            profile = InvestigatorProfileStore.load(ctx)
            parsed = profile.applyTo(parsed)
            vm.applyInvestigatorProfile(profile)
            showProfileDialog = false
        }
    )

    if (chooseDefaultAddress) DefaultAddressChoiceDialog(
        value = parsed,
        allowCustom = false,
        onDismiss = { chooseDefaultAddress = false },
        onSelect = { selection ->
            parsed = selection
            chooseDefaultAddress = false
            checkDuplicatesAndPersist()
        }
    )

    duplicates?.let { rows ->
        AlertDialog(
            onDismissRequest = { duplicates = null },
            title = { Text("중복 의뢰 가능성") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("이미 비슷한 조사건이 저장되어 있습니다.")
                    rows.take(4).forEach { old ->
                        Text("• ${old.managementNo.ifBlank { "관리번호 없음" }} / ${old.debtorName}\n  ${old.propertyAddress}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { duplicates = null; scope.launch { persist() } }) { Text("그래도 저장") } },
            dismissButton = { OutlinedButton(onClick = { duplicates = null }) { Text("취소") } }
        )
    }

    val photos = rememberDocumentPhotoActions(LocalDate.now().year,
        onPhoto = { uri, file -> acceptOcr(uri, fromCamera = file != null, file = file) },
        onError = { statusMessage = it; preprocess = ""; showDiagnostics = false })
    val warnings = remember(parsed) { ocrWarningsV29(parsed) }

    Scaffold(topBar = { TopAppBar(title = { Text("조사의뢰서 등록") }, navigationIcon = { TextButton(enabled = !saving, onClick = { cameraFile?.delete(); onCancel() }) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("조사의뢰서를 가져올 방법을 선택하세요.", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = photos.choosePhoto,
                            enabled = !busy && !saving,
                            modifier = Modifier.weight(1f)
                        ) { Text("갤러리에서 선택") }
                        OutlinedButton(
                            onClick = photos.takePhoto,
                            enabled = !busy && !saving,
                            modifier = Modifier.weight(1f)
                        ) { Text("카메라 촬영") }
                    }
                    Text("두 방법 모두 원본은 변경하지 않고 기기 안에 보관합니다.", style = MaterialTheme.typography.labelSmall)
                    if (busy || saving) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }
            if (statusMessage.isNotBlank()) Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp).testTag("ocr-status"))
            if (warnings.isNotEmpty() && source != null && !busy) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Text("OCR 검수 필요 ${warnings.size}개", fontWeight = FontWeight.SemiBold)
                        warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            EditFields(parsed, fixedInvestigator = true) { parsed = profile.applyTo(it) }
            PlannedDateFieldV29(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            StatusChoiceV29(parsed.status) { parsed = parsed.copy(status = it) }
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = source != null && profile.isConfigured && !busy && !saving,
                onClick = { chooseDefaultAddress = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (warnings.isEmpty()) "검수 완료 및 저장" else "확인 후 저장") }
            if (!profile.isConfigured) {
                TextButton(onClick = { showProfileDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("고정 조사담당자 먼저 설정")
                }
            }
            OutlinedButton(enabled = raw.isNotBlank(), onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showRaw) "OCR 원문 숨기기" else "OCR 원문 보기")
            }
            if (showRaw && raw.isNotBlank()) {
                HorizontalDivider(Modifier.padding(top = 10.dp))
                Text(raw, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (preprocess.isNotBlank() && !busy) {
                TextButton(onClick = { showDiagnostics = !showDiagnostics },
                    modifier = Modifier.fillMaxWidth().testTag("ocr-diagnostics-toggle")) {
                    Text(if (showDiagnostics) "인식 진단 숨기기" else "인식 진단 보기")
                }
                if (showDiagnostics) {
                    Text(preprocess, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("ocr-diagnostics"))
                }
            }
        }
    }
}

@Composable
private fun StatusChoiceV29(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text("진행도", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            STATUS_VALUES_V29.forEach { status ->
                FilterChip(
                    selected = value.normalizedStatusV29() == status,
                    onClick = { onChange(status) },
                    label = { Text(status) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = statusContainerColorV36(status).copy(alpha = .30f),
                        labelColor = statusContentColorV36(status),
                        selectedContainerColor = statusContainerColorV36(status),
                        selectedLabelColor = statusContentColorV36(status)
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusDialogV29(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("진행도 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                STATUS_VALUES_V29.forEach { status ->
                    val selected = current.normalizedStatusV29() == status
                    Button(
                        onClick = { onSelect(status) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = statusContainerColorV36(status),
                            contentColor = statusContentColorV36(status)
                        )
                    ) { Text((if (selected) "✓ " else "") + status) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
internal fun PlannedDateFieldV29(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var show by rememberUiState { mutableStateOf(false) }
    if (show) PlannedDateDialogV29(value, { show = false }) { show = false; onChange(it) }
    OutlinedTextField(
        value = value.takeIf { it.isNotBlank() }?.let(::displayDateV29).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("조사 예정일") },
        placeholder = { Text("미지정") },
        trailingIcon = { TextButton(onClick = { show = true }, modifier = Modifier.testTag("planned-date-open")) { Text("선택") } },
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("planned-date-field")
    )
}

@Composable
private fun PlannedDateDialogV29(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = dateMillisV29(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, modifier = Modifier.testTag("planned-date-confirm"), onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                onSave(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
            }) { Text("확인") }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) TextButton(onClick = { onSave("") }, modifier = Modifier.testTag("planned-date-clear")) { Text("미지정") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    ) { DatePicker(state = state) }
}

@Composable
private fun SchedulePeriodDialogV33(
    initialStart: String,
    initialEnd: String,
    onDismiss: () -> Unit,
    onApply: (String, String) -> Unit
) {
    val today = LocalDate.now()
    fun millis(value: String): Long = (value.toDateV29() ?: today)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = millis(initialStart),
        initialSelectedEndDateMillis = millis(initialEnd)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val startMillis = state.selectedStartDateMillis ?: millis(today.toString())
                val endMillis = state.selectedEndDateMillis ?: startMillis
                val start = Instant.ofEpochMilli(minOf(startMillis, endMillis)).atZone(ZoneOffset.UTC).toLocalDate().toString()
                val end = Instant.ofEpochMilli(maxOf(startMillis, endMillis)).atZone(ZoneOffset.UTC).toLocalDate().toString()
                onApply(start, end)
            }) { Text("조회") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    ) {
        DateRangePicker(
            state = state,
            title = { Text("조회 기간 지정", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = null,
            showModeToggle = false
        )
    }
}

private fun schedulePeriodLabelV36(
    filter: String,
    periodStart: String,
    periodEnd: String,
    today: LocalDate
): String = when (filter) {
    FILTER_ALL_V29 -> "전체"
    FILTER_TODAY_V29 -> displayDateV29(today.toString())
    FILTER_TOMORROW_V36 -> displayDateV29(today.plusDays(1).toString())
    FILTER_THIS_WEEK_V36 -> "이번주"
    FILTER_UNASSIGNED_V36 -> "미지정"
    else -> if (periodStart == periodEnd) displayDateV29(periodStart)
    else "${displayDateV29(periodStart)} ~ ${displayDateV29(periodEnd)}"
}

internal fun scheduleMatchesStatusV36(
    c: InvestigationCase,
    filter: String,
    today: LocalDate
): Boolean = when (filter) {
    FILTER_ALL_V29 -> true
    FILTER_NEW_V36 -> c.status.normalizedStatusV29() == STATUS_NEW_V29
    FILTER_IN_PROGRESS_V29 -> c.status.normalizedStatusV29() == STATUS_IN_PROGRESS_V29
    FILTER_DELAYED_V29 -> c.status.normalizedStatusV29() !in setOf(STATUS_DONE_V29, STATUS_CANCELLED_V29) &&
        caseWarningsV29(c, today).any { it.contains("초과") || it.contains("지남") }
    FILTER_CANCELLED_V29 -> c.status.normalizedStatusV29() == STATUS_CANCELLED_V29
    FILTER_DONE_V29 -> c.status.normalizedStatusV29() == STATUS_DONE_V29
    else -> true
}

internal fun scheduleStatusCountsV36(
    rows: List<InvestigationCase>,
    today: LocalDate
): Map<String, Int> = listOf(
    FILTER_ALL_V29,
    FILTER_NEW_V36,
    FILTER_IN_PROGRESS_V29,
    FILTER_DELAYED_V29,
    FILTER_CANCELLED_V29,
    FILTER_DONE_V29
).associateWith { filter ->
    rows.count { c -> scheduleMatchesStatusV36(c, filter, today) }
}

private fun ocrWarningsV29(c: InvestigationCase): List<String> = buildList {
    if (c.managementNo.isBlank()) add("관리번호가 비어 있습니다.")
    if (c.requestDate.isBlank()) add("의뢰일을 확인하세요.")
    if (c.debtorName.isBlank()) add("채무자명이 비어 있습니다.")
    else if (!Regex("\\(\\d{6}(?:-\\*)?\\)").containsMatchIn(c.debtorName)) add("채무자 생년월일을 확인하세요.")
    if (c.propertyAddress.isBlank()) add("물건소재지가 비어 있습니다.")
    if (c.branch.isBlank()) add("농협 영업점 정보가 비어 있습니다.")
    if (c.requester.isBlank()) add("조사의뢰자 정보가 비어 있습니다.")
}

private fun caseWarningsV29(c: InvestigationCase, today: LocalDate): List<String> = buildList {
    if (c.status.normalizedStatusV29() in setOf(STATUS_DONE_V29, STATUS_CANCELLED_V29)) return@buildList
    val planned = c.plannedDate.toDateV29()
    val due = c.dueDate.toDateV29()
    if (c.plannedDate.isBlank()) add("예정일 미지정")
    if (planned != null && planned.isBefore(today)) add("조사예정일 지남")
    if (due != null) {
        val days = ChronoUnit.DAYS.between(today, due)
        when {
            days < 0 -> add("완료요청일 초과")
            days in 0..2 -> add("완료요청일 임박")
        }
    }
}

private const val STATUS_NEW_V29 = CASE_STATUS_NEW_V36
private const val STATUS_IN_PROGRESS_V29 = CASE_STATUS_PROGRESS_V36
private const val STATUS_CANCELLED_V29 = CASE_STATUS_CANCELLED_V36
private const val STATUS_DONE_V29 = CASE_STATUS_DONE_V36
private val STATUS_VALUES_V29 = CASE_STATUS_VALUES_V36
private const val SCHEDULE_VIEW_DATE_V36 = "날짜별"
private const val SCHEDULE_VIEW_NUMBER_V36 = "조사번호"
private const val FILTER_PERIOD_V29 = "조회기간"
private const val FILTER_ALL_V29 = "전체보기"
private const val FILTER_TODAY_V29 = "오늘"
private const val FILTER_TOMORROW_V36 = "내일"
private const val FILTER_THIS_WEEK_V36 = "이번주"
private const val FILTER_UNASSIGNED_V36 = "미지정"
private const val FILTER_NEW_V36 = "신규"
private const val FILTER_IN_PROGRESS_V29 = "진행중"
private const val FILTER_DELAYED_V29 = "지연"
private const val FILTER_CANCELLED_V29 = "의뢰취소"
private const val FILTER_DONE_V29 = "완료"
private const val NO_DATE_V29 = "__NO_DATE_V29__"
private const val MAP_SIZE_SMALL_V31 = 0
private const val MAP_SIZE_NORMAL_V31 = 1
private const val MAP_SIZE_LARGE_V31 = 2
private val MAP_WIDTH_FRACTIONS_V31 = listOf(.38f, .50f, .60f)
private val displayDateFormatterV29 = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val timestampFormatterV29 = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN)

private fun String.normalizedStatusV29(): String = normalizeCaseStatusV36(this)

private fun statusOrderV29(value: String): Int = when (value.normalizedStatusV29()) {
    STATUS_NEW_V29 -> 0
    STATUS_IN_PROGRESS_V29 -> 1
    STATUS_CANCELLED_V29 -> 2
    else -> 3
}

private fun String.toDateV29(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
private fun displayDateV29(value: String): String = runCatching { LocalDate.parse(value).format(displayDateFormatterV29) }.getOrDefault(value)
private fun dateMillisV29(value: String): Long? = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
private fun displayTimestampV29(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatterV29)
