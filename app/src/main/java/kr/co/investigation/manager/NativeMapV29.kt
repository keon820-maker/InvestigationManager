package kr.co.investigation.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import kr.co.investigation.manager.data.InvestigationCase

/**
 * v0.32 카카오맵
 * - 진행중 조사건만 표시
 * - 모든 마커 위에 방문순서/채무자/관리번호를 상시 표시
 * - 마커를 누르면 상위 화면의 길안내 선택창을 연다.
 * - Fold/태블릿 창 크기 변경 시 지도 영역 안에서 다시 맞춘다.
 */
@Composable
fun NativeMapPaneV29(
    items: List<InvestigationCase>,
    selected: InvestigationCase?,
    onNavigate: (InvestigationCase) -> Unit,
    modifier: Modifier = Modifier,
    mapSizeLevel: Int? = null,
    onMapSizeLevel: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var mapError by remember { mutableStateOf(false) }
    var viewportKey by remember { mutableStateOf("") }

    val points = remember(items) {
        items.filter {
            it.status == "진행중" && it.propertyLatitude != null && it.propertyLongitude != null
        }.sortedWith(
            compareBy<InvestigationCase> { it.plannedDate }
                .thenBy { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }
                .thenBy { it.id }
        )
    }

    val mapView = remember(context) {
        MapView(context).apply {
            clipChildren = true
            clipToPadding = true
        }
    }

    DisposableEffect(mapView) {
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
            onDispose { }
        } else {
            var disposed = false
            mapView.start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        if (!disposed) kakaoMap = null
                    }

                    override fun onMapError(error: Exception) {
                        Log.e("InvestigationMap", "Kakao map error", error)
                        if (!disposed) mapError = true
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(map: KakaoMap) {
                        if (!disposed) {
                            kakaoMap = map
                            mapError = false
                        }
                    }

                    override fun getPosition(): LatLng = LatLng.from(37.5665, 126.9780)
                    override fun getZoomLevel(): Int = 10
                }
            )
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runCatching { mapView.resume() }
            }
            onDispose {
                disposed = true
                runCatching { mapView.finish() }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.resume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(kakaoMap, points, selected?.id, mapSizeLevel) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelLayer = map.labelManager?.layer ?: return@LaunchedEffect
        val pointsById = points.associateBy { it.id.toString() }

        labelLayer.removeAll()
        points.forEach { c ->
            val bitmap = caseInfoMarkerBitmapV32(c, selected?.id == c.id)
            val style = LabelStyle.from(bitmap)
                .setAnchorPoint(0.5f, 1.0f)
                .setApplyDpScale(false)
            labelLayer.addLabel(
                LabelOptions.from(
                    "investigation-${c.id}",
                    LatLng.from(c.propertyLatitude!!, c.propertyLongitude!!)
                )
                    .setStyles(style)
                    .setClickable(true)
                    .setTag(c.id.toString())
            )
        }

        map.setOnLabelClickListener { _, _, label ->
            val clicked = label.tag?.toString()?.let(pointsById::get)
            if (clicked != null) latestOnNavigate(clicked)
            clicked != null
        }

        val selectedPoint = selected?.takeIf {
            it.status == "진행중" && it.propertyLatitude != null && it.propertyLongitude != null
        }
        val newViewportKey = buildString {
            append(points.joinToString("|") { "${it.id}:${it.routeOrder}:${it.propertyLatitude}:${it.propertyLongitude}" })
            append("#sel=").append(selectedPoint?.id ?: 0)
            append("#size=").append(mapSizeLevel ?: -1)
        }
        if (newViewportKey != viewportKey) {
            viewportKey = newViewportKey
            mapView.post {
                when {
                    selectedPoint != null -> map.moveCamera(
                        CameraUpdateFactory.newCenterPosition(
                            LatLng.from(selectedPoint.propertyLatitude!!, selectedPoint.propertyLongitude!!),
                            16
                        )
                    )
                    points.size == 1 -> map.moveCamera(
                        CameraUpdateFactory.newCenterPosition(
                            LatLng.from(points.first().propertyLatitude!!, points.first().propertyLongitude!!),
                            15
                        )
                    )
                    points.size > 1 -> map.moveCamera(
                        CameraUpdateFactory.fitMapPoints(
                            points.map { LatLng.from(it.propertyLatitude!!, it.propertyLongitude!!) }.toTypedArray(),
                            90,
                            15
                        )
                    )
                    else -> map.moveCamera(
                        CameraUpdateFactory.newCenterPosition(LatLng.from(37.5665, 126.9780), 10)
                    )
                }
            }
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
            MapMessageV32("카카오맵 앱 키가 설정되지 않았습니다.")
        } else {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize().clipToBounds()
            )
            if (mapError) {
                MapMessageV32("카카오맵을 불러오지 못했습니다. 네트워크 연결 후 앱을 다시 열어주세요.")
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    if (points.isEmpty()) "진행중 조사 위치 없음" else "진행중 ${points.size}건",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "카카오맵 · 마커 터치 길안내 · 핀치/더블탭 확대",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        mapSizeLevel?.let { level ->
            val safeLevel = level.coerceIn(0, 2)
            val labels = listOf("작게", "보통", "크게")
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                tonalElevation = 5.dp,
                shadowElevation = 3.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("지도 폭", style = MaterialTheme.typography.labelMedium)
                    TextButton(
                        onClick = { onMapSizeLevel(safeLevel - 1) },
                        enabled = safeLevel > 0,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
                    ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                    Text(labels[safeLevel], style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = { onMapSizeLevel(safeLevel + 1) },
                        enabled = safeLevel < 2,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
                    ) { Text("＋", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MapMessageV32(message: String) {
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun caseInfoMarkerBitmapV32(c: InvestigationCase, selected: Boolean): Bitmap {
    val width = 390
    val height = 166
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bubble = RectF(8f, 4f, width - 8f, 94f)
    val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) Color.rgb(25, 90, 170) else Color.argb(235, 40, 47, 55)
        style = Paint.Style.FILL
    }
    val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawRoundRect(bubble, 22f, 22f, bubblePaint)
    canvas.drawRoundRect(bubble, 22f, 22f, bubbleStroke)

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) Color.rgb(25, 90, 170) else Color.rgb(0, 128, 108)
        style = Paint.Style.FILL
    }
    val pinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    val path = Path().apply {
        moveTo(width / 2f - 20f, 127f)
        lineTo(width / 2f, 161f)
        lineTo(width / 2f + 20f, 127f)
        close()
    }
    canvas.drawPath(path, pinPaint)
    canvas.drawCircle(width / 2f, 116f, 28f, pinPaint)
    canvas.drawCircle(width / 2f, 116f, 28f, pinStroke)

    val firstLine = buildString {
        if (c.routeOrder > 0) append("${c.routeOrder} · ")
        append(c.debtorName.ifBlank { "조사건" })
    }.take(18)
    val secondLine = c.managementNo.ifBlank { shortAddressV32(c.propertyAddress) }.take(28)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 31f
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 230, 235)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = 23f
    }
    canvas.drawText(firstLine, width / 2f, 40f, titlePaint)
    canvas.drawText(secondLine, width / 2f, 74f, subPaint)

    val orderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 26f
    }
    val pinText = if (c.routeOrder > 0) c.routeOrder.toString() else "•"
    val baseline = 116f - (orderPaint.ascent() + orderPaint.descent()) / 2f
    canvas.drawText(pinText, width / 2f, baseline, orderPaint)

    return bitmap
}

private fun shortAddressV32(value: String): String = value
    .replace(Regex("^\\s*\\d{5,6}\\s+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(28)
