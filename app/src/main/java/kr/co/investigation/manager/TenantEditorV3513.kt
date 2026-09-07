package kr.co.investigation.manager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private data class TenantDraftV3513(
    val name: String = "",
    val phone: String = ""
)

@Composable
fun TenantEditorV3513(
    tenantsJson: String,
    onChange: (String) -> Unit
) {
    val rows = parseTenantRowsV3513(tenantsJson).toMutableList().apply {
        while (size < 10) add(TenantDraftV3513())
    }
    val detectedCount = rows.indexOfLast { it.name.isNotBlank() || it.phone.isNotBlank() } + 1
    var visibleCount by rememberSaveable(tenantsJson) {
        mutableIntStateOf(max(1, detectedCount).coerceAtMost(10))
    }

    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("임차인 정보", style = MaterialTheme.typography.titleSmall)
        Text(
            "조사의뢰서 임차인란에서 읽은 성명과 전화번호입니다. 저장 전에 확인·수정할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall
        )

        repeat(visibleCount) { index ->
            val row = rows[index]
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("임차인 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { value ->
                            rows[index] = row.copy(name = value)
                            onChange(tenantRowsToJsonV3513(rows))
                        },
                        label = { Text("성명") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = row.phone,
                        onValueChange = { value ->
                            rows[index] = row.copy(phone = value)
                            onChange(tenantRowsToJsonV3513(rows))
                        },
                        label = { Text("전화번호") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (row.name.isNotBlank() || row.phone.isNotBlank()) {
                        TextButton(onClick = {
                            rows[index] = TenantDraftV3513()
                            onChange(tenantRowsToJsonV3513(rows))
                        }) { Text("이 임차인 비우기") }
                    }
                }
            }
        }

        if (visibleCount < 10) {
            OutlinedButton(
                onClick = { visibleCount++ },
                modifier = Modifier.fillMaxWidth()
            ) { Text("임차인 추가") }
        }
    }
    HorizontalDivider()
    Spacer(Modifier.height(5.dp))
}

private fun parseTenantRowsV3513(json: String): List<TenantDraftV3513> = runCatching {
    val array = JSONArray(json.ifBlank { "[]" })
    (0 until minOf(array.length(), 10)).map { index ->
        val obj = array.optJSONObject(index)
        if (obj == null) TenantDraftV3513() else TenantDraftV3513(
            name = obj.optString("name").ifBlank { obj.optString("tenantName") }.trim(),
            phone = obj.optString("phone").ifBlank { obj.optString("mobile") }.trim()
        )
    }
}.getOrDefault(emptyList())

private fun tenantRowsToJsonV3513(rows: List<TenantDraftV3513>): String {
    val last = rows.take(10).indexOfLast { it.name.isNotBlank() || it.phone.isNotBlank() }
    if (last < 0) return "[]"
    val array = JSONArray()
    rows.take(last + 1).forEach { row ->
        array.put(JSONObject().apply {
            put("name", row.name.trim())
            put("phone", row.phone.trim())
        })
    }
    return array.toString()
}
