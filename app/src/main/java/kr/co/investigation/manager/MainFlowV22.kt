package kr.co.investigation.manager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase

/** v0.22 메인 흐름: 목록 메뉴에서 지도 마커 위치로 이동 지원. */
@Composable
fun InvestigationAppV22(vm: AppViewModel) {
    var screen by remember { mutableStateOf("main") }
    var formReturn by remember { mutableStateOf("main") }
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }
    when (screen) {
        "main" -> MainScreenV22(
            vm = vm,
            onNew = { screen = "ocr" },
            onEdit = { vm.select(it); screen = "detail" },
            onForm = { vm.select(it); formReturn = "main"; screen = "form" },
            onSettings = { screen = "settings" }
        )
        "ocr" -> OcrRegisterScreen(vm, onDone = { screen = "main" }, onCancel = { screen = "main" })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenV22(
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
            q.isBlank() || listOf(it.managementNo, it.debtorName, it.propertyAddress, it.phone, it.mobile)
                .any { s -> s.contains(q, true) }
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
                CaseListV22(
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
fun CaseListV22(
    items: List<InvestigationCase>,
    q: String,
    onQ: (String) -> Unit,
    onSelect: (InvestigationCase) -> Unit,
    onLocate: (InvestigationCase) -> Unit,
    onEdit: (InvestigationCase) -> Unit,
    onForm: (InvestigationCase) -> Unit,
    onComplete: (InvestigationCase) -> Unit,
    modifier: Modifier
) {
    var menuCaseId by remember { mutableStateOf<Long?>(null) }
    Column(modifier.padding(12.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = onQ,
            label = { Text("관리번호·채무자·주소·전화번호 검색") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "리스트를 누르면 작업 메뉴가 열립니다.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 5.dp, bottom = 3.dp)
        )
        LazyColumn {
            items(items, key = { it.id }) { c ->
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
