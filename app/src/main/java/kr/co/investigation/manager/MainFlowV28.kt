@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.app.Activity
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

/**
 * v0.28
 * 문서 -> 일정 -> 동선 -> 지도/내비 -> 현장 시작/완료 흐름을 한 화면에서 관리한다.
 */
@Composable
fun InvestigationAppV28(vm: AppViewModel) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf("main") }
    var formReturn by remember { mutableStateOf("main") }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    fun goBack() {
        screen = when (screen) {
            "attachment" -> "detail"
            "form" -> formReturn
            "detail", "ocr", "settings", "patches" -> "main"
            else -> "main"
        }
    }

    BackHandler(enabled = true) {
        when {
            confirmExit -> confirmExit = false
            screen == "main" -> confirmExit = true
            else -> goBack()
        }
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
        "main" -> MainScreenV28(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" },
            onPatchHistory = { screen = "patches" }
        )
        "ocr" -> OcrRegisterScreenV28(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
        "detail" -> vm.selected.collectAsStateWithLifecycle().value?.let {
            DetailScreen(
                vm = vm,
                c0 = it,
                onBack = { screen = "main" },
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
private fun MainScreenV28(
    vm: AppViewModel,
    onNew: () -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onSettings: () -> Unit,
    onPatchHistory: () -> Unit
) {
    val context = LocalContext.current
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mobileTab by rememberSaveable { mutableIntStateOf(0) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var quickFilter by rememberSaveable { mutableStateOf(FILTER_ALL_V28) }
    var moreMenu by remember { mutableStateOf(false) }
    var routeDate by remember { mutableStateOf<String?>(null) }
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var nextAfterComplete by remember { mutableStateOf<InvestigationCase?>(null) }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val todayDate = LocalDate.now()
    val today = todayDate.toString()
    val tomorrow = todayDate.plusDays(1).toString()
    val weekStart = todayDate.minusDays((todayDate.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val weekEnd = weekStart.plusDays(6)

    routeDate?.let { date ->
        val routeItems = cases.filter {
            it.plannedDate == date && it.status.normalizedStatusV28() != STATUS_DONE_V28
        }
        RoutePlannerDialogV28(
            date = date,
            items = routeItems,
            onDismiss = { routeDate = null },
            onSave = {
                routeDate = null
                vm.saveRouteOrder(it)
            }
        )
    }

    navCase?.let { c ->
        NavigationChoiceDialogV28(c = c, onDismiss = { navCase = null })
    }

    nextAfterComplete?.let { c ->
        AlertDialog(
            onDismissRequest = { nextAfterComplete = null },
            title = { Text("다음 조사 안내") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("현재 건을 완료 처리했습니다.")
                    Text(
                        buildString {
                            if (c.routeOrder > 0) append("다음 ${c.routeOrder}번 · ") else append("다음 일정 · ")
                            append(c.managementNo.ifBlank { c.debtorName })
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(c.propertyAddress, style = MaterialTheme.typography.bodySmall)
                    Text("바로 길안내를 시작할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = c.propertyLatitude != null && c.propertyLongitude != null,
                        onClick = { openTmapV28(context, c); nextAfterComplete = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("TMAP으로 다음 목적지") }
                    OutlinedButton(
                        enabled = c.propertyLatitude != null && c.propertyLongitude != null,
                        onClick = { openKakaoRouteV28(context, c); nextAfterComplete = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("카카오로 다음 목적지") }
                }
            },
            dismissButton = { TextButton(onClick = { nextAfterComplete = null }) { Text("나중에") } }
        )
    }

    val searched = remember(cases, query) {
        cases.filter { c ->
            query.isBlank() || listOf(
                c.managementNo, c.debtorName, c.propertyAddress, c.phone, c.mobile,
                c.plannedDate, c.branch, c.status
            ).any { it.contains(query, true) }
        }
    }

    val quickFiltered = remember(searched, quickFilter, today, tomorrow, weekStart, weekEnd) {
        searched.filter { c ->
            when (quickFilter) {
                FILTER_TODAY_V28 -> c.plannedDate == today
                FILTER_TOMORROW_V28 -> c.plannedDate == tomorrow
                FILTER_WEEK_V28 -> c.plannedDate.toLocalDateOrNullV28()?.let { !it.isBefore(weekStart) && !it.isAfter(weekEnd) } == true
                FILTER_UNASSIGNED_V28 -> c.plannedDate.isBlank()
                FILTER_DELAYED_V28 -> caseWarningsV28(c, todayDate).any { it.contains("초과") || it.contains("지남") }
                else -> true
            }
        }
    }

    val listItems = remember(quickFiltered, showCompleted) {
        quickFiltered.filter { showCompleted || it.status.normalizedStatusV28() != STATUS_DONE_V28 }
    }
    val mapItems = remember(quickFiltered) {
        quickFiltered.filter { it.status.normalizedStatusV28() == STATUS_IN_PROGRESS_V28 }
    }

    val todayCount = remember(cases, today) {
        cases.count { it.plannedDate == today && it.status.normalizedStatusV28() != STATUS_DONE_V28 }
    }
    val newCount = remember(cases) { cases.count { it.status.normalizedStatusV28() == STATUS_NEW_V28 } }
    val progressCount = remember(cases) { cases.count { it.status.normalizedStatusV28() == STATUS_IN_PROGRESS_V28 } }
    val completedCount = remember(cases) { cases.count { it.status.normalizedStatusV28() == STATUS_DONE_V28 } }
    val delayedCount = remember(cases, todayDate) {
        cases.count { c ->
            c.status.normalizedStatusV28() != STATUS_DONE_V28 &&
                caseWarningsV28(c, todayDate).any { it.contains("초과") || it.contains("지남") }
        }
    }
    val unassignedCount = remember(cases) {
        cases.count { it.plannedDate.isBlank() && it.status.normalizedStatusV28() != STATUS_DONE_V28 }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("조사 일정", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${year}년 · ${plannedDateDisplayV28(today)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹") }
                    TextButton(onClick = { vm.setYear(year + 1) }) { Text("›") }
                    Box {
                        TextButton(onClick = { moreMenu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("패치내역") }, onClick = { moreMenu = false; onPatchHistory() })
                            DropdownMenuItem(text = { Text("데이터 관리") }, onClick = { moreMenu = false; onSettings() })
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (compact) {
                NavigationBar {
                    NavigationBarItem(
                        selected = mobileTab == 0,
                        onClick = { mobileTab = 0 },
                        icon = { Text("≡", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("일정") }
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
            ExtendedFloatingActionButton(
                onClick = onNew,
                text = { Text("신규 등록") },
                icon = { Text("＋", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                SchedulePaneV28(
                    items = listItems,
                    query = query,
                    onQuery = { query = it },
                    quickFilter = quickFilter,
                    onQuickFilter = { quickFilter = it },
                    todayCount = todayCount,
                    newCount = newCount,
                    progressCount = progressCount,
                    completedCount = completedCount,
                    delayedCount = delayedCount,
                    unassignedCount = unassignedCount,
                    showCompleted = showCompleted,
                    onToggleCompleted = { showCompleted = !showCompleted },
                    onLocate = {
                        vm.select(it)
                        if (!wide) mobileTab = 1
                    },
                    onEdit = onEdit,
                    onForm = onForm,
                    onNavigate = { navCase = it },
                    onCall = { dialCaseV28(context, it) },
                    onStatus = { c, status ->
                        when (status) {
                            STATUS_IN_PROGRESS_V28 -> vm.startInvestigation(c)
                            STATUS_DONE_V28 -> vm.completeInvestigation(c)
                            else -> vm.update(c.copy(status = STATUS_NEW_V28, startedAt = null, completedAt = null))
                        }
                    },
                    onSchedule = { c, date -> vm.update(c.copy(plannedDate = date, routeOrder = 0)) },
                    onRoute = { routeDate = it },
                    onStart = { vm.startInvestigation(it) },
                    onComplete = { c ->
                        val next = nextCaseForRouteV28(cases, c)
                        vm.completeInvestigation(c)
                        nextAfterComplete = next
                    },
                    modifier = modifier
                )
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    listPane(Modifier.weight(.46f))
                    VerticalDivider()
                    NativeMapPaneV28(mapItems, selected, Modifier.weight(.54f))
                }
            } else {
                if (mobileTab == 0) listPane(Modifier.fillMaxSize())
                else NativeMapPaneV28(mapItems, selected, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SchedulePaneV28(
    items: List<InvestigationCase>,
    query: String,
    onQuery: (String) -> Unit,
    quickFilter: String,
    onQuickFilter: (String) -> Unit,
    todayCount: Int,
    newCount: Int,
    progressCount: Int,
    completedCount: Int,
    delayedCount: Int,
    unassignedCount: Int,
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
        PlannedDateDialogV28(
            initial = c.plannedDate,
            onDismiss = { scheduleCase = null },
            onSave = { date -> scheduleCase = null; onSchedule(c, date) }
        )
    }
    statusCase?.let { c ->
        StatusDialogV28(
            current = c.status,
            onDismiss = { statusCase = null },
            onSelect = { status -> statusCase = null; onStatus(c, status) }
        )
    }

    val grouped = remember(items) {
        items.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate.isBlank() }
                .thenBy { it.plannedDate }
                .thenBy { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                .thenBy { statusOrderV28(it.status) }
                .thenBy { it.dueDate }
                .thenByDescending { it.id }
        ).groupBy { it.plannedDate.ifBlank { NO_PLANNED_DATE_V28 } }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            ScheduleSummaryV28(todayCount, progressCount, newCount, completedCount, delayedCount, unassignedCount)
            Spacer(Modifier.height(10.dp))
            QuickFiltersV28(quickFilter, onQuickFilter)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("관리번호, 채무자, 주소, 예정일 검색") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "날짜별 동선 · 지도는 진행중만 표시",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = showCompleted,
                    onClick = onToggleCompleted,
                    label = { Text(if (showCompleted) "완료 숨기기" else "완료 $completedCount") }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (grouped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("표시할 일정이 없습니다.", style = MaterialTheme.typography.titleMedium)
                    Text("필터 또는 검색조건을 확인하세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (groupDate, rows) ->
                    item(key = "date-header-v28-$groupDate") {
                        DateHeaderV28(
                            groupDate = groupDate,
                            count = rows.size,
                            routeCount = rows.count { it.status.normalizedStatusV28() != STATUS_DONE_V28 },
                            onRoute = if (groupDate != NO_PLANNED_DATE_V28) ({ onRoute(groupDate) }) else null
                        )
                    }
                    items(rows, key = { "case-v28-${it.id}" }) { c ->
                        CaseCardV28(
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
private fun ScheduleSummaryV28(today: Int, progress: Int, fresh: Int, done: Int, delayed: Int, unassigned: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCardV28("오늘", today)
        SummaryCardV28("진행중", progress)
        SummaryCardV28("지연", delayed)
        SummaryCardV28("미지정", unassigned)
        SummaryCardV28("신규", fresh)
        SummaryCardV28("완료", done)
    }
}

@Composable
private fun SummaryCardV28(label: String, count: Int) {
    Surface(
        color = if (label == "지연" && count > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (label == "지연" && count > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuickFiltersV28(value: String, onChange: (String) -> Unit) {
    val filters = listOf(FILTER_ALL_V28, FILTER_TODAY_V28, FILTER_TOMORROW_V28, FILTER_WEEK_V28, FILTER_UNASSIGNED_V28, FILTER_DELAYED_V28)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        filters.forEach { filter ->
            FilterChip(selected = value == filter, onClick = { onChange(filter) }, label = { Text(filter) })
        }
    }
}

@Composable
private fun DateHeaderV28(groupDate: String, count: Int, routeCount: Int, onRoute: (() -> Unit)?) {
    val today = LocalDate.now().toString()
    val title = when (groupDate) {
        NO_PLANNED_DATE_V28 -> "예정일 미지정"
        today -> "오늘 · ${plannedDateDisplayV28(groupDate)}"
        else -> plannedDateDisplayV28(groupDate)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, start = 3.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${count}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRoute != null && routeCount > 1) {
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = onRoute,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("동선") }
        }
    }
}

@Composable
private fun CaseCardV28(
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
    val status = c.status.normalizedStatusV28()
    val hasMarker = status == STATUS_IN_PROGRESS_V28 && c.propertyLatitude != null && c.propertyLongitude != null
    val hasNav = c.propertyLatitude != null && c.propertyLongitude != null
    val hasPhone = c.mobile.isNotBlank() || c.phone.isNotBlank() || c.ownerPhone.isNotBlank()
    val warnings = caseWarningsV28(c, today)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (c.routeOrder > 0 && c.plannedDate.isNotBlank()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Text(c.routeOrder.toString(), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        c.managementNo.ifBlank { "관리번호 없음" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                        DropdownMenuItem(
                            text = { Text(if (hasMarker) "지도에서 보기" else "지도 표시 불가") },
                            enabled = hasMarker,
                            onClick = onLocate
                        )
                    }
                }
            }

            if (c.propertyAddress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(c.propertyAddress, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (c.branch.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(c.branch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    warnings.forEach { warning -> WarningPillV28(warning) }
                }
            }

            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val planned = c.plannedDate.takeIf { it.isNotBlank() }?.let(::plannedDateDisplayV28) ?: "미지정"
                Text(
                    "예정 $planned",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (c.plannedDate == LocalDate.now().toString()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (c.dueDate.isNotBlank()) Text("완료요청 ${c.dueDate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (c.startedAt != null || c.completedAt != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        c.startedAt?.let { append("시작 ${timestampDisplayV28(it)}") }
                        if (c.startedAt != null && c.completedAt != null) append(" · ")
                        c.completedAt?.let { append("완료 ${timestampDisplayV28(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onCall, enabled = hasPhone, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("전화") }
                TextButton(onClick = onForm, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("의뢰서") }
                TextButton(onClick = onLocate, enabled = hasMarker, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("지도") }
                TextButton(onClick = onNavigate, enabled = hasNav, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("길안내") }
            }

            when (status) {
                STATUS_NEW_V28 -> FilledTonalButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("조사 시작") }
                STATUS_IN_PROGRESS_V28 -> Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("조사 완료") }
            }
        }
    }
}

@Composable
private fun WarningPillV28(text: String) {
    val severe = text.contains("초과") || text.contains("지남")
    Surface(
        shape = RoundedCornerShape(50),
        color = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (severe) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun RoutePlannerDialogV28(
    date: String,
    items: List<InvestigationCase>,
    onDismiss: () -> Unit,
    onSave: (List<InvestigationCase>) -> Unit
) {
    val ordered = remember(date, items.map { "${it.id}:${it.routeOrder}" }) {
        items.sortedWith(
            compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                .thenBy { it.id }
        ).toMutableStateList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${plannedDateDisplayV28(date)} 동선") },
        text = {
            Column {
                Text("방문순서를 직접 바꾸거나 거리순으로 자동 정렬하세요.", style = MaterialTheme.typography.bodySmall)
                Text("거리순 정렬은 현재 1번 위치를 출발점으로 가까운 곳을 이어 붙입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val sorted = autoRouteOrderV28(ordered.toList())
                        ordered.clear(); ordered.addAll(sorted)
                    },
                    enabled = ordered.count { it.propertyLatitude != null && it.propertyLongitude != null } >= 2,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("거리순 자동정렬") }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(ordered, key = { _, c -> c.id }) { index, c ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text((index + 1).toString(), fontWeight = FontWeight.Bold) }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.managementNo.ifBlank { c.debtorName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(c.propertyAddress.ifBlank { "주소 없음" }, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val moved = ordered.removeAt(index)
                                        ordered.add(index - 1, moved)
                                    }
                                ) { Text("↑") }
                                TextButton(
                                    enabled = index < ordered.lastIndex,
                                    onClick = {
                                        val moved = ordered.removeAt(index)
                                        ordered.add(index + 1, moved)
                                    }
                                ) { Text("↓") }
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
private fun NavigationChoiceDialogV28(c: InvestigationCase, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.managementNo.ifBlank { "길안내" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (c.debtorName.isNotBlank()) Text(c.debtorName, fontWeight = FontWeight.SemiBold)
                Text(c.propertyAddress)
                Text("길안내 앱을 선택하세요.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { openTmapV28(context, c); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("TMAP 길안내") }
                OutlinedButton(onClick = { openKakaoRouteV28(context, c); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("카카오 길안내") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun OcrRegisterScreenV28(vm: AppViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
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
        val id = vm.create(parsed.copy(status = parsed.status.normalizedStatusV28()))
        source?.let {
            val a = OriginalFileStore.copyOriginal(ctx, it, id, parsed.year, "ORIGINAL_REQUEST").attachment
            vm.db.attachments().insert(a)
        }
        saving = false
        onDone()
    }

    duplicates?.let { rows ->
        AlertDialog(
            onDismissRequest = { duplicates = null },
            title = { Text("중복 의뢰 가능성") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("이미 비슷한 조사건이 저장되어 있습니다.")
                    rows.take(4).forEach { old ->
                        Text("• ${old.managementNo.ifBlank { "관리번호 없음" }} / ${old.debtorName}\n  ${old.propertyAddress}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("같은 문서를 다시 등록하는 것이 맞는지 확인하세요.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { duplicates = null; scope.launch { persist() } }) { Text("그래도 저장") }
            },
            dismissButton = { OutlinedButton(onClick = { duplicates = null }) { Text("취소") } }
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            source = uri
            busy = true
            showRaw = false
            raw = ""
            preprocess = "문서 분석 중..."
            scope.launch {
                runCatching { OcrService.recognizeCase(ctx, uri) }
                    .onSuccess { r ->
                        raw = r.rawText
                        parsed = r.parsed.copy(status = r.parsed.status.normalizedStatusV28())
                        preprocess = r.preprocessMessage
                    }
                    .onFailure { preprocess = "OCR 실패: ${it.message.orEmpty()}" }
                busy = false
            }
        }
    }

    val warnings = remember(parsed) { ocrWarningsV28(parsed) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("조사의뢰서 등록") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("뒤로") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("문서 사진을 선택하면 내용을 자동으로 읽습니다.", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy && !saving) { Text("조사의뢰서 사진 선택") }
                    if (busy || saving) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            if (preprocess.isNotBlank()) Text(preprocess, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            if (source != null && !busy) {
                Text("자동인식 결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("강조된 검수 항목을 확인한 뒤 저장하세요.", style = MaterialTheme.typography.bodySmall)
            }

            if (warnings.isNotEmpty() && source != null && !busy) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("OCR 검수 필요 ${warnings.size}개", fontWeight = FontWeight.SemiBold)
                        warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            EditFields(parsed) { parsed = it }
            PlannedDateFieldV28(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            StatusChoiceV28(parsed.status) { parsed = parsed.copy(status = it) }
            Spacer(Modifier.height(16.dp))

            Button(
                enabled = source != null && !busy && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val found = vm.findDuplicates(parsed)
                        if (found.isNotEmpty()) {
                            duplicates = found
                            saving = false
                        } else {
                            persist()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (warnings.isEmpty()) "검수 완료 및 저장" else "확인 후 저장") }

            OutlinedButton(
                enabled = raw.isNotBlank(),
                onClick = { showRaw = !showRaw },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showRaw) "OCR 원문 숨기기" else "OCR 원문 보기") }

            if (showRaw && raw.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Text("OCR 원문 (진단용)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                Text(raw, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusChoiceV28(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("진행도", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STATUS_VALUES_V28.forEach { status ->
                FilterChip(selected = value.normalizedStatusV28() == status, onClick = { onChange(status) }, label = { Text(status) })
            }
        }
    }
}

@Composable
private fun StatusDialogV28(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("진행도 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                STATUS_VALUES_V28.forEach { status ->
                    val selected = current.normalizedStatusV28() == status
                    if (selected) Button(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text("✓ $status") }
                    else OutlinedButton(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text(status) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun PlannedDateFieldV28(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) PlannedDateDialogV28(initial = value, onDismiss = { show = false }, onSave = { show = false; onChange(it) })
    OutlinedTextField(
        value = value.takeIf { it.isNotBlank() }?.let(::plannedDateDisplayV28).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("조사 예정일") },
        placeholder = { Text("미지정") },
        trailingIcon = { TextButton(onClick = { show = true }) { Text("선택") } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun PlannedDateDialogV28(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = plannedDateMillisV28(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onSave(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
            ) { Text("확인") }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) TextButton(onClick = { onSave("") }) { Text("미지정") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    ) { DatePicker(state = state) }
}

private fun ocrWarningsV28(c: InvestigationCase): List<String> = buildList {
    if (c.managementNo.isBlank()) add("관리번호가 비어 있습니다.")
    else if (c.managementNo.firstOrNull()?.let { it == 'i' || it == 'I' || it == 'l' || it == 'ㅣ' } == true) add("관리번호 앞 불필요 문자를 확인하세요: ${c.managementNo}")
    if (c.requestDate.isBlank()) add("의뢰일을 확인하세요.")
    if (c.debtorName.isBlank()) add("채무자명이 비어 있습니다.")
    if (c.propertyAddress.isBlank()) add("물건소재지가 비어 있어 지도 표시가 불가능합니다.")
    if (c.branch.isBlank()) add("농협 영업점 정보가 비어 있습니다.")
    if (c.requester.isBlank()) add("조사의뢰자 정보가 비어 있습니다.")
    if (c.investigatorPhone.isBlank()) add("조사담당자 전화번호를 확인하세요.")
}

private fun caseWarningsV28(c: InvestigationCase, today: LocalDate): List<String> = buildList {
    if (c.status.normalizedStatusV28() == STATUS_DONE_V28) return@buildList
    val planned = c.plannedDate.toLocalDateOrNullV28()
    val due = c.dueDate.toLocalDateOrNullV28()
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

private fun nextCaseForRouteV28(all: List<InvestigationCase>, current: InvestigationCase): InvestigationCase? {
    if (current.plannedDate.isBlank()) return null
    val active = all.filter {
        it.id != current.id && it.plannedDate == current.plannedDate && it.status.normalizedStatusV28() != STATUS_DONE_V28
    }.sortedWith(compareBy<InvestigationCase> { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }.thenBy { it.id })
    if (active.isEmpty()) return null
    if (current.routeOrder > 0) return active.firstOrNull { it.routeOrder > current.routeOrder } ?: active.firstOrNull()
    return active.firstOrNull()
}

private fun autoRouteOrderV28(source: List<InvestigationCase>): List<InvestigationCase> {
    if (source.size < 2) return source
    val located = source.filter { it.propertyLatitude != null && it.propertyLongitude != null }.toMutableList()
    val noLocation = source.filter { it.propertyLatitude == null || it.propertyLongitude == null }
    if (located.size < 2) return source

    val first = source.firstOrNull { it.propertyLatitude != null && it.propertyLongitude != null } ?: located.first()
    located.removeAll { it.id == first.id }
    val result = mutableListOf(first)
    var current = first
    while (located.isNotEmpty()) {
        val next = located.minByOrNull { routeDistanceV28(current, it) } ?: break
        result += next
        located.remove(next)
        current = next
    }
    result += noLocation
    return result
}

private fun routeDistanceV28(a: InvestigationCase, b: InvestigationCase): Double {
    val lat1 = Math.toRadians(a.propertyLatitude ?: return Double.MAX_VALUE)
    val lon1 = Math.toRadians(a.propertyLongitude ?: return Double.MAX_VALUE)
    val lat2 = Math.toRadians(b.propertyLatitude ?: return Double.MAX_VALUE)
    val lon2 = Math.toRadians(b.propertyLongitude ?: return Double.MAX_VALUE)
    val dLat = lat2 - lat1
    val dLon = lon2 - lon1
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 6371.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
}

private const val STATUS_NEW_V28 = "신규"
private const val STATUS_IN_PROGRESS_V28 = "진행중"
private const val STATUS_DONE_V28 = "완료"
private val STATUS_VALUES_V28 = listOf(STATUS_NEW_V28, STATUS_IN_PROGRESS_V28, STATUS_DONE_V28)
private const val NO_PLANNED_DATE_V28 = "__NO_PLANNED_DATE_V28__"
private const val FILTER_ALL_V28 = "전체"
private const val FILTER_TODAY_V28 = "오늘"
private const val FILTER_TOMORROW_V28 = "내일"
private const val FILTER_WEEK_V28 = "이번주"
private const val FILTER_UNASSIGNED_V28 = "미지정"
private const val FILTER_DELAYED_V28 = "지연"
private val plannedDateFormatterV28 = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val timestampFormatterV28 = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN)

private fun String.normalizedStatusV28(): String = when (trim()) {
    STATUS_IN_PROGRESS_V28 -> STATUS_IN_PROGRESS_V28
    STATUS_DONE_V28 -> STATUS_DONE_V28
    else -> STATUS_NEW_V28
}

private fun statusOrderV28(value: String): Int = when (value.normalizedStatusV28()) {
    STATUS_NEW_V28 -> 0
    STATUS_IN_PROGRESS_V28 -> 1
    else -> 2
}

private fun plannedDateDisplayV28(value: String): String = runCatching {
    LocalDate.parse(value).format(plannedDateFormatterV28)
}.getOrDefault(value)

private fun plannedDateMillisV28(value: String): Long? = runCatching {
    LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()

private fun String.toLocalDateOrNullV28(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

private fun timestampDisplayV28(value: Long): String = runCatching {
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatterV28)
}.getOrDefault("")
