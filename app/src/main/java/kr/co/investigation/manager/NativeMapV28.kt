package kr.co.investigation.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** v0.28: 진행중 조사만 표시하고 routeOrder가 있으면 마커에 방문순서를 표시한다. */
@Composable
fun NativeMapPaneV28(
    items: List<InvestigationCase>,
    selected: InvestigationCase?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }
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

    navCase?.let { c ->
        AlertDialog(
            onDismissRequest = { navCase = null },
            title = {
                Text(
                    buildString {
                        if (c.routeOrder > 0) append("${c.routeOrder}번 · ")
                        append(c.managementNo.ifBlank { "길안내" })
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.titleSmall)
                    Text(c.propertyAddress)
                    if (c.plannedDate.isNotBlank()) Text("조사 예정 ${c.plannedDate}", style = MaterialTheme.typography.bodySmall)
                    Text("길안내 앱을 선택하세요.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { openTmapV28(context, c); navCase = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("TMAP 길안내") }
                    OutlinedButton(
                        onClick = { openKakaoRouteV28(context, c); navCase = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("카카오 길안내") }
                }
            },
            dismissButton = { TextButton(onClick = { navCase = null }) { Text("닫기") } }
        )
    }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
            overlays.add(DoubleTapZoomOverlayV28())
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.overlays.removeAll(map.overlays.filterIsInstance<Marker>())
                var selectedMarker: Marker? = null

                points.forEach { c ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(c.propertyLatitude!!, c.propertyLongitude!!)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = buildString {
                            if (c.routeOrder > 0) append("${c.routeOrder}. ")
                            append(c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } })
                        }
                        snippet = buildString {
                            if (c.debtorName.isNotBlank()) append(c.debtorName).append('\n')
                            append(c.propertyAddress)
                        }
                        if (c.routeOrder > 0) {
                            icon = routeMarkerDrawableV28(context, c.routeOrder, selected?.id == c.id)
                        }
                        setOnMarkerClickListener { _, _ ->
                            navCase = c
                            true
                        }
                    }
                    map.overlays.add(marker)
                    if (selected?.id == c.id) selectedMarker = marker
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
                            map.controller.animateTo(GeoPoint(selectedPoint.propertyLatitude!!, selectedPoint.propertyLongitude!!))
                            selectedMarker?.showInfoWindow()
                        }
                        points.size == 1 -> {
                            map.controller.setZoom(15.0)
                            map.controller.animateTo(GeoPoint(points[0].propertyLatitude!!, points[0].propertyLongitude!!))
                        }
                        points.size > 1 -> {
                            val geoPoints = points.map { GeoPoint(it.propertyLatitude!!, it.propertyLongitude!!) }
                            val box = BoundingBox.fromGeoPoints(geoPoints)
                            map.post { map.zoomToBoundingBox(box, true, 80) }
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
                    if (points.isEmpty()) "진행중 조사 위치 없음" else "진행중 ${points.size}건 · 숫자=방문순서",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "마커 터치 길안내 · 두 번 탭 확대/축소 · 핀치 확대/축소",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private class DoubleTapZoomOverlayV28 : Overlay() {
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

private fun routeMarkerDrawableV28(context: android.content.Context, order: Int, selected: Boolean): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(96, 120, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) Color.rgb(25, 90, 170) else Color.rgb(0, 128, 108)
        style = Paint.Style.FILL
    }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    val path = Path().apply {
        moveTo(28f, 63f)
        lineTo(48f, 112f)
        lineTo(68f, 63f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawCircle(48f, 44f, 35f, fill)
    canvas.drawCircle(48f, 44f, 35f, outline)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = if (order < 10) 38f else 31f
    }
    val baseline = 44f - (text.ascent() + text.descent()) / 2f
    canvas.drawText(order.toString(), 48f, baseline, text)
    return BitmapDrawable(context.resources, bitmap)
}
