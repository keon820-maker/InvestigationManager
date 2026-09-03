package kr.co.investigation.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

/**
 * WebView/Leaflet 대신 Android 네이티브 osmdroid 지도를 사용한다.
 * 일부 삼성 기기에서 외부 Leaflet CDN이 로드되지 않아 흰 화면만 보이던 문제를 피한다.
 */
@Composable
fun NativeMapPane(
    items: List<InvestigationCase>,
    selected: InvestigationCase?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val points = remember(items) {
        items.filter { it.propertyLatitude != null && it.propertyLongitude != null }
    }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
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
                val oldMarkers = map.overlays.filterIsInstance<Marker>()
                map.overlays.removeAll(oldMarkers)

                var selectedMarker: Marker? = null
                points.forEach { c ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(c.propertyLatitude!!, c.propertyLongitude!!)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = c.managementNo.ifBlank { c.debtorName.ifBlank { "조사건" } }
                        snippet = buildString {
                            if (c.debtorName.isNotBlank()) append(c.debtorName).append('\n')
                            append(c.propertyAddress)
                        }
                    }
                    map.overlays.add(marker)
                    if (selected?.id == c.id) selectedMarker = marker
                }

                val selectedPoint = selected?.takeIf {
                    it.propertyLatitude != null && it.propertyLongitude != null
                }
                when {
                    selectedPoint != null -> {
                        map.controller.setZoom(16.0)
                        map.controller.animateTo(
                            GeoPoint(selectedPoint.propertyLatitude!!, selectedPoint.propertyLongitude!!)
                        )
                        selectedMarker?.showInfoWindow()
                    }
                    points.size == 1 -> {
                        map.controller.setZoom(15.0)
                        map.controller.animateTo(
                            GeoPoint(points[0].propertyLatitude!!, points[0].propertyLongitude!!)
                        )
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
                map.invalidate()
            }
        )

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            tonalElevation = 3.dp,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = if (points.isEmpty()) {
                    if (items.isEmpty()) "등록된 조사건 없음" else "지도 좌표 확인 중"
                } else {
                    "지도 표시 ${points.size}건"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
