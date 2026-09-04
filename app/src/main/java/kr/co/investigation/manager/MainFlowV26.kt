@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** v0.26: 진행도 관리, 완료 숨김, 진행중 전용 지도, 패치내역 진입. */
@Composable
fun InvestigationAppV26(vm: AppViewModel) {
    var screen by remember { mutableStateOf("main") }
    var formReturn by remember { mutableStateOf("main") }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    when (screen) {
        "main" -> MainScreenV26(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" },
            onPatchHistory = { screen = "patches" }
        )
        "ocr" -> OcrRegisterScreenV26(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
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
fun MainScreenV26(
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
    var q by remember { mutableStateOf("") }
    var mobileTab by remember { mutableIntStateOf(0) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }

    val searched = remember(cases, q) {
        cases.filter {
            q.isBlank() || listOf(
                it.managementNo, it.debtorName, it.propertyAddress, it.phone, it.mobile, it.plannedDate,
                it.branch, it.status
            ).any { s -> s.contains(q, true) }
        }
    }
    val listItems = remember(searched, showCompleted) {
        searched.filter { showCompleted || it.status != STATUS_DONE }
    }
    val mapItems = remember(searched) { searched.filter { it.status == STATUS_IN_PROGRESS } }
    val completedCount = remember(cases) { cases.count { it.status == STATUS_DONE } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("조사관리") },
                actions = {
                    TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹") }
                    Text("$year")
                    TextButton(onClick = { vm.setYear(year + 1) }) { Text("›") }
                    TextButton(onClick = onPatchHistory) { Text("패치") }
                    TextButton(onClick = onSettings) { Text("데이터") }
                    Button(onClick = onNew) { Text("+ 신규등록") }
                }
            )
        }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                CaseListV26(
                    items = listItems,
                    q = q,
                    onQ = { q = it },
                    showCompleted = showCompleted,
                    completedCount = completedCount,
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
                    modifier = m
                )
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(.44f))
                    NativeMapPane(mapItems, selected, Modifier.weight(.56f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = mobileTab) {
                        Tab(selected = mobileTab == 0, onClick = { mobileTab = 0 }, text = { Text("목록") })
                        Tab(selected = mobileTab == 1, onClick = { mobileTab = 1 }, text = { Text("지도") })
                    }
                    if (mobileTab == 0) list(Modifier.fillMaxSize())
                    else NativeMapPane(mapItems, selected, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CaseListV26(
    items: List<InvestigationCase>,
    q: String,
    onQ: (String) -> Unit,
    showCompleted: Boolean,
    completedCount: Int,
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
        PlannedDateDialogV26(
            initial = c.plannedDate,
            onDismiss = { scheduleCase = null },
            onSave = { date -> scheduleCase = null; onSchedule(c, date) }
        )
    }
    statusCase?.let { c ->
        StatusDialogV26(
            current = c.status,
            onDismiss = { statusCase = null },
            onSelect = { status -> statusCase = null; onStatus(c, status) }
        )
    }

    val grouped = remember(items) {
        items.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate.isBlank() }
                .thenBy { it.plannedDate }
                .thenBy { statusOrder(it.status) }
                .thenBy { it.dueDate }
                .thenByDescending { it.id }
        ).groupBy { it.plannedDate.ifBlank { NO_PLANNED_DATE_V26 } }
    }

    Column(modifier.padding(12.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = onQ,
            label = { Text("관리번호·채무자·주소·전화번호·예정일 검색") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "완료 건은 기본 숨김 · 지도는 진행중 건만 표시",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            OutlinedButton(onClick = onToggleCompleted, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (showCompleted) "완료 숨기기" else "완료 보기 ($completedCount)")
            }
        }

        LazyColumn {
            grouped.forEach { (groupDate, rows) ->
                item(key = "date-header-$groupDate") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp),
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            if (groupDate == NO_PLANNED_DATE_V26) "미지정" else plannedDateDisplayV26(groupDate),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                items(rows, key = { "case-${it.id}" }) { c ->
                    Box(Modifier.fillMaxWidth()) {
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                onSelect(c)
                                menuCaseId = c.id
                            }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(c.managementNo.ifBlank { "관리번호 없음" }, style = MaterialTheme.typography.titleMedium)
                                    AssistChip(onClick = { statusCase = c }, label = { Text(c.status.normalizedStatus()) })
                                }
                                Text(c.debtorName)
                                Text(c.propertyAddress, maxLines = 2)
                                Text(
                                    "조사예정 ${c.plannedDate.takeIf { it.isNotBlank() }?.let(::plannedDateDisplayV26) ?: "미지정"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("완료요청 ${c.dueDate}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        DropdownMenu(
                            expanded = menuCaseId == c.id,
                            onDismissRequest = { menuCaseId = null }
                        ) {
                            val hasMarker = c.status == STATUS_IN_PROGRESS && c.propertyLatitude != null && c.propertyLongitude != null
                            DropdownMenuItem(
                                text = { Text(if (hasMarker) "마커 위치로 이동" else if (c.status != STATUS_IN_PROGRESS) "진행중일 때 지도 표시" else "마커 위치 없음") },
                                enabled = hasMarker,
                                onClick = { menuCaseId = null; onLocate(c) }
                            )
                            DropdownMenuItem(
                                text = { Text("진행도 변경 (${c.status.normalizedStatus()})") },
                                onClick = { menuCaseId = null; statusCase = c }
                            )
                            DropdownMenuItem(
                                text = { Text("조사 예정일 설정") },
                                onClick = { menuCaseId = null; scheduleCase = c }
                            )
                            DropdownMenuItem(text = { Text("편집") }, onClick = { menuCaseId = null; onEdit(c) })
                            DropdownMenuItem(text = { Text("조사의뢰서") }, onClick = { menuCaseId = null; onForm(c) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrRegisterScreenV26(vm: AppViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(InvestigationCase(year = LocalDate.now().year)) }
    var busy by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var preprocess by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            source = u
            busy = true
            showRaw = false
            raw = ""
            preprocess = "문서 분석 중..."
            scope.launch {
                runCatching { OcrService.recognizeCase(ctx, u) }
                    .onSuccess { r -> raw = r.rawText; parsed = r.parsed.copy(status = r.parsed.status.normalizedStatus()); preprocess = r.preprocessMessage }
                    .onFailure { preprocess = "OCR 실패: ${it.message.orEmpty()}" }
                busy = false
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR 조사의뢰서 등록") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("뒤로") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button(onClick = { picker.launch("image/*") }) { Text("조사의뢰서 사진 선택") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preprocess.isNotBlank()) Text(preprocess, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (source != null && !busy) {
                Text("자동인식 결과", style = MaterialTheme.typography.titleMedium)
                Text("항목 확인 후 조사 예정일과 진행도를 지정하고 저장하세요.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            EditFields(parsed) { parsed = it }
            PlannedDateFieldV26(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            StatusChoiceV26(parsed.status) { parsed = parsed.copy(status = it) }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(enabled = source != null && !busy, onClick = {
                    scope.launch {
                        val id = vm.create(parsed.copy(status = parsed.status.normalizedStatus()))
                        source?.let {
                            val a = OriginalFileStore.copyOriginal(ctx, it, id, parsed.year, "ORIGINAL_REQUEST").attachment
                            vm.db.attachments().insert(a)
                        }
                        onDone()
                    }
                }) { Text("검수 완료 및 저장") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = raw.isNotBlank(), onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "OCR 원문 숨기기" else "OCR 원문 보기")
                }
            }
            if (showRaw && raw.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Text("OCR 원문(진단용)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                Text(raw, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusChoiceV26(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("진행도", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STATUS_VALUES.forEach { status ->
                FilterChip(
                    selected = value.normalizedStatus() == status,
                    onClick = { onChange(status) },
                    label = { Text(status) }
                )
            }
        }
    }
}

@Composable
private fun StatusDialogV26(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("진행도 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                STATUS_VALUES.forEach { status ->
                    OutlinedButton(onClick = { onSelect(status) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (current.normalizedStatus() == status) "✓ $status" else status)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun PlannedDateFieldV26(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        PlannedDateDialogV26(initial = value, onDismiss = { show = false }, onSave = { show = false; onChange(it) })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("조사 예정일") },
            placeholder = { Text("미지정") },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { show = true }, modifier = Modifier.padding(top = 8.dp)) { Text("날짜 선택") }
    }
}

@Composable
private fun PlannedDateDialogV26(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = plannedDateMillisV26(initial))
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

private const val STATUS_NEW = "신규"
private const val STATUS_IN_PROGRESS = "진행중"
private const val STATUS_DONE = "완료"
private val STATUS_VALUES = listOf(STATUS_NEW, STATUS_IN_PROGRESS, STATUS_DONE)
private const val NO_PLANNED_DATE_V26 = "__NO_PLANNED_DATE_V26__"
private val plannedDateFormatterV26 = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

private fun String.normalizedStatus(): String = when (this.trim()) {
    STATUS_IN_PROGRESS -> STATUS_IN_PROGRESS
    STATUS_DONE -> STATUS_DONE
    else -> STATUS_NEW
}

private fun statusOrder(value: String): Int = when (value.normalizedStatus()) {
    STATUS_NEW -> 0
    STATUS_IN_PROGRESS -> 1
    else -> 2
}

private fun plannedDateDisplayV26(value: String): String = runCatching {
    LocalDate.parse(value).format(plannedDateFormatterV26)
}.getOrDefault(value)

private fun plannedDateMillisV26(value: String): Long? = runCatching {
    LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()
