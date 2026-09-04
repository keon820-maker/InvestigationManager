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
import androidx.compose.foundation.rememberScrollState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v0.27
 * - 일정 중심 홈 UI
 * - 모바일 하단 일정/지도 전환
 * - 시스템 뒤로가기 처리 + 홈 종료 확인
 * - 완료 기본 숨김 및 진행중 지도 정책 유지
 */
@Composable
fun InvestigationAppV27(vm: AppViewModel) {
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
        if (screen == "main") confirmExit = true else goBack()
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
            dismissButton = {
                OutlinedButton(onClick = { confirmExit = false }) { Text("아니요") }
            }
        )
    }

    when (screen) {
        "main" -> MainScreenV27(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" },
            onPatchHistory = { screen = "patches" }
        )
        "ocr" -> OcrRegisterScreenV27(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
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
private fun MainScreenV27(
    vm: AppViewModel,
    onNew: () -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onSettings: () -> Unit,
    onPatchHistory: () -> Unit
) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mobileTab by rememberSaveable { mutableIntStateOf(0) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val today = LocalDate.now().toString()

    val searched = remember(cases, query) {
        cases.filter { c ->
            query.isBlank() || listOf(
                c.managementNo, c.debtorName, c.propertyAddress, c.phone, c.mobile,
                c.plannedDate, c.branch, c.status
            ).any { it.contains(query, true) }
        }
    }
    val listItems = remember(searched, showCompleted) {
        searched.filter { showCompleted || it.status.normalizedStatusV27() != STATUS_DONE_V27 }
    }
    val mapItems = remember(searched) {
        searched.filter { it.status.normalizedStatusV27() == STATUS_IN_PROGRESS_V27 }
    }

    val todayCount = remember(cases, today) {
        cases.count { it.plannedDate == today && it.status.normalizedStatusV27() != STATUS_DONE_V27 }
    }
    val newCount = remember(cases) { cases.count { it.status.normalizedStatusV27() == STATUS_NEW_V27 } }
    val progressCount = remember(cases) { cases.count { it.status.normalizedStatusV27() == STATUS_IN_PROGRESS_V27 } }
    val completedCount = remember(cases) { cases.count { it.status.normalizedStatusV27() == STATUS_DONE_V27 } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("조사 일정", fontWeight = FontWeight.SemiBold)
                        Text(
                            "$year년 · ${plannedDateDisplayV27(today)}",
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
                            DropdownMenuItem(
                                text = { Text("패치내역") },
                                onClick = { moreMenu = false; onPatchHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("데이터 관리") },
                                onClick = { moreMenu = false; onSettings() }
                            )
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
                SchedulePaneV27(
                    items = listItems,
                    query = query,
                    onQuery = { query = it },
                    todayCount = todayCount,
                    newCount = newCount,
                    progressCount = progressCount,
                    completedCount = completedCount,
                    showCompleted = showCompleted,
                    onToggleCompleted = { showCompleted = !showCompleted },
                    onSelect = { vm.select(it) },
                    onLocate = {
                        vm.select(it)
                        if (!wide) mobileTab = 1
                    },
                    onEdit = onEdit,
                    onForm = onForm,
                    onStatus = { c, status -> vm.update(c.copy(status = status)) },
                    onSchedule = { c, date -> vm.update(c.copy(plannedDate = date)) },
                    modifier = modifier
                )
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    listPane(Modifier.weight(.46f))
                    VerticalDivider()
                    NativeMapPane(mapItems, selected, Modifier.weight(.54f))
                }
            } else {
                if (mobileTab == 0) listPane(Modifier.fillMaxSize())
                else NativeMapPane(mapItems, selected, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SchedulePaneV27(
    items: List<InvestigationCase>,
    query: String,
    onQuery: (String) -> Unit,
    todayCount: Int,
    newCount: Int,
    progressCount: Int,
    completedCount: Int,
    showCompleted: Boolean,
    onToggleCompleted: () -> Unit,
    onSelect: (InvestigationCase) -> Unit,
    onLocate: (InvestigationCase) -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onStatus: (InvestigationCase, String) -> Unit,
    onSchedule: (InvestigationCase, String) -> Unit,
    modifier: Modifier
) {
    var menuCaseId by remember { mutableStateOf<Long?>(null) }
    var scheduleCase by remember { mutableStateOf<InvestigationCase?>(null) }
    var statusCase by remember { mutableStateOf<InvestigationCase?>(null) }

    scheduleCase?.let { c ->
        PlannedDateDialogV27(
            initial = c.plannedDate,
            onDismiss = { scheduleCase = null },
            onSave = { date -> scheduleCase = null; onSchedule(c, date) }
        )
    }
    statusCase?.let { c ->
        StatusDialogV27(
            current = c.status,
            onDismiss = { statusCase = null },
            onSelect = { status -> statusCase = null; onStatus(c, status) }
        )
    }

    val grouped = remember(items) {
        items.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate.isBlank() }
                .thenBy { it.plannedDate }
                .thenBy { statusOrderV27(it.status) }
                .thenBy { it.dueDate }
                .thenByDescending { it.id }
        ).groupBy { it.plannedDate.ifBlank { NO_PLANNED_DATE_V27 } }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            ScheduleSummaryV27(todayCount, newCount, progressCount, completedCount)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("관리번호, 채무자, 주소, 예정일 검색") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "예정일 순으로 표시 · 지도는 진행중만 표시",
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
                    Spacer(Modifier.height(4.dp))
                    Text("신규 등록 또는 검색조건을 확인하세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (groupDate, rows) ->
                    item(key = "date-header-v27-$groupDate") {
                        DateHeaderV27(groupDate, rows.size)
                    }
                    items(rows, key = { "case-v27-${it.id}" }) { c ->
                        CaseCardV27(
                            c = c,
                            menuExpanded = menuCaseId == c.id,
                            onMenu = { menuCaseId = c.id },
                            onDismissMenu = { menuCaseId = null },
                            onOpen = { onSelect(c); onEdit(c) },
                            onLocate = { menuCaseId = null; onLocate(c) },
                            onForm = { menuCaseId = null; onForm(c) },
                            onEdit = { menuCaseId = null; onEdit(c) },
                            onStatus = { menuCaseId = null; statusCase = c },
                            onSchedule = { menuCaseId = null; scheduleCase = c },
                            onStatusChip = { statusCase = c }
                        )
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun ScheduleSummaryV27(today: Int, fresh: Int, progress: Int, done: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCardV27("오늘", today, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SummaryCardV27("진행중", progress, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        SummaryCardV27("신규", fresh, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        SummaryCardV27("완료", done, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun SummaryCardV27(label: String, count: Int, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(16.dp)) {
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
private fun DateHeaderV27(groupDate: String, count: Int) {
    val today = LocalDate.now().toString()
    val title = when (groupDate) {
        NO_PLANNED_DATE_V27 -> "예정일 미지정"
        today -> "오늘 · ${plannedDateDisplayV27(groupDate)}"
        else -> plannedDateDisplayV27(groupDate)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, start = 3.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${count}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CaseCardV27(
    c: InvestigationCase,
    menuExpanded: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    onForm: () -> Unit,
    onEdit: () -> Unit,
    onStatus: () -> Unit,
    onSchedule: () -> Unit,
    onStatusChip: () -> Unit
) {
    val status = c.status.normalizedStatusV27()
    val hasMarker = status == STATUS_IN_PROGRESS_V27 && c.propertyLatitude != null && c.propertyLongitude != null

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        c.managementNo.ifBlank { "관리번호 없음" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (c.debtorName.isNotBlank()) {
                        Text(c.debtorName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AssistChip(onClick = onStatusChip, label = { Text(status) })
                Box {
                    TextButton(onClick = onMenu, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(
                            text = { Text(if (hasMarker) "지도에서 보기" else if (status != STATUS_IN_PROGRESS_V27) "진행중일 때 지도 표시" else "지도 좌표 없음") },
                            enabled = hasMarker,
                            onClick = onLocate
                        )
                        DropdownMenuItem(text = { Text("조사의뢰서 보기") }, onClick = onForm)
                        DropdownMenuItem(text = { Text("상세 / 편집") }, onClick = onEdit)
                        DropdownMenuItem(text = { Text("진행도 변경") }, onClick = onStatus)
                        DropdownMenuItem(text = { Text("조사 예정일 변경") }, onClick = onSchedule)
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

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val planned = c.plannedDate.takeIf { it.isNotBlank() }?.let(::plannedDateDisplayV27) ?: "미지정"
                Text(
                    "예정 $planned",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (c.plannedDate == LocalDate.now().toString()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (c.dueDate.isNotBlank()) {
                    Text("완료요청 ${c.dueDate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OcrRegisterScreenV27(vm: AppViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(InvestigationCase(year = LocalDate.now().year)) }
    var busy by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var preprocess by remember { mutableStateOf("") }

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
                        parsed = r.parsed.copy(status = r.parsed.status.normalizedStatusV27())
                        preprocess = r.preprocessMessage
                    }
                    .onFailure { preprocess = "OCR 실패: ${it.message.orEmpty()}" }
                busy = false
            }
        }
    }

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
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("조사의뢰서 사진 선택") }
                    if (busy) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            if (preprocess.isNotBlank()) {
                Text(preprocess, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            }
            if (source != null && !busy) {
                Text("자동인식 결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("내용을 확인한 뒤 조사 예정일과 진행도를 지정하세요.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))

            EditFields(parsed) { parsed = it }
            PlannedDateFieldV27(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            StatusChoiceV27(parsed.status) { parsed = parsed.copy(status = it) }
            Spacer(Modifier.height(16.dp))

            Button(
                enabled = source != null && !busy,
                onClick = {
                    scope.launch {
                        val id = vm.create(parsed.copy(status = parsed.status.normalizedStatusV27()))
                        source?.let {
                            val a = OriginalFileStore.copyOriginal(ctx, it, id, parsed.year, "ORIGINAL_REQUEST").attachment
                            vm.db.attachments().insert(a)
                        }
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("검수 완료 및 저장") }

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
private fun StatusChoiceV27(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("진행도", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STATUS_VALUES_V27.forEach { status ->
                FilterChip(
                    selected = value.normalizedStatusV27() == status,
                    onClick = { onChange(status) },
                    label = { Text(status) }
                )
            }
        }
    }
}

@Composable
private fun StatusDialogV27(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("진행도 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                STATUS_VALUES_V27.forEach { status ->
                    val selected = current.normalizedStatusV27() == status
                    if (selected) {
                        Button(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text("✓ $status") }
                    } else {
                        OutlinedButton(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) { Text(status) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun PlannedDateFieldV27(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        PlannedDateDialogV27(initial = value, onDismiss = { show = false }, onSave = { show = false; onChange(it) })
    }
    OutlinedTextField(
        value = value.takeIf { it.isNotBlank() }?.let(::plannedDateDisplayV27).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("조사 예정일") },
        placeholder = { Text("미지정") },
        trailingIcon = { TextButton(onClick = { show = true }) { Text("선택") } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun PlannedDateDialogV27(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = plannedDateMillisV27(initial))
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

private const val STATUS_NEW_V27 = "신규"
private const val STATUS_IN_PROGRESS_V27 = "진행중"
private const val STATUS_DONE_V27 = "완료"
private val STATUS_VALUES_V27 = listOf(STATUS_NEW_V27, STATUS_IN_PROGRESS_V27, STATUS_DONE_V27)
private const val NO_PLANNED_DATE_V27 = "__NO_PLANNED_DATE_V27__"
private val plannedDateFormatterV27 = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

private fun String.normalizedStatusV27(): String = when (trim()) {
    STATUS_IN_PROGRESS_V27 -> STATUS_IN_PROGRESS_V27
    STATUS_DONE_V27 -> STATUS_DONE_V27
    else -> STATUS_NEW_V27
}

private fun statusOrderV27(value: String): Int = when (value.normalizedStatusV27()) {
    STATUS_NEW_V27 -> 0
    STATUS_IN_PROGRESS_V27 -> 1
    else -> 2
}

private fun plannedDateDisplayV27(value: String): String = runCatching {
    LocalDate.parse(value).format(plannedDateFormatterV27)
}.getOrDefault(value)

private fun plannedDateMillisV27(value: String): Long? = runCatching {
    LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()
