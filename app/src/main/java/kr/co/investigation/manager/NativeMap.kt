package kr.co.investigation.manager

import android.content.Intent
import android.net.Uri
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

/**
 * Android 네이티브 osmdroid 지도.
 * v0.26부터 진행중 조사건만 마커로 표시하며, 마커를 누르면 길안내 버튼을 제공한다.
 */
@Composable
fun NativeMapPane(
    items: List<InvestigationCase>,
    selected: InvestigationCase?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var navCase by remember { mutableStateOf<InvestigationCase?>(null) }
    val points = remember(items) {
        items.filter {
            it.status == "진행중" && it.propertyLatitude != null && it.propertyLongitude != null
        }
    }

    navCase?.let { c ->
        val lat = c.propertyLatitude
        val lon = c.propertyLongitude
        if (lat != null && lon != null) {
            val destinationName = c.propertyAddress.ifBlank {
                c.managementNo.ifBlank { c.debtorName.ifBlank { "조사 목적지" } }
            }
            AlertDialog(
                onDismissRequest = { navCase = null },
                title = { Text(c.managementNo.ifBlank { "길안내" }) },
                text = {
                    Column {
                        if (c.debtorName.isNotBlank()) Text(c.debtorName, style = MaterialTheme.typography.titleSmall)
                        Text(c.propertyAddress)
                        Spacer(Modifier.height(8.dp))
                        Text("사용할 길안내 앱을 선택하세요.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                openTmap(context, destinationName, lat, lon)
                                navCase = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("TMAP 길안내") }
                        OutlinedButton(
                            onClick = {
                                openKakaoRoute(context, destinationName, lat, lon)
                                navCase = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("카카오 길안내") }
                    }
                },
                dismissButton = { TextButton(onClick = { navCase = null }) { Text("닫기") } }
            )
        } else {
            navCase = null
        }
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
                    if (items.none { it.status == "진행중" }) "진행중 조사건 없음" else "진행중 건 좌표 확인 중"
                } else {
                    "진행중 지도 표시 ${points.size}건"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

private fun openTmap(context: android.content.Context, name: String, lat: Double, lon: Double) {
    val uri = Uri.parse(
        "tmap://route?goalname=${Uri.encode(name)}&goalx=$lon&goaly=$lat"
    )
    val launched = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!launched) {
        val market = Uri.parse("market://details?id=com.skt.tmap.ku")
        val web = Uri.parse("https://play.google.com/store/apps/details?id=com.skt.tmap.ku")
        if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** 카카오 공식 지도 길찾기 링크. 앱 설치 시 카카오맵으로 연결되고 아니면 웹으로 열린다. */
private fun openKakaoRoute(context: android.content.Context, name: String, lat: Double, lon: Double) {
    val uri = Uri.parse("https://map.kakao.com/link/to/${Uri.encode(name)},$lat,$lon")
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
