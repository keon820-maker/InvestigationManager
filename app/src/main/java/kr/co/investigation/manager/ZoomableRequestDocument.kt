package kr.co.investigation.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kr.co.investigation.manager.data.InvestigationCase
import kotlin.math.max
import kotlin.math.roundToInt

/** 조사의뢰서 조회: 버튼/핀치/두 번 탭 확대·축소. */
@Composable
fun ZoomableRequestDocument(c: InvestigationCase, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val baseDensity = LocalDensity.current
        val fitZoom = ((maxWidth.value - 24f) / 760f).coerceIn(0.35f, 1f)
        val readableZoom = max(1f, fitZoom * 2f).coerceAtMost(2f)
        var zoom by remember(maxWidth) { mutableFloatStateOf(fitZoom) }
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()

        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "두 번 탭으로 확대/맞춤",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { zoom = (zoom - 0.10f).coerceAtLeast(0.35f) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("−") }
                    Text("${(zoom * 100).roundToInt()}%", modifier = Modifier.padding(horizontal = 8.dp))
                    OutlinedButton(
                        onClick = { zoom = (zoom + 0.10f).coerceAtMost(2f) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("+") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { zoom = fitZoom }) { Text("맞춤") }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFFE9EDF0))
                    .pointerInput(fitZoom, readableZoom, zoom) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoom = if (zoom <= fitZoom + 0.08f) readableZoom else fitZoom
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange.isFinite() && zoomChange > 0f) {
                                        zoom = (zoom * zoomChange).coerceIn(0.35f, 2f)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                if (event.changes.all { !it.pressed }) break
                            }
                        }
                    }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(vertical)
                        .horizontalScroll(horizontal)
                        .padding(12.dp)
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = baseDensity.density * zoom,
                            fontScale = baseDensity.fontScale
                        )
                    ) {
                        RequestDocumentView(c)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
