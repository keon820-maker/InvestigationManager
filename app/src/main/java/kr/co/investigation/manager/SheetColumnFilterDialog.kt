package kr.co.investigation.manager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SheetColumnFilterDialog(
    label: String, options: List<String>, selected: Set<String>,
    onChange: (Set<String>) -> Unit, onApply: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit
) {
    var query by rememberSaveable(label) { mutableStateOf("") }
    val choices = options.filter { it.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label 필터") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("값 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { onChange(options.toSet()) }) { Text("전체 선택") }
                    TextButton(onClick = { onChange(emptySet()) }) { Text("전체 해제") }
                }
                Text("${selected.size}/${options.size}개 선택", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(choices, key = { it }) { value ->
                        Row(Modifier.fillMaxWidth().testTag("sheet-filter-value-$value").clickable {
                            onChange(if(value in selected) selected - value else selected + value)
                        }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value in selected, onCheckedChange = null)
                            Text(value.ifEmpty { "(빈 값)" }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("적용") } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("이 열 필터 해제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}
