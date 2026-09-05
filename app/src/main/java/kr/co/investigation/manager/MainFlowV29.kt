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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.ocr.OcrService
import kr.co.investigation.manager.storage.OriginalFileStore
import java.time.DayOfWeek
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
    var screen by remember { mutableStateOf("main") }
    var formReturn by remember { mutableStateOf("main") }
    var detailReturn by remember { mutableStateOf("main") }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(!prefs.getBoolean("v029_guide_seen", false)) }

    fun closeGuide() {
        prefs.edit().putBoolean("v029_guide_seen", true).apply()
        showGuide = false
    }

    fun goBack() {
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

    when (screen) {
        "main" -> MainScreenV29(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); detailReturn = "main"; screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" },
            onPatchHistory = { screen = "patches" },
            onCalendar = { screen = "calendar" },
            onDataSheet = { screen = "datasheet" },
            onGuide = { showGuide = true }
        )
        "calendar" -> CalendarScreenV29(
            vm = vm,
            onBack = { screen = "main" },
            onOpen = { vm.select(it); detailReturn = "calendar"; screen = "detail" }
        )
        "datasheet" -> DataSheetScreenV31(
            vm = vm,
            onBack = { screen = "main" },
            onOpen = { vm.select(it); detailReturn = "datasheet"; screen = "detail" }
        )
        "ocr" -> OcrRegisterScreenV29(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
        "detail" -> vm.selected.collectAsStateWithLifecycle().value?.let {
            DetailScreen(
                vm = vm,
                c0 = it,
                onBack = { screen = detailReturn },
                onForm = { formReturn = "detail"; screen = "form" },
                onAttachment = { att -> viewingAttachment = att; screen = "attachment" }
            )
        }
        "form" -> vm.selected.collectAsStateWithLifecycle().value?.let {
            RequestFormScreen(it, onBack = { screen = formReturn })
        }
        "attachment" -> viewingAttachment?.let {
            AttachmentViewerScreen(it, onBack = { screen = "detail" })
        }
        "settings" -> SettingsScreen(vm, onBack = { screen = "main" })
        "patches" -> PatchHistoryScreen(onBack = { screen = "main" })
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
                Text("문서 → 일정 → 동선 → 지도/내비 → 조사 완료 순서로 사용합니다.", fontWeight = FontWeight.SemiBold)
                Text("1. 신규 등록에서 종이 조사의뢰서를 촬영한 사진을 선택합니다.")
                Text("2. OCR 결과를 확인하고 조사 예정일과 진행도를 지정합니다.")
                Text("3. 일정 화면의 오늘/내일/이번주 필터로 방문할 건을 확인합니다.")
                Text("4. 같은 날짜의 ‘동선’ 버튼에서 방문순서를 정하거나 거리순 자동정렬합니다.")
                Text("5. 진행중 건은 카카오맵에 표시되며 마커의 간단정보로 대상을 구분할 수 있습니다.")
                Text("6. 길안내는 물건 소재지 또는 소유자 주소를 고른 뒤 TMAP/카카오를 선택합니다.")
                Text("7. 전화는 임차인·물건 소유자·채무자 중 저장된 번호를 선택합니다.")
                Text("8. 캘린더에서는 월 전체 조사 일정을 한눈에 확인합니다.")
                Text("9. 전체 데이터시트에서는 모든 연도의 저장 건을 필터링하고 화면 크기를 조절해 확인합니다.")
                Text("10. 태블릿 가로 분할 화면에서는 지도 위 ‘지도 폭’ 버튼으로 지도 크기를 조절합니다.")
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
    onGuide: () -> Unit
) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mobileTab by rememberSaveable { mutableIntStateOf(0) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var quickFilter by rememberSaveable { mutableStateOf(FILTER_ALL_V29) }
    var moreMenu by remember { mutableStateOf(false) }
    var routeDate by remember { mutableStateOf<String?>(null) }
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var callCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var nextAfterComplete by remember { mutableStateOf<InvestigationCase?>(null) }
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
    val tomorrow = todayDate.plusDays(1).toString()
    val weekStart = todayDate.minusDays((todayDate.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val weekEnd = weekStart.plusDays(6)

    routeDate?.let { date ->
        RoutePlannerDialogV29(
            date = date,
            items = cases.filter { it.plannedDate == date && it.status.normalizedStatusV29() != STATUS_DONE_V29 },
            onDismiss = { routeDate = null },
            onSave = { routeDate = null; vm.saveRouteOrder(it) }
        )
    }

    navCase?.let { c ->
        NavigationFlowDialogV29(c = c, onDismiss = { navCase = null })
    }
    callCase?.let { c ->
        PhoneChoiceDialogV29(c = c, onDismiss = { callCase = null })
    }
    nextAfterComplete?.let { next ->
        AlertDialog(
            onDismissRequest = { nextAfterComplete = null },
            title = { Text("다음 조사") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("현재 건을 완료했습니다.")
                    Text(
                        buildString {
                            if (next.routeOrder > 0) append("${next.routeOrder}번 · ")
                            append(next.managementNo.ifBlank { next.debtorName })
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(next.propertyAddress, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { nextAfterComplete = null; navCase = next }) { Text("다음 목적지 길안내") }
            },
            dismissButton = { TextButton(onClick = { nextAfterComplete = null }) { Text("나중에") } }
        )
    }

    val searched = remember(cases, query) {
        cases.filter { c ->
            query.isBlank() || listOf(
                c.managementNo, c.debtorName, c.propertyAddress, c.ownerAddress,
                c.phone, c.mobile, c.plannedDate, c.branch, c.status
            ).any { it.contains(query, true) }
        }
    }
    val quickFiltered = remember(searched, quickFilter, today, tomorrow, weekStart, weekEnd) {
        searched.filter { c ->
            when (quickFilter) {
                FILTER_TODAY_V29 -> c.plannedDate == today
                FILTER_TOMORROW_V29 -> c.plannedDate == tomorrow
                FILTER_WEEK_V29 -> c.plannedDate.toDateV29()?.let { !it.isBefore(weekStart) && !it.isAfter(weekEnd) } == true
                FILTER_UNASSIGNED_V29 -> c.plannedDate.isBlank()
                FILTER_DELAYED_V29 -> caseWarningsV29(c, todayDate).any { it.contains("초과") || it.contains("지남") }
                else -> true
            }
        }
    }
    val listItems = remember(quickFiltered, showCompleted) {
        quickFiltered.filter { showCompleted || it.status.normalizedStatusV29() != STATUS_DONE_V29 }
    }
    val mapItems = remember(quickFiltered) {
        quickFiltered.filter { it.status.normalizedStatusV29() == STATUS_IN_PROGRESS_V29 }
    }

    val todayCount = cases.count { it.plannedDate == today && it.status.normalizedStatusV29() != STATUS_DONE_V29 }
    val newCount = cases.count { it.status.normalizedStatusV29() == STATUS_NEW_V29 }
    val progressCount = cases.count { it.status.normalizedStatusV29() == STATUS_IN_PROGRESS_V29 }
    val completedCount = cases.count { it.status.normalizedStatusV29() == STATUS_DONE_V29 }
    val delayedCount = cases.count { c ->
        c.status.normalizedStatusV29() != STATUS_DONE_V29 && caseWarningsV29(c, todayDate).any { it.contains("초과") || it.contains("지남") }
    }
    val unassignedCount = cases.count { it.plannedDate.isBlank() && it.status.normalizedStatusV29() != STATUS_DONE_V29 }

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
                        TextButton(onClick = { moreMenu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("캘린더") }, onClick = { moreMenu = false; onCalendar() })
                            DropdownMenuItem(text = { Text("전체 데이터시트") }, onClick = { moreMenu = false; onDataSheet() })
                            DropdownMenuItem(text = { Text("사용방법") }, onClick = { moreMenu = false; onGuide() })
                            DropdownMenuItem(text = { Text("패치내역") }, onClick = { moreMenu = false; onPatchHistory() })
                            DropdownMenuItem(text = { Text("데이터 관리") }, onClick = { moreMenu = false; onSettings() })
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
            ExtendedFloatingActionButton(onClick = onNew, text = { Text("신규 등록") }, icon = { Text("＋") })
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
                    quickFilter = quickFilter,
                    onQuickFilter = { quickFilter = it },
                    counts = SummaryCountsV29(todayCount, progressCount, delayedCount, unassignedCount, newCount, completedCount),
                    showCompleted = showCompleted,
                    onToggleCompleted = { showCompleted = !showCompleted },
                    onLocate = { vm.select(it); if (!wide) mobileTab = 1 },
                    onEdit = onEdit,
                    onForm = onForm,
                    onNavigate = { navCase = it },
                    onCall = { callCase = it },
                    onStatus = { c, status ->
                        when (status) {
                            STATUS_IN_PROGRESS_V29 -> vm.startInvestigation(c)
                            STATUS_DONE_V29 -> vm.completeInvestigation(c)
                            else -> vm.update(c.copy(status = STATUS_NEW_V29, startedAt = null, completedAt = null))
                        }
                    },
                    onSchedule = { c, date -> vm.update(c.copy(plannedDate = date, routeOrder = 0)) },
                    onRoute = { routeDate = it },
                    onStart = { vm.startInvestigation(it) },
                    onComplete = { c ->
                        val next = nextCaseV29(cases, c)
                        vm.completeInvestigation(c)
                        nextAfterComplete = next
                    },
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

private data class SummaryCountsV29(
    val today: Int,
    val progress: Int,
    val delayed: Int,
    val unassigned: Int,
    val fresh: Int,
    val done: Int
)

@Composable
private fun SchedulePaneV29(
    items: List<InvestigationCase>,
    query: String,
    onQuery: (String) -> Unit,
    quickFilter: String,
    onQuickFilter: (String) -> Unit,
    counts: SummaryCountsV29,
    showCompleted: Boolean,
    onToggleCompleted: () -> Unit,
    onLocate: (InvestigationCase) -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onNavigate: (InvestigationCase) -> Unit,
    onCall: (InvestigationCase) -> Unit,
    onStatus: (InvestigationCase, String) -> Unit,
    onSchedule: (InvestigationCase, String) -> Unit,
    onRoute: (String) -> Unit,
    onStart: (InvestigationCase) -> Unit,
    onComplete: (InvestigationCase) -> Unit,
    modifier: Modifier
) {
    var menuCaseId by remember { mutableStateOf<Long?>(null) }
    var scheduleCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var statusCase by remember { mutableStateOf<InvestigationCase?>(null) }
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

    val grouped = remember(items) {
        items.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate.isBlank() }
                .thenBy { it.plannedDate }
                .thenBy { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                .thenBy { statusOrderV29(it.status) }
                .thenBy { it.dueDate }
                .thenByDescending { it.id }
        ).groupBy { it.plannedDate.ifBlank { NO_DATE_V29 } }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            SummaryRowV29(counts)
            Spacer(Modifier.height(9.dp))
            QuickFiltersV29(quickFilter, onQuickFilter)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("관리번호, 채무자, 주소, 예정일 검색") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("날짜별 동선 · 지도는 진행중만 표시", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilterChip(selected = showCompleted, onClick = onToggleCompleted, label = { Text(if (showCompleted) "완료 숨기기" else "완료 ${counts.done}") })
            }
        }
        HorizontalDivider()

        if (grouped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("표시할 일정이 없습니다.") }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (date, rows) ->
                    item(key = "head-$date") {
                        DateHeaderV29(
                            date = date,
                            count = rows.size,
                            routeCount = rows.count { it.status.normalizedStatusV29() != STATUS_DONE_V29 },
                            onRoute = if (date != NO_DATE_V29) ({ onRoute(date) }) else null
                        )
                    }
                    items(rows, key = { it.id }) { c ->
                        CaseCardV29(
                            c = c,
                            today = today,
                            menuExpanded = menuCaseId == c.id,
                            onMenu = { menuCaseId = c.id },
                            onDismissMenu = { menuCaseId = null },
                            onOpen = { onEdit(c) },
                            onLocate = { menuCaseId = null; onLocate(c) },
                            onForm = { menuCaseId = null; onForm(c) },
                            onEdit = { menuCaseId = null; onEdit(c) },
                            onStatus = { menuCaseId = null; statusCase = c },
                            onSchedule = { menuCaseId = null; scheduleCase = c },
                            onStatusChip = { statusCase = c },
                            onNavigate = { onNavigate(c) },
                            onCall = { onCall(c) },
                            onStart = { onStart(c) },
                            onComplete = { onComplete(c) }
                        )
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryRowV29(c: SummaryCountsV29) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(
            "오늘" to c.today,
            "진행중" to c.progress,
            "지연" to c.delayed,
            "미지정" to c.unassigned,
            "신규" to c.fresh,
            "완료" to c.done
        ).forEach { (label, count) ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (label == "지연" && count > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(count.toString(), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun QuickFiltersV29(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(FILTER_ALL_V29, FILTER_TODAY_V29, FILTER_TOMORROW_V29, FILTER_WEEK_V29, FILTER_UNASSIGNED_V29, FILTER_DELAYED_V29).forEach {
            FilterChip(selected = value == it, onClick = { onChange(it) }, label = { Text(it) })
        }
    }
}

@Composable
private fun DateHeaderV29(date: String, count: Int, routeCount: Int, onRoute: (() -> Unit)?) {
    val title = when (date) {
        NO_DATE_V29 -> "예정일 미지정"
        LocalDate.now().toString() -> "오늘 · ${displayDateV29(date)}"
        else -> displayDateV29(date)
    }
    Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 3.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${count}건", style = MaterialTheme.typography.labelMedium)
        if (onRoute != null && routeCount > 1) {
            Spacer(Modifier.width(5.dp))
            FilledTonalButton(onClick = onRoute, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("동선") }
        }
    }
}

@Composable
private fun CaseCardV29(
    c: InvestigationCase,
    today: LocalDate,
    menuExpanded: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    onForm: () -> Unit,
    onEdit: () -> Unit,
    onStatus: () -> Unit,
    onSchedule: () -> Unit,
    onStatusChip: () -> Unit,
    onNavigate: () -> Unit,
    onCall: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit
) {
    val status = c.status.normalizedStatusV29()
    val hasMarker = status == STATUS_IN_PROGRESS_V29 && c.propertyLatitude != null && c.propertyLongitude != null
    val hasNav = c.propertyAddress.isNotBlank() || c.ownerAddress.isNotBlank()
    val hasPhone = phoneTargetsV29(c).isNotEmpty()
    val warnings = caseWarningsV29(c, today)

    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (c.routeOrder > 0 && c.plannedDate.isNotBlank()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Text(c.routeOrder.toString(), fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.managementNo.ifBlank { "관리번호 없음" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = onStatusChip, label = { Text(status) })
                Box {
                    TextButton(onClick = onMenu, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(text = { Text("상세 / 편집") }, onClick = onEdit)
                        DropdownMenuItem(text = { Text("조사의뢰서 보기") }, onClick = onForm)
                        DropdownMenuItem(text = { Text("진행도 변경") }, onClick = onStatus)
                        DropdownMenuItem(text = { Text("조사 예정일 변경") }, onClick = onSchedule)
                        DropdownMenuItem(text = { Text(if (hasMarker) "지도에서 보기" else "지도 표시 불가") }, enabled = hasMarker, onClick = onLocate)
                    }
                }
            }

            if (c.propertyAddress.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(c.propertyAddress, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            when (status) {
                STATUS_NEW_V29 -> FilledTonalButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("조사 시작") }
                STATUS_IN_PROGRESS_V29 -> Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("조사 완료") }
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
private fun NavigationFlowDialogV29(c: InvestigationCase, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember(c.id) { mutableStateOf<NavigationTargetV29?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun resolve(owner: Boolean) {
        busy = true
        error = ""
        scope.launch {
            target = resolveNavigationTargetV29(context, c, owner)
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
                        FilledTonalButton(onClick = { resolve(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("물건 소재지", fontWeight = FontWeight.SemiBold)
                                Text(c.propertyAddress, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (c.ownerAddress.isNotBlank()) {
                        OutlinedButton(onClick = { resolve(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("소유자 주소", fontWeight = FontWeight.SemiBold)
                                Text(c.ownerAddress, style = MaterialTheme.typography.labelSmall)
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
private fun RoutePlannerDialogV29(
    date: String,
    items: List<InvestigationCase>,
    onDismiss: () -> Unit,
    onSave: (List<InvestigationCase>) -> Unit
) {
    val ordered = remember(date, items.map { "${it.id}:${it.routeOrder}" }) {
        items.sortedWith(compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }.thenBy { it.id }).toMutableStateList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${displayDateV29(date)} 동선") },
        text = {
            Column {
                Text("방문순서를 바꾸거나 거리순으로 자동 정렬하세요.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(7.dp))
                FilledTonalButton(
                    onClick = {
                        val sorted = autoRouteV29(ordered.toList())
                        ordered.clear(); ordered.addAll(sorted)
                    },
                    enabled = ordered.count { it.propertyLatitude != null && it.propertyLongitude != null } >= 2,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("거리순 자동정렬") }
                Spacer(Modifier.height(7.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(ordered, key = { _, c -> c.id }) { index, c ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Box(Modifier.size(31.dp), contentAlignment = Alignment.Center) { Text((index + 1).toString(), fontWeight = FontWeight.Bold) }
                                }
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.managementNo.ifBlank { c.debtorName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(c.propertyAddress, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(enabled = index > 0, onClick = {
                                    val moved = ordered.removeAt(index); ordered.add(index - 1, moved)
                                }) { Text("↑") }
                                TextButton(enabled = index < ordered.lastIndex, onClick = {
                                    val moved = ordered.removeAt(index); ordered.add(index + 1, moved)
                                }) { Text("↓") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(ordered.toList()) }, enabled = ordered.isNotEmpty()) { Text("순서 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun CalendarScreenV29(vm: AppViewModel, onBack: () -> Unit, onOpen: (InvestigationCase) -> Unit) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val byDate = remember(cases) { cases.filter { it.plannedDate.isNotBlank() }.groupBy { it.plannedDate } }
    val today = LocalDate.now()

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
                compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                    .thenBy { statusOrderV29(it.status) }
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("이 날짜의 조사 일정이 없습니다.") }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(rows, key = { it.id }) { c ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(c) }) {
                            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (c.routeOrder > 0) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(c.routeOrder.toString(), fontWeight = FontWeight.Bold) }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } }, fontWeight = FontWeight.SemiBold)
                                    if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.bodySmall)
                                    Text(c.propertyAddress, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                AssistChip(onClick = {}, label = { Text(c.status.normalizedStatusV29()) })
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
                    val active = cases.count { it.status.normalizedStatusV29() != STATUS_DONE_V29 }
                    val done = cases.size - active
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(InvestigationCase(year = LocalDate.now().year)) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var preprocess by remember { mutableStateOf("") }
    var duplicates by remember { mutableStateOf<List<InvestigationCase>?>(null) }

    suspend fun persist() {
        saving = true
        val id = vm.create(parsed.copy(status = parsed.status.normalizedStatusV29()))
        source?.let {
            val attachment = OriginalFileStore.copyOriginal(ctx, it, id, parsed.year, "ORIGINAL_REQUEST").attachment
            vm.db.attachments().insert(attachment)
        }
        saving = false
        onDone()
    }

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            source = uri
            busy = true
            raw = ""
            showRaw = false
            preprocess = "문서 분석 중..."
            scope.launch {
                runCatching { OcrService.recognizeCase(ctx, uri) }
                    .onSuccess { r ->
                        raw = r.rawText
                        parsed = r.parsed.copy(status = r.parsed.status.normalizedStatusV29())
                        preprocess = r.preprocessMessage
                    }
                    .onFailure { preprocess = "OCR 실패: ${it.message.orEmpty()}" }
                busy = false
            }
        }
    }
    val warnings = remember(parsed) { ocrWarningsV29(parsed) }

    Scaffold(topBar = { TopAppBar(title = { Text("조사의뢰서 등록") }, navigationIcon = { TextButton(onClick = onCancel) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("종이 조사의뢰서 사진을 선택하세요.", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy && !saving) { Text("조사의뢰서 사진 선택") }
                    if (busy || saving) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }
            if (preprocess.isNotBlank()) Text(preprocess, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (warnings.isNotEmpty() && source != null && !busy) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Text("OCR 검수 필요 ${warnings.size}개", fontWeight = FontWeight.SemiBold)
                        warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            EditFields(parsed) { parsed = it }
            PlannedDateFieldV29(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            StatusChoiceV29(parsed.status) { parsed = parsed.copy(status = it) }
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = source != null && !busy && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val found = vm.findDuplicates(parsed)
                        if (found.isNotEmpty()) { duplicates = found; saving = false } else persist()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (warnings.isEmpty()) "검수 완료 및 저장" else "확인 후 저장") }
            OutlinedButton(enabled = raw.isNotBlank(), onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showRaw) "OCR 원문 숨기기" else "OCR 원문 보기")
            }
            if (showRaw && raw.isNotBlank()) {
                HorizontalDivider(Modifier.padding(top = 10.dp))
                Text(raw, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
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
                FilterChip(selected = value.normalizedStatusV29() == status, onClick = { onChange(status) }, label = { Text(status) })
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
                    if (current.normalizedStatusV29() == status) Button(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text("✓ $status") }
                    else OutlinedButton(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text(status) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun PlannedDateFieldV29(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) PlannedDateDialogV29(value, { show = false }) { show = false; onChange(it) }
    OutlinedTextField(
        value = value.takeIf { it.isNotBlank() }?.let(::displayDateV29).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("조사 예정일") },
        placeholder = { Text("미지정") },
        trailingIcon = { TextButton(onClick = { show = true }) { Text("선택") } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
private fun PlannedDateDialogV29(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = dateMillisV29(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                onSave(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
            }) { Text("확인") }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) TextButton(onClick = { onSave("") }) { Text("미지정") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    ) { DatePicker(state = state) }
}

private fun ocrWarningsV29(c: InvestigationCase): List<String> = buildList {
    if (c.managementNo.isBlank()) add("관리번호가 비어 있습니다.")
    if (c.requestDate.isBlank()) add("의뢰일을 확인하세요.")
    if (c.debtorName.isBlank()) add("채무자명이 비어 있습니다.")
    if (c.propertyAddress.isBlank()) add("물건소재지가 비어 있습니다.")
    if (c.branch.isBlank()) add("농협 영업점 정보가 비어 있습니다.")
    if (c.requester.isBlank()) add("조사의뢰자 정보가 비어 있습니다.")
    if (c.investigatorPhone.isBlank()) add("조사담당자 전화번호를 확인하세요.")
}

private fun caseWarningsV29(c: InvestigationCase, today: LocalDate): List<String> = buildList {
    if (c.status.normalizedStatusV29() == STATUS_DONE_V29) return@buildList
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

private fun nextCaseV29(all: List<InvestigationCase>, current: InvestigationCase): InvestigationCase? {
    if (current.plannedDate.isBlank()) return null
    return all.filter {
        it.id != current.id &&
            it.plannedDate == current.plannedDate &&
            it.status.normalizedStatusV29() != STATUS_DONE_V29
    }.sortedWith(compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }.thenBy { it.id })
        .firstOrNull { current.routeOrder <= 0 || it.routeOrder > current.routeOrder }
        ?: all.filter {
            it.id != current.id && it.plannedDate == current.plannedDate && it.status.normalizedStatusV29() != STATUS_DONE_V29
        }.minByOrNull { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
}

private fun autoRouteV29(items: List<InvestigationCase>): List<InvestigationCase> {
    if (items.size < 2) return items
    val withCoords = items.filter { it.propertyLatitude != null && it.propertyLongitude != null }.toMutableList()
    val withoutCoords = items.filter { it.propertyLatitude == null || it.propertyLongitude == null }
    if (withCoords.size < 2) return items

    val result = mutableListOf<InvestigationCase>()
    var current = withCoords.removeAt(0)
    result += current
    while (withCoords.isNotEmpty()) {
        val next = withCoords.minByOrNull {
            distanceKmV29(current.propertyLatitude!!, current.propertyLongitude!!, it.propertyLatitude!!, it.propertyLongitude!!)
        }!!
        withCoords.remove(next)
        result += next
        current = next
    }
    result += withoutCoords
    return result
}

private fun distanceKmV29(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

private const val STATUS_NEW_V29 = "신규"
private const val STATUS_IN_PROGRESS_V29 = "진행중"
private const val STATUS_DONE_V29 = "완료"
private val STATUS_VALUES_V29 = listOf(STATUS_NEW_V29, STATUS_IN_PROGRESS_V29, STATUS_DONE_V29)
private const val FILTER_ALL_V29 = "전체"
private const val FILTER_TODAY_V29 = "오늘"
private const val FILTER_TOMORROW_V29 = "내일"
private const val FILTER_WEEK_V29 = "이번주"
private const val FILTER_UNASSIGNED_V29 = "미지정"
private const val FILTER_DELAYED_V29 = "지연"
private const val NO_DATE_V29 = "__NO_DATE_V29__"
private const val MAP_SIZE_SMALL_V31 = 0
private const val MAP_SIZE_NORMAL_V31 = 1
private const val MAP_SIZE_LARGE_V31 = 2
private val MAP_WIDTH_FRACTIONS_V31 = listOf(.38f, .50f, .60f)
private val displayDateFormatterV29 = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val timestampFormatterV29 = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN)

private fun String.normalizedStatusV29(): String = when (trim()) {
    STATUS_IN_PROGRESS_V29 -> STATUS_IN_PROGRESS_V29
    STATUS_DONE_V29 -> STATUS_DONE_V29
    else -> STATUS_NEW_V29
}

private fun statusOrderV29(value: String): Int = when (value.normalizedStatusV29()) {
    STATUS_NEW_V29 -> 0
    STATUS_IN_PROGRESS_V29 -> 1
    else -> 2
}

private fun String.toDateV29(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
private fun displayDateV29(value: String): String = runCatching { LocalDate.parse(value).format(displayDateFormatterV29) }.getOrDefault(value)
private fun dateMillisV29(value: String): Long? = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
private fun displayTimestampV29(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatterV29)
