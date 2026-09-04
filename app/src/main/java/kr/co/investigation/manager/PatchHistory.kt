package kr.co.investigation.manager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private data class PatchRelease(val version: String, val name: String, val notes: String, val date: String)

/** GitHub Release 이력을 직접 읽으므로 이후 릴리스도 앱 업데이트 후 자동 누적된다. */
@Composable
fun PatchHistoryScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var releases by remember { mutableStateOf<List<PatchRelease>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { loadPatchReleases() }
            .onSuccess { releases = it }
            .onFailure { error = it.message ?: "패치내역을 불러오지 못했습니다." }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("패치내역") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            Text(
                "GitHub Release 이력을 표시합니다. 새 버전이 배포되면 이 목록에도 계속 누적됩니다.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() }
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { reloadKey++ }) { Text("다시 불러오기") }
                }
                releases.isEmpty() -> Text("표시할 패치내역이 없습니다.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(releases, key = { it.version }) { release ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(release.version, style = MaterialTheme.typography.titleMedium)
                                    if (release.date.isNotBlank()) Text(release.date, style = MaterialTheme.typography.labelSmall)
                                }
                                if (release.name.isNotBlank() && !release.name.contains(release.version)) {
                                    Text(release.name, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (release.notes.isNotBlank()) {
                                    Spacer(Modifier.height(5.dp))
                                    Text(release.notes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadPatchReleases(): List<PatchRelease> = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/keon820-maker/InvestigationManager/releases?per_page=50").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 10000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "InvestigationManager-Android")
    }
    try {
        if (connection.responseCode !in 200..299) error("GitHub 응답 오류 ${connection.responseCode}")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                if (o.optBoolean("draft", false)) continue
                val tag = o.optString("tag_name")
                val name = o.optString("name")
                val published = o.optString("published_at").take(10)
                add(PatchRelease(tag, name, cleanReleaseBody(o.optString("body")), published))
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun cleanReleaseBody(body: String): String = body.lines()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .filterNot { it.startsWith("##") || it.startsWith("**Full Changelog**") }
    .map { line ->
        line.removePrefix("* ")
            .replace(Regex(" by @[^ ]+ in https?://\\S+"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()
    }
    .filter { it.isNotBlank() }
    .joinToString("\n")
