package kr.co.investigation.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.co.investigation.manager.data.InvestigationCase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

/**
 * v0.29 지도
 * - 진행중 조사건만 표시
 * - 모든 마커 위에 방문순서/채무자/관리번호를 상시 표시
 * - 마커를 누르면 상위 화면의 길안내 선택창을 연다.
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

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            clipChildren = true
            clipToPadding = true
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
            overlays.add(DoubleTapZoomOverlayV29())
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = { map ->
                map.overlays.removeAll(map.overlays.filterIsInstance<Marker>())

                points.forEach { c ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(c.propertyLatitude!!, c.propertyLongitude!!)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } }
                        snippet = c.propertyAddress
                        icon = caseInfoMarkerDrawableV29(
                            context = context,
                            c = c,
                            selected = selected?.id == c.id
                        )
                        setOnMarkerClickListener { _, _ ->
                            onNavigate(c)
                            true
                        }
                    }
                    map.overlays.add(marker)
                }

                val selectedPoint = selected?.takeIf {
                    it.status == "진행중" && it.propertyLatitude != null && it.propertyLongitude != null
                }
                val newViewportKey = buildString {
                    append(points.joinToString("|") { "${it.id}:${it.routeOrder}:${it.propertyLatitude}:${it.propertyLongitude}" })
                    append("#sel=").append(selectedPoint?.id ?: 0)
                }
                if (newViewportKey != viewportKey) {
                    viewportKey = newViewportKey
                    when {
                        selectedPoint != null -> {
                            map.controller.setZoom(16.0)
                            map.controller.animateTo(
                                GeoPoint(selectedPoint.propertyLatitude!!, selectedPoint.propertyLongitude!!)
                            )
                        }
                        points.size == 1 -> {
                            map.controller.setZoom(15.0)
                            map.controller.animateTo(
                                GeoPoint(points.first().propertyLatitude!!, points.first().propertyLongitude!!)
                            )
                        }
                        points.size > 1 -> {
                            val box = BoundingBox.fromGeoPoints(
                                points.map { GeoPoint(it.propertyLatitude!!, it.propertyLongitude!!) }
                            )
                            map.post { map.zoomToBoundingBox(box, true, 90) }
                        }
                        else -> {
                            map.controller.setZoom(10.0)
                            map.controller.setCenter(GeoPoint(37.5665, 126.9780))
                        }
                    }
                }
                map.invalidate()
            }
        )

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
                    "모든 마커에 간단정보 표시 · 마커 터치 길안내",
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

private class DoubleTapZoomOverlayV29 : Overlay() {
    override fun onDoubleTap(e: MotionEvent, mapView: MapView): Boolean {
        val x = e.x.toInt()
        val y = e.y.toInt()
        return if (mapView.zoomLevelDouble >= 16.0) {
            mapView.controller.zoomOutFixing(x, y)
        } else {
            mapView.controller.zoomInFixing(x, y)
        }
    }
}

private fun caseInfoMarkerDrawableV29(
    context: android.content.Context,
    c: InvestigationCase,
    selected: Boolean
): BitmapDrawable {
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
    val secondLine = c.managementNo.ifBlank { shortAddressV29(c.propertyAddress) }.take(28)

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

    return BitmapDrawable(context.resources, bitmap)
}

private fun shortAddressV29(value: String): String = value
    .replace(Regex("^\\s*\\d{5,6}\\s+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(28)
