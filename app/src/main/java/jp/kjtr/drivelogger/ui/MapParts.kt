package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.MapKind
import jp.kjtr.drivelogger.geo.GeoBounds
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.geo.Simplify
import kotlin.math.roundToInt

fun MapKind.toMapType(): MapType = when (this) {
    MapKind.NORMAL -> MapType.NORMAL
    MapKind.SATELLITE -> MapType.SATELLITE
    MapKind.HYBRID -> MapType.HYBRID
    MapKind.TERRAIN -> MapType.TERRAIN
}

fun LatLon.toLatLng() = LatLng(lat, lon)

/** 地図を範囲に合わせる（[OnMapReady] の後に）。大きさを渡すとキーの制限でタイルが読めないときも合わせられる。 */
suspend fun CameraPositionState.fitTo(b: GeoBounds, paddingPx: Int, widthPx: Int = 0, heightPx: Int = 0) {
    val update = if (b.north - b.south < 1e-4 && b.east - b.west < 1e-4) {
        CameraUpdateFactory.newLatLngZoom(LatLng((b.north + b.south) / 2, (b.east + b.west) / 2), 16f)
    } else {
        val bounds = LatLngBounds(LatLng(b.south, b.west), LatLng(b.north, b.east))
        if (widthPx > 0 && heightPx > 0) {
            CameraUpdateFactory.newLatLngBounds(bounds, widthPx, heightPx, paddingPx.coerceAtMost(minOf(widthPx, heightPx) / 4))
        } else {
            CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        }
    }
    runCatching { move(update) }
}

/** 地図（GoogleMap オブジェクト）が使えるようになったら呼ぶ（onMapLoaded はキーが通らないと来ないので待たない）。 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
@GoogleMapComposable
fun OnMapReady(onReady: () -> Unit) {
    MapEffect(Unit) { onReady() }
}

/** 走った軌跡。 */
@Composable
@GoogleMapComposable
fun TrackLines(segments: List<List<LatLon>>, color: Color, widthPx: Float, zIndex: Float = 3f) {
    for (seg in segments) {
        if (seg.size < 2) continue
        val pts = remember(seg) { Simplify.stride(seg, 5000).map { it.toLatLng() } }
        Polyline(points = pts, color = color, width = widthPx, jointType = JointType.ROUND, zIndex = zIndex)
    }
}

/**
 * 開拓したマス（1 km 四方。隣り合うマスをまとめた矩形）を半透明で塗る。[camera] を渡すと、地図が止まっているときの
 * 表示範囲に入るものだけ描く（マスが多くても重くならないように）。
 */
@Composable
@GoogleMapComposable
fun CellPolygons(rects: List<jp.kjtr.drivelogger.stats.CellRect>, color: Color, camera: CameraPositionState? = null, zIndex: Float = 0.5f) {
    val moving = camera?.isMoving == true
    var view by remember { mutableStateOf<jp.kjtr.drivelogger.stats.CellRect?>(null) }
    LaunchedEffect(camera, moving) {
        if (camera == null || moving) return@LaunchedEffect
        val b = camera.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
        view = jp.kjtr.drivelogger.stats.CellRect(b.southwest.latitude, b.southwest.longitude, b.northeast.latitude, b.northeast.longitude)
    }
    val shown = remember(rects, view) {
        val v = view
        if (camera == null || v == null) rects.take(jp.kjtr.drivelogger.stats.CellShapes.MAX_SHAPES) else jp.kjtr.drivelogger.stats.CellShapes.visible(rects, v)
    }
    for (r in shown) {
        com.google.maps.android.compose.Polygon(
            points = remember(r) { listOf(LatLng(r.south, r.west), LatLng(r.south, r.east), LatLng(r.north, r.east), LatLng(r.north, r.west)) },
            fillColor = color, strokeColor = color.copy(alpha = (color.alpha * 1.8f).coerceAtMost(1f)), strokeWidth = 1.5f, zIndex = zIndex,
        )
    }
}

/**
 * 地図の右のボタン列（0.4.1〜。まとめ・ドライブの詳細）。上に [buttons]（入りきらなければ縦にスクロール）、右下に独自のズームの −/+（横並び）。
 * Google 標準のズームボタン（右下）は使わない（`zoomControlsEnabled = false`）。0.4.0 は地図をつまみで小さくすると
 * 右のボタン列が標準の ± に重なった。± も同じ列で配置するので、地図がどれだけ小さくても（つまみで最小・横向き・全画面のシート）重ならない。
 * [modifier] に上下の余白（全画面のステータスバー・シートの分）を入れて、Box の中で `align(TopEnd)` して置く。
 */
@Composable
fun MapSideButtons(
    camera: CameraPositionState,
    ready: Boolean,
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    fun zoom(update: com.google.android.gms.maps.CameraUpdate) {
        if (ready) scope.launch { runCatching { camera.animate(update, 250) } }
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = buttons,
        )
        // ± は横に並べる（縦の場所を取らない）
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            SmallFloatingActionButton(onClick = { zoom(CameraUpdateFactory.zoomOut()) }) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.map_zoom_out))
            }
            SmallFloatingActionButton(onClick = { zoom(CameraUpdateFactory.zoomIn()) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.map_zoom_in))
            }
        }
    }
}

/**
 * 全画面の地図＋下から引き出すシート（自転車ルートロガーと同じ作り）。シートには題名と [sheet] の中身。
 * [map] にはステータスバーの分（上）とシートの見える分（下）を渡す（Google のロゴ・帰属表示がシートに隠れないように）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FullscreenMapWithSheet(
    title: String,
    map: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit,
    sheet: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberStandardBottomSheetState(
        initialValue = androidx.compose.material3.SheetValue.PartiallyExpanded, skipHiddenState = true,
    )
    val scaffoldState = androidx.compose.material3.rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topInset = androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    // つまみ・題名・タブまで見せる
    val peek = 124.dp + navBottom
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetMax = maxHeight * 0.7f
        androidx.compose.material3.BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetContent = {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxWidth().height(sheetMax).padding(bottom = navBottom),
                ) {
                    androidx.compose.material3.Text(
                        title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                    sheet()
                }
            },
        ) { _ -> map(topInset, peek) }
    }
}

/**
 * 駐車中の地図: 駐車した位置（P）・最後に測った現在地（青い点）・徒歩の軌跡（点線）・駐車中に撮った写真。両方が入るように合わせる。
 * 停車中なので普通に動かせる。
 */
@Composable
fun ParkMap(
    car: LatLon?, here: LatLon?, walk: List<LatLon>, photos: List<jp.kjtr.drivelogger.data.PhotoEntity>, mapKind: MapKind, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(car?.lat ?: 35.681, car?.lon ?: 139.767), if (car != null) 16f else 5f) }
    var ready by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val pad = with(density) { 96.dp.roundToPx() }
    LaunchedEffect(ready, car, here, size) {
        if (!ready || size.width == 0) return@LaunchedEffect
        val b = GeoBounds.of(listOfNotNull(car, here)) ?: return@LaunchedEffect
        camera.fitTo(b, pad, size.width, size.height)
        // 車と現在地が近いと寄りすぎるので、ズームは 17 まで
        if (camera.position.zoom > 17f) runCatching { camera.move(CameraUpdateFactory.zoomTo(17f)) }
    }
    val parkColor = colorResource(R.color.park)
    Box(modifier.onSizeChanged { size = it }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = mapKind.toMapType()),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, compassEnabled = true),
            // 地図の上の HUD の下に Google のロゴ・帰属表示が隠れないように
            contentPadding = PaddingValues(top = 120.dp, bottom = 110.dp),
        ) {
            OnMapReady { ready = true }
            if (walk.size >= 2) {
                Polyline(
                    points = remember(walk) { walk.map { it.toLatLng() } }, color = parkColor, width = with(density) { 4.dp.toPx() }, jointType = JointType.ROUND,
                    pattern = listOf(com.google.android.gms.maps.model.Dot(), com.google.android.gms.maps.model.Gap(with(density) { 6.dp.toPx() })),
                )
            }
            if (car != null) {
                ParkMarkers(listOf(jp.kjtr.drivelogger.data.ParkEntity(id = -1, tripId = 0, startedAt = 0, lat = car.lat, lon = car.lon)))
            }
            if (here != null) {
                // 最後に測った現在地（画面上で一定の大きさの青い点）
                val state = remember(here) { com.google.maps.android.compose.MarkerState(here.toLatLng()) }
                com.google.maps.android.compose.MarkerComposable(here, state = state, anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 6f) {
                    Box(Modifier.size(20.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape).padding(3.dp).background(Color(0xFF1E88E5), androidx.compose.foundation.shape.CircleShape))
                }
            }
            PhotoMarkers(photos, onClick = {})
        }
    }
}

/**
 * 走行画面の地図（全面 [full] または録画中の小窓）。現在地が中心・ヘディングアップ（進行方向が上）・走った軌跡。
 * ジェスチャーは使わない（運転中に触らない）。中央に進行方向の矢印。Google のロゴ・帰属表示は SDK が出す（覆わない）。
 */
@Composable
fun DriveMap(
    lat: Double?,
    lon: Double?,
    headingDeg: Double?,
    track: List<List<LatLon>>,
    mapKind: MapKind,
    full: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 縦向きの全面だけ、自分の位置を下に寄せて前方を広く見せる
    val pushDown = full && androidx.compose.ui.platform.LocalConfiguration.current.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), if (full) 5f else 14f) }
    var ready by remember { mutableStateOf(false) }
    val ui = remember {
        MapUiSettings(
            compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false,
            rotationGesturesEnabled = false, scrollGesturesEnabled = false, scrollGesturesEnabledDuringRotateOrZoom = false,
            tiltGesturesEnabled = false, zoomControlsEnabled = false, zoomGesturesEnabled = false,
        )
    }
    val bearing = ((headingDeg ?: 0.0).roundToInt() % 360).toFloat()
    val zoom = if (full) 16.5f else 15f
    LaunchedEffect(lat, lon, bearing, ready) {
        if (!ready || lat == null || lon == null) return@LaunchedEffect
        // 全面のときは自分の位置を少し下に（前方を広く見せる）
        runCatching { camera.move(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(lat, lon), zoom, if (full) 30f else 0f, bearing))) }
    }
    val lineColor = colorResource(R.color.track_line)
    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = mapKind.toMapType()),
            uiSettings = ui,
            contentPadding = if (pushDown) PaddingValues(top = 160.dp, bottom = 0.dp) else PaddingValues(),
        ) {
            OnMapReady { ready = true }
            TrackLines(track, lineColor, with(density) { (if (full) 6.dp else 4.dp).toPx() })
        }
        if (lat != null) {
            // 地図の contentPadding（上 160dp）で中心が下にずれた分だけ矢印もずらす
            val fill = colorResource(R.color.hud_map_arrow)
            val outline = colorResource(R.color.hud_map_arrow_outline)
            Canvas(
                Modifier.align(Alignment.Center).size(if (full) 34.dp else 24.dp)
                    .offset(y = if (pushDown) 80.dp else 0.dp),
            ) {
                val w = size.width
                val h = size.height
                val arrow = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(w, h)
                    lineTo(w / 2f, h * 0.72f)
                    lineTo(0f, h)
                    close()
                }
                drawPath(arrow, fill)
                drawPath(arrow, outline, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            }
        }
    }
}

