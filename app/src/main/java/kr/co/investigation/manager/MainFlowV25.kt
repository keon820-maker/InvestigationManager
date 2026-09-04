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

/** v0.25: 조사 예정일 입력/변경 + 날짜별 목록 그룹. */
@Composable
fun InvestigationAppV25(vm: AppViewModel) {
    var screen by remember { mutableStateOf("main") }
    var formReturn by remember { mutableStateOf("main") }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    when (screen) {
        "main" -> MainScreenV25(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" }
        )
        "ocr" -> OcrRegisterScreenV25(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
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
    }
}

@Composable
fun MainScreenV25(
    vm: AppViewModel,
    onNew: () -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onSettings: () -> Unit
) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var mobileTab by remember { mutableIntStateOf(0) }
    val filtered = remember(cases, q) {
        cases.filter {
            q.isBlank() || listOf(
                it.managementNo, it.debtorName, it.propertyAddress, it.phone, it.mobile, it.plannedDate
            ).any { s -> s.contains(q, true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("조사관리") },
                actions = {
                    TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹") }
                    Text("$year")
                    TextButton(onClick = { vm.setYear(year + 1) }) { Text("›") }
                    TextButton(onClick = onSettings) { Text("데이터") }
                    Button(onClick = onNew) { Text("+ 신규등록") }
                }
            )
        }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                CaseListV25(
                    items = filtered,
                    q = q,
                    onQ = { q = it },
                    onSelect = { vm.select(it) },
                    onLocate = {
                        vm.select(it)
                        if (!wide) mobileTab = 1
                    },
                    onEdit = onEdit,
                    onForm = onForm,
                    onComplete = { vm.update(it.copy(status = "완료")) },
                    onSchedule = { c, date -> vm.update(c.copy(plannedDate = date)) },
                    modifier = m
                )
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(.44f))
                    NativeMapPane(filtered, selected, Modifier.weight(.56f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = mobileTab) {
                        Tab(selected = mobileTab == 0, onClick = { mobileTab = 0 }, text = { Text("목록") })
                        Tab(selected = mobileTab == 1, onClick = { mobileTab = 1 }, text = { Text("지도") })
                    }
                    if (mobileTab == 0) list(Modifier.fillMaxSize())
                    else NativeMapPane(filtered, selected, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun CaseListV25(
    items: List<InvestigationCase>,
    q: String,
    onQ: (String) -> Unit,
    onSelect: (InvestigationCase) -> Unit,
    onLocate: (InvestigationCase) -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onComplete: (InvestigationCase) -> Unit,
    onSchedule: (InvestigationCase, String) -> Unit,
    modifier: Modifier
) {
    var menuCaseId by remember { mutableStateOf<Long?>(null) }
    var scheduleCase by remember { mutableStateOf<InvestigationCase?>(null) }

    scheduleCase?.let { c ->
        PlannedDateDialog(
            initial = c.plannedDate,
            onDismiss = { scheduleCase = null },
            onSave = { date ->
                scheduleCase = null
                onSchedule(c, date)
            }
        )
    }

    val grouped = remember(items) {
        items.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate.isBlank() }
                .thenBy { it.plannedDate }
                .thenBy { it.dueDate }
                .thenByDescending { it.id }
        ).groupBy { it.plannedDate.ifBlank { NO_PLANNED_DATE } }
    }

    Column(modifier.padding(12.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = onQ,
            label = { Text("관리번호·채무자·주소·전화번호·예정일 검색") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "조사 예정일 기준으로 날짜별 표시됩니다. 리스트를 누르면 작업 메뉴가 열립니다.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 5.dp, bottom = 5.dp)
        )
        LazyColumn {
            grouped.forEach { (groupDate, rows) ->
                item(key = "date-header-$groupDate") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp),
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            if (groupDate == NO_PLANNED_DATE) "미지정" else plannedDateDisplay(groupDate),
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
                                    AssistChip(onClick = {}, label = { Text(c.status) })
                                }
                                Text(c.debtorName)
                                Text(c.propertyAddress, maxLines = 2)
                                Text(
                                    "조사예정 ${c.plannedDate.takeIf { it.isNotBlank() }?.let(::plannedDateDisplay) ?: "미지정"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("완료요청 ${c.dueDate}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        DropdownMenu(
                            expanded = menuCaseId == c.id,
                            onDismissRequest = { menuCaseId = null }
                        ) {
                            val hasMarker = c.propertyLatitude != null && c.propertyLongitude != null
                            DropdownMenuItem(
                                text = { Text(if (hasMarker) "마커 위치로 이동" else "마커 위치 없음") },
                                enabled = hasMarker,
                                onClick = { menuCaseId = null; onLocate(c) }
                            )
                            DropdownMenuItem(
                                text = { Text("조사 예정일 설정") },
                                onClick = { menuCaseId = null; scheduleCase = c }
                            )
                            DropdownMenuItem(text = { Text("편집") }, onClick = { menuCaseId = null; onEdit(c) })
                            DropdownMenuItem(text = { Text("조사의뢰서") }, onClick = { menuCaseId = null; onForm(c) })
                            DropdownMenuItem(
                                text = { Text(if (c.status == "완료") "완료처리됨" else "완료처리") },
                                enabled = c.status != "완료",
                                onClick = { menuCaseId = null; onComplete(c) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OcrRegisterScreenV25(vm: AppViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
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
                    .onSuccess { r -> raw = r.rawText; parsed = r.parsed; preprocess = r.preprocessMessage }
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
                Text("아래 항목을 확인·수정하고 조사 예정일을 지정한 뒤 저장하세요.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            EditFields(parsed) { parsed = it }
            PlannedDateField(parsed.plannedDate) { parsed = parsed.copy(plannedDate = it) }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(enabled = source != null && !busy, onClick = {
                    scope.launch {
                        val id = vm.create(parsed)
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
fun PlannedDateField(value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        PlannedDateDialog(
            initial = value,
            onDismiss = { show = false },
            onSave = { show = false; onChange(it) }
        )
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
private fun PlannedDateDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = plannedDateMillis(initial))
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

private const val NO_PLANNED_DATE = "__NO_PLANNED_DATE__"
private val plannedDateFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private fun plannedDateDisplay(value: String): String = runCatching {
    LocalDate.parse(value).format(plannedDateFormatter)
}.getOrDefault(value)

private fun plannedDateMillis(value: String): Long? = runCatching {
    LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()
