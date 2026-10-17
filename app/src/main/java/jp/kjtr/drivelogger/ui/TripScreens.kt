package jp.kjtr.drivelogger.ui

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import androidx.compose.material.icons.filled.ScatterPlot
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.drivelogger.R
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ScrollableTabRow
import jp.kjtr.drivelogger.data.PhotoEntity
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.playback.StopSpan
import jp.kjtr.drivelogger.playback.TimelineItem
import jp.kjtr.drivelogger.playback.TimelineKind
import jp.kjtr.drivelogger.playback.TripTimeline
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.geo.GeoBounds
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.geo.Simplify
import jp.kjtr.drivelogger.playback.RecordedSpans
import jp.kjtr.drivelogger.playback.TimeSpan
import jp.kjtr.drivelogger.stats.DriveAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripListScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit, onSummary: () -> Unit) {
    // 開いているドライブ（0.6.0〜）も先頭に出す。まとめ・合計は閉じたものだけ
    val all by remember { vm.trips.all() }.collectAsState(emptyList())
    val list = remember(all) { all.filter { !it.isOpen } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trips_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = { TextButton(onClick = onSummary) { Text(stringResource(R.string.summary_title)) } },
            )
        },
    ) { pad ->
        if (all.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.trips_empty)) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isNotEmpty()) {
                item(key = "summary") {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onSummary)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.summary_card, list.size, Format.km(list.sumOf { it.distanceM }), Format.duration(list.sumOf { it.movingMs })),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.Filled.ZoomOutMap, contentDescription = null)
                        }
                    }
                }
            }
            items(all, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t.id) }) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = if (t.isOpen) 14.dp else 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (t.isOpen) {
                                Text(
                                    stringResource(R.string.trip_open_badge), style = MaterialTheme.typography.labelMedium, color = Color.White,
                                    modifier = Modifier.background(colorResource(R.color.brand_primary), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (t.recordedVideo) Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.trip_has_video), tint = colorResource(R.color.recording))
                        }
                        Text(Format.dateTime(t.startedAt) + (t.endedAt?.let { " – " + Format.time(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 4.dp)) {
                            StatCell(stringResource(R.string.stat_distance), Format.kmNumber(t.distanceM), Modifier.weight(1f), unit = "km")
                            StatCell(stringResource(R.string.stat_running), Format.duration(t.movingMs), Modifier.weight(1f))
                            StatCell(stringResource(R.string.stat_avg), Format.speed(avg(t)), Modifier.weight(1f), unit = "km/h")
                        }
                        if (!t.isOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ReopenButton(vm, t) }
                    }
                }
            }
        }
    }
}

/** 生の GPS の点（比較表示）を出すズーム・点の半径 [dp]・一度に描く点の上限。 */
private const val RAW_DOTS_MIN_ZOOM = 16f
private const val RAW_DOT_DP = 3.5f
private const val MAX_RAW_DOTS = 400

/** 経過時間（ドライブの最初〜最後の記録。開いている間は今まで）。走行時間（GPS ログの合計）は [TripEntity.movingMs]。 */
private fun elapsed(t: TripEntity) = (if (t.isOpen) System.currentTimeMillis() else (t.endedAt ?: t.startedAt)) - t.startedAt
private fun avg(t: TripEntity) = if (t.movingMs > 0) t.distanceM / (t.movingMs / 1000.0) else 0.0
private fun avgRunning(t: TripEntity): Double {
    val run = t.movingMs - t.stillMs
    return if (run > 0) t.distanceM / (run / 1000.0) else 0.0
}

/** 速度の区分（km/h）と色（車向け）。 */
private val SPEED_BANDS = listOf(
    20.0 to Color(0xFF1E88E5), 40.0 to Color(0xFF00ACC1), 60.0 to Color(0xFF43A047),
    80.0 to Color(0xFFFDD835), 100.0 to Color(0xFFFB8C00), Double.MAX_VALUE to Color(0xFFE53935),
)

private fun bandOf(kmh: Double): Int = SPEED_BANDS.indexOfFirst { kmh < it.first }.coerceAtLeast(0)

private data class ColoredRun(val points: List<LatLng>, val color: Color)

private fun coloredRuns(points: List<TripPointEntity>): List<ColoredRun> {
    val out = ArrayList<ColoredRun>()
    for (seg in TripRepository.segmentsOf(points)) {
        if (seg.size < 2) continue
        val pts = Simplify.stride(seg, 8000)
        var cur = ArrayList<LatLng>()
        var band = -1
        for (i in pts.indices) {
            val p = pts[i]
            val kmh = (p.speed?.toDouble() ?: if (i > 0) {
                val q = pts[i - 1]
                val dt = (p.time - q.time) / 1000.0
                if (dt > 0) Geo.distanceM(q.lat, q.lon, p.lat, p.lon) / dt else 0.0
            } else 0.0) * 3.6
            val b = bandOf(kmh)
            val ll = LatLng(p.lat, p.lon)
            if (band == -1) band = b
            if (b != band && cur.size >= 2) {
                out.add(ColoredRun(cur, SPEED_BANDS[band].second))
                cur = arrayListOf(cur.last())
                band = b
            }
            cur.add(ll)
        }
        if (cur.size >= 2) out.add(ColoredRun(cur, SPEED_BANDS[band.coerceAtLeast(0)].second))
    }
    return out
}

/** 録画していた時間帯（出来事 video）。 */
fun videoSpans(events: List<TripEventEntity>, trip: TripEntity?): List<TimeSpan> =
    events.filter { it.type == TripEventEntity.TYPE_VIDEO }.map { TimeSpan(it.startedAt, it.endedAt ?: trip?.endedAt ?: Long.MAX_VALUE) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedLegend(showVideo: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(stringResource(R.string.speed_legend), style = MaterialTheme.typography.labelSmall)
        listOf("<20", "20-40", "40-60", "60-80", "80-100", "100+").forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 5.dp).size(10.dp).background(SPEED_BANDS[i].second))
                Text(" $label", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showVideo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 8.dp).size(width = 16.dp, height = 8.dp).background(colorResource(R.color.recording).copy(alpha = 0.45f)))
                Text(" " + stringResource(R.string.legend_recorded), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    vm: MainViewModel, tripId: Long, justFinished: Boolean, onBack: () -> Unit, onPlayClip: (Long, Long) -> Unit, onCard: (Long) -> Unit,
    onGoDrive: () -> Unit = {},
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { vm.trips.trip(tripId) }.collectAsState(null)
    val rawEvents by remember(tripId) { vm.trips.events(tripId) }.collectAsState(emptyList())
    // GPS ログ・動画ログ（0.6.0〜）。動画ログは「録画した時間帯」の出来事の形にもして、地図・タイムラインで今までどおり使う
    val logs by remember(tripId) { vm.trips.logs(tripId) }.collectAsState(emptyList())
    val events = remember(rawEvents, logs, trip) {
        (rawEvents.filter { it.type != TripEventEntity.TYPE_VIDEO } + TripRepository.videoEvents(logs, trip?.endedAt ?: System.currentTimeMillis()))
            .sortedWith(compareBy({ it.startedAt }, { it.id }))
    }
    var deletingLog by remember { mutableStateOf<Pair<jp.kjtr.drivelogger.data.DriveLogEntity, Int>?>(null) }
    var exportingLog by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val exportLogGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val l = exportingLog
        if (uri != null && l != null) vm.exportGpx(tripId, uri, l.first, l.second)
        exportingLog = null
    }
    val clips by remember(tripId) { vm.clips.forTrip(tripId) }.collectAsState(emptyList())
    val photos by remember(tripId) { vm.photos.forTrip(tripId) }.collectAsState(emptyList())
    val parks by remember(tripId) { vm.trips.parks(tripId) }.collectAsState(emptyList())
    val walkLines by produceState(emptyList<List<LatLng>>(), tripId, parks) {
        value = vm.trips.walkPoints(tripId).groupBy { it.parkId }.values.map { s -> s.sortedBy { it.time }.map { LatLng(it.lat, it.lon) } }.filter { it.size >= 2 }
    }
    var viewingPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.importPhotos(tripId, uris)
    }
    val settings by vm.settings.collectAsState()
    // ログを消した・再開して記録を足したら読み直す
    val points by produceState(emptyList<TripPointEntity>(), tripId, trip?.pointCount, logs.size) { value = vm.trips.points(tripId) }
    // 表示の点（0.8.0〜 軌跡をなめらかにする設定なら、このドライブの乗り物でなめらかにした点。記録した点は points のまま）
    val smoothCfg = remember(trip?.vehicle, settings.trackSmoothing) { TripRepository.smoothConfigOf(trip) }
    val shown by produceState(emptyList<TripPointEntity>(), points, smoothCfg) {
        value = withContext(Dispatchers.Default) { TripRepository.smoothed(points, smoothCfg) }
    }
    val runs by produceState(emptyList<ColoredRun>(), shown) { value = withContext(Dispatchers.Default) { coloredRuns(shown) } }
    val spans = remember(events, trip) { videoSpans(events, trip) }
    val recordedRuns by produceState(emptyList<List<LatLng>>(), shown, spans) {
        value = withContext(Dispatchers.Default) {
            RecordedSpans.pick(TripRepository.segmentsOf(shown), spans) { it.time }.map { s -> Simplify.stride(s, 4000).map { LatLng(it.lat, it.lon) } }
        }
    }
    val autoPause = trip?.autoPause
    val analysis by produceState<DriveAnalysis?>(null, points, autoPause, smoothCfg) {
        if (autoPause == null || points.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { TripRepository.analyze(points, autoPause, smoothCfg) }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // タイムライン（録画した区間・写真・保護・衝撃・停車を時刻順に）
    val stops by produceState(emptyList<StopSpan>(), points) { value = withContext(Dispatchers.Default) { TripTimeline.stops(points) } }
    val timeline = remember(trip, events, photos, stops, parks, logs) {
        trip?.let { TripTimeline.build(it.startedAt, if (it.isOpen) null else it.endedAt, events, photos, stops, parks, gpsLogs = logs.filter { l -> l.isGps }) } ?: emptyList()
    }
    val photosById = remember(photos) { photos.associateBy { it.id } }
    val noClipText = stringResource(R.string.timeline_no_clip)

    fun clipFor(item: TimelineItem): Pair<jp.kjtr.drivelogger.data.ClipEntity, Long>? = when (item.kind) {
        TimelineKind.VIDEO -> TripTimeline.clipAt(clips, item.startMs, gapToleranceMs = 15_000)
        TimelineKind.IMPACT, TimelineKind.PROTECT -> TripTimeline.clipAt(clips, item.startMs, leadMs = TripTimeline.EVENT_LEAD_MS)
        else -> null
    }

    // 地図の大きさ（つまみで変える。離したら設定に保存）と全画面
    var ratio by remember { mutableFloatStateOf(settings.tripMapRatio) }
    LaunchedEffect(settings.tripMapRatio) { ratio = settings.tripMapRatio }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // このドライブで初めて開拓したマス（全ドライブのまとめの材料から）
    var showNewCells by rememberSaveable { mutableStateOf(false) }
    // 矩形（隣り合うマスをまとめたもの）とマスの数。表示する数はマスの数（0.4.1〜。0.4.0 は矩形の数で少なく出ていた）
    val newCellsInfo by produceState(jp.kjtr.drivelogger.stats.CellShapes.TripNewCells(emptyList(), 0), trip?.id, trip?.pointCount) {
        val t = trip ?: return@produceState
        if (t.status != TripEntity.STATUS_FINISHED) return@produceState
        val all = vm.app.summaries.digests(vm.trips.allFinished())
        val first = jp.kjtr.drivelogger.stats.CellShapes.firstSeen(all.map { it.facts })
        val mine = all.firstOrNull { it.facts.id == t.id }?.facts?.cells ?: return@produceState
        value = withContext(Dispatchers.Default) { jp.kjtr.drivelogger.stats.CellShapes.newCellsOfTrip(mine, first, t.startedAt) }
    }
    val newCells = newCellsInfo.rects
    val newCellColor = colorResource(R.color.cell_new)
    // 獲得標高（0.8.0〜 まとめと同じ ElevationGain）
    val ascent by produceState<Double?>(null, points) {
        if (points.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { jp.kjtr.drivelogger.stats.ElevationGain.of(points) }
    }

    // 生の GPS の点（0.8.0〜 比較表示）。融合した点は元の測位、GPS のままの点はそのまま（推測航法・停車中の点は無し）
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val rawSegs by produceState(emptyList<List<TripPointEntity>>(), points) {
        value = withContext(Dispatchers.Default) { TripRepository.segmentsOf(TripRepository.rawPoints(points)) }
    }
    val rawLines = remember(rawSegs) { rawSegs.filter { it.size >= 2 }.map { s -> Simplify.stride(s, 8000).map { LatLng(it.lat, it.lon) } } }
    // 点と精度の円は、寄ったとき（ズーム 16 以上）に見えている範囲の点だけ（多いと重いので最大 400 点）
    var rawDots by remember { mutableStateOf(emptyList<TripPointEntity>()) }
    var dotRadiusM by remember { mutableStateOf(2.0) }

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showFinished by rememberSaveable { mutableStateOf(justFinished) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f) }
    val padPx = with(density) { 32.dp.roundToPx() }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) vm.exportGpx(tripId, uri)
    }

    fun bounds() = GeoBounds.of(
        shown.ifEmpty { points }.map { LatLon(it.lat, it.lon) } + walkLines.flatten().map { LatLon(it.latitude, it.longitude) } +
            photos.mapNotNull { p -> p.lat?.let { la -> p.lon?.let { LatLon(la, it) } } },
    )

    /** タイムラインの行の場所へ地図を寄せる（位置が無ければ、その時刻の軌跡の位置）。 */
    fun focusMap(item: TimelineItem) {
        val ll = item.lat?.let { la -> item.lon?.let { LatLon(la, it) } }
            ?: jp.kjtr.drivelogger.photo.PhotoLocator.interpolate(points.sortedBy { it.time }, item.startMs)
            ?: return
        if (!mapLoaded) return
        scope.launch {
            runCatching {
                cameraState.animate(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(LatLng(ll.lat, ll.lon), maxOf(cameraState.position.zoom, 15f)), 600)
            }
        }
    }

    LaunchedEffect(shown, mapLoaded, mapSize) {
        if (fitted || !mapLoaded || shown.isEmpty() || mapSize.width == 0) return@LaunchedEffect
        bounds()?.let {
            fitted = true
            cameraState.fitTo(it, padPx, mapSize.width, mapSize.height)
        }
    }

    // 地図が止まったら、見えている範囲の生の点を選び直す
    LaunchedEffect(showRaw, rawSegs, cameraState.isMoving, mapLoaded) {
        if (!showRaw || !mapLoaded || cameraState.isMoving) return@LaunchedEffect
        val zoom = cameraState.position.zoom
        val b = cameraState.projection?.visibleRegion?.latLngBounds
        if (zoom < RAW_DOTS_MIN_ZOOM || b == null) {
            rawDots = emptyList()
            return@LaunchedEffect
        }
        val lat = cameraState.position.target.latitude
        // 1 dp の長さ [m]（Web メルカトル。Google マップのズームは 256 dp のタイルで数える）
        val mPerDp = 156_543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())
        dotRadiusM = mPerDp * RAW_DOT_DP
        rawDots = withContext(Dispatchers.Default) {
            Simplify.stride(rawSegs.flatten().filter { b.contains(LatLng(it.lat, it.lon)) }, MAX_RAW_DOTS)
        }
    }

    /** 地図。[topInset] は全画面のときにステータスバーを避ける分、[bottomPad] は下から引き出すシートの分。 */
    @Composable
    fun MapPane(modifier: Modifier, topInset: androidx.compose.ui.unit.Dp = 0.dp, bottomPad: androidx.compose.ui.unit.Dp = 0.dp) {
        Box(modifier.onSizeChanged { mapSize = it }) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(mapType = settings.mapKind.toMapType()),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false),
                contentPadding = PaddingValues(top = topInset, bottom = bottomPad),
            ) {
                OnMapReady { mapLoaded = true }
                // このドライブで初めて開拓したマス（切り替え）
                if (showNewCells) CellPolygons(newCells, newCellColor, zIndex = 0.5f)
                // 録画した区間（太い半透明の線を下に）
                recordedRuns.forEach { r ->
                    Polyline(points = r, color = colorResource(R.color.recording).copy(alpha = 0.45f), width = with(density) { 14.dp.toPx() }, jointType = JointType.ROUND, zIndex = 1f)
                }
                runs.forEach { r ->
                    Polyline(points = r.points, color = r.color, width = with(density) { 5.dp.toPx() }, jointType = JointType.ROUND, zIndex = 2f)
                }
                events.filter { (it.type == TripEventEntity.TYPE_IMPACT || it.type == TripEventEntity.TYPE_PROTECT) && it.lat != null && it.lon != null }.forEach { e ->
                    val state = remember(e.id) { MarkerState(LatLng(e.lat!!, e.lon!!)) }
                    Marker(state = state, title = eventLabel(e), snippet = Format.clock(e.startedAt), zIndex = 4f)
                }
                // 駐車中の徒歩の軌跡（点線）と駐車の位置（P）
                walkLines.forEach { w ->
                    Polyline(
                        points = w, color = Color(0xFF6A1B9A), width = with(density) { 4.dp.toPx() }, jointType = JointType.ROUND, zIndex = 3f,
                        pattern = listOf(com.google.android.gms.maps.model.Dot(), com.google.android.gms.maps.model.Gap(with(density) { 6.dp.toPx() })),
                    )
                }
                ParkMarkers(parks)
                // 生の GPS（0.8.0〜 比較表示）: 細い線＋寄ったときは点と精度の円
                if (showRaw) {
                    val rawColor = colorResource(R.color.raw_gps)
                    rawLines.forEach { l ->
                        Polyline(points = l, color = rawColor.copy(alpha = 0.75f), width = with(density) { 1.5.dp.toPx() }, zIndex = 2.5f)
                    }
                    rawDots.forEach { p ->
                        val c = LatLng(p.lat, p.lon)
                        p.accuracy?.takeIf { it > 0f }?.let { acc ->
                            Circle(
                                center = c, radius = acc.toDouble(), strokeColor = rawColor.copy(alpha = 0.45f), strokeWidth = with(density) { 1.dp.toPx() },
                                fillColor = rawColor.copy(alpha = 0.05f), zIndex = 2.4f,
                            )
                        }
                        Circle(center = c, radius = dotRadiusM, fillColor = rawColor, strokeColor = Color.White, strokeWidth = with(density) { 1.dp.toPx() }, zIndex = 2.6f)
                    }
                }
                // 写真（縮小版のマーカー。タップで拡大・左右に送る）
                PhotoMarkers(photos, onClick = { viewingPhoto = it })
            }
            SpeedLegend(showVideo = recordedRuns.isNotEmpty(), modifier = Modifier.align(Alignment.TopStart).padding(top = topInset + 8.dp, start = 8.dp, end = 64.dp))
            // 右のボタン列＋独自のズームの ±（地図が小さいと列は縦にスクロール。0.4.1〜、標準のズームボタンとの重なりを無くした）
            MapSideButtons(
                cameraState, mapLoaded,
                // 上は 8dp（0.4.0 は 48dp 空けていた。左の凡例は end = 64dp で列にかからない）
                Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 8.dp, bottom = bottomPad + 8.dp),
            ) {
                SmallFloatingActionButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.map_fullscreen_exit else R.string.map_fullscreen),
                    )
                }
                SmallFloatingActionButton(
                    onClick = { val b = bounds() ?: return@SmallFloatingActionButton; if (mapLoaded) scope.launch { cameraState.fitTo(b, padPx, mapSize.width, mapSize.height) } },
                ) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.fit_track)) }
                // 生の GPS の点を重ねる（0.8.0〜）
                if (rawLines.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { showRaw = !showRaw },
                        containerColor = if (showRaw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (showRaw) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Icon(Icons.Filled.ScatterPlot, contentDescription = stringResource(R.string.trip_raw_toggle)) }
                }
                if (newCells.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { showNewCells = !showNewCells },
                        containerColor = if (showNewCells) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (showNewCells) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Icon(Icons.Filled.GridOn, contentDescription = stringResource(R.string.trip_new_cells_toggle)) }
                }
            }
            if (showRaw && rawLines.isNotEmpty()) {
                val zoomedIn = cameraState.position.zoom >= RAW_DOTS_MIN_ZOOM
                Row(
                    // 初めて走ったマスの表示と重ならないよう、その上（end = 112dp は右下のズームの −/+ の分）
                    Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 112.dp, bottom = bottomPad + if (showNewCells && newCells.isNotEmpty()) 52.dp else 28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(colorResource(R.color.raw_gps), androidx.compose.foundation.shape.CircleShape))
                    Text(
                        " " + stringResource(if (zoomedIn) R.string.trip_raw_legend else R.string.trip_raw_legend_zoom),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (showNewCells && newCells.isNotEmpty()) {
                Text(
                    stringResource(R.string.trip_new_cells, newCellsInfo.count), style = MaterialTheme.typography.labelSmall,
                    // end = 112dp は右下のズームの −/+ の分（地図が狭いと 2 行に折り返す）
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 112.dp, bottom = bottomPad + 28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Overview(t: TripEntity) {
        // 開いているドライブ（0.6.0〜）: 走行画面へ戻る。閉じたドライブ: 再開して記録を足せる
        if (t.isOpen) {
            val openHere = vm.session.tripId == t.id
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).background(colorResource(R.color.brand_primary).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.trip_open_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (openHere) TextButton(onClick = onGoDrive) { Text(stringResource(R.string.back_to_drive)) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trip_reopen_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                ReopenButton(vm, t, guard = { vm.restrictedNow() })
            }
        }
        Text(Format.dateTime(t.startedAt) + (t.endedAt?.takeIf { !t.isOpen }?.let { " – " + Format.dateTimeShort(t.startedAt, it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
        // 距離・走行時間は GPS ログの合計（ログとログの間は数えない）。経過時間はドライブの最初〜最後
        Row(Modifier.padding(top = 8.dp)) {
            StatCell(stringResource(R.string.stat_distance), Format.kmNumber(t.distanceM), Modifier.weight(1f), unit = "km")
            StatCell(stringResource(R.string.stat_running), Format.duration(t.movingMs), Modifier.weight(1f))
            StatCell(stringResource(R.string.stat_elapsed), Format.duration(elapsed(t)), Modifier.weight(1f))
        }
        Row {
            StatCell(stringResource(R.string.stat_avg), Format.speed(avg(t)), Modifier.weight(1f), unit = "km/h")
            // 0.8.0〜 走った時間（停車を除く）が短すぎる・最高速度を超える（徒歩は 5 km/h 未満が「停車」に入る）ときは出さない
            val ar = avgRunning(t)
            val arOk = t.movingMs - t.stillMs >= 60_000 && ar <= t.maxSpeedMps * 1.05 + 0.1
            StatCell(stringResource(R.string.stat_avg_running), if (arOk) Format.speed(ar) else "--", Modifier.weight(1f), unit = "km/h")
            StatCell(stringResource(R.string.stat_max), Format.speed(t.maxSpeedMps), Modifier.weight(1f), unit = "km/h")
        }
        Text(stringResource(R.string.trip_still_line, Format.duration(t.stillMs)), style = MaterialTheme.typography.bodySmall)
        ascent?.let { Text(stringResource(R.string.trip_ascent_line, kotlin.math.round(it).toInt()), style = MaterialTheme.typography.bodySmall) }
        // 乗り物（0.8.0〜。車以外のときだけ）
        if (t.vehicleKind != jp.kjtr.drivelogger.record.Vehicle.CAR) {
            Text(stringResource(R.string.trip_vehicle_line, vehicleLabel(t.vehicleKind)), style = MaterialTheme.typography.bodySmall)
        }
        // GPS ログ・動画ログの一覧
        LogList(
            logs, clips, System.currentTimeMillis(),
            onExportGpx = { l, n -> exportingLog = l.id to n; exportLogGpx.launch(vm.tripFileBase(t) + "_gps$n.gpx") },
            onShareGpx = { l, n -> vm.shareGpx(context, tripId, l.id, n) },
            onPlay = { c -> onPlayClip(c.id, 0L) },
            onDelete = { l, n -> deletingLog = l to n },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            OutlinedButton(onClick = { exportGpx.launch(vm.tripFileBase(t) + ".gpx") }) {
                Icon(Icons.Filled.FileDownload, contentDescription = null); Text(" " + stringResource(R.string.export_gpx))
            }
            OutlinedButton(onClick = { vm.shareGpx(context, tripId) }) {
                Icon(Icons.Filled.Share, contentDescription = null); Text(" " + stringResource(R.string.share_gpx))
            }
            OutlinedButton(onClick = { onCard(tripId) }) {
                Icon(Icons.Filled.Image, contentDescription = null); Text(" " + stringResource(R.string.card_make))
            }
            OutlinedButton(onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null); Text(" " + stringResource(R.string.photo_add_from_library))
            }
        }
        if (photos.isNotEmpty()) {
            Text(stringResource(R.string.photos_title, photos.size), style = MaterialTheme.typography.titleSmall)
            PhotoStrip(photos, onClick = { viewingPhoto = it }, modifier = Modifier.padding(vertical = 6.dp))
        }
        // 駐車・写真（駐車中の時間は走行時間・平均に入らない）
        if (parks.isNotEmpty() || photos.isNotEmpty()) {
            val parkMs = parks.sumOf { ((it.endedAt ?: t.endedAt ?: it.startedAt) - it.startedAt).coerceAtLeast(0) }
            Row {
                StatCell(stringResource(R.string.stat_parks), parks.size.toString(), Modifier.weight(1f), unit = stringResource(R.string.unit_times))
                StatCell(stringResource(R.string.stat_park_time), Format.duration(parkMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_photos), photos.size.toString(), Modifier.weight(1f), unit = stringResource(R.string.unit_photos))
            }
            val walk = parks.sumOf { it.walkDistanceM }
            if (walk > 0) Text(stringResource(R.string.trip_walk_total, Format.distance(walk)), style = MaterialTheme.typography.bodySmall)
        }
        if (spans.isNotEmpty()) {
            Text(stringResource(R.string.trip_video_total, Format.duration(RecordedSpans.totalMs(spans.map { it.copy(endMs = minOf(it.endMs, t.endedAt ?: it.endMs)) }))), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Text(stringResource(R.string.point_count, t.pointCount), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        val otherEvents = events.filter { it.type != TripEventEntity.TYPE_VIDEO }
        if (otherEvents.isNotEmpty()) {
            Text(stringResource(R.string.events_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            otherEvents.forEach { e ->
                val span = Format.clock(e.startedAt) + (if (e.endedAt != null && e.endedAt != e.startedAt) " – " + Format.clock(e.endedAt) else "")
                val value = if (e.type == TripEventEntity.TYPE_IMPACT && e.value != null) "  " + String.format(Locale.ROOT, "%.2f G", e.value) else ""
                Text("$span  ${eventLabel(e)}$value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    fun ClipsTab() {
        if (clips.isEmpty()) {
            Text(stringResource(if (spans.isEmpty()) R.string.trip_no_video else R.string.trip_video_gone), Modifier.padding(vertical = 12.dp))
            return
        }
        Text(stringResource(R.string.trip_clips_count, clips.size), style = MaterialTheme.typography.titleSmall)
        clips.forEach { c -> ClipRow(vm, c, onClick = { onPlayClip(c.id, 0L) }) }
    }

    @Composable
    fun InfoPane(modifier: Modifier) {
        val t = trip
        Column(modifier) {
            ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 4.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.trip_tab_overview), maxLines = 1) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.trip_tab_timeline), maxLines = 1) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.trip_tab_splits), maxLines = 1) })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.trip_tab_speed), maxLines = 1) })
                Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text(stringResource(R.string.trip_tab_clips), maxLines = 1) })
            }
            key(tab) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (t != null) {
                        when (tab) {
                            0 -> Overview(t)
                            1 -> TimelineSection(timeline, photosById, hasClipAt = { clipFor(it) != null }) { item ->
                                when (item.kind) {
                                    TimelineKind.PHOTO -> photosById[item.photoId]?.let { viewingPhoto = it }
                                    TimelineKind.VIDEO, TimelineKind.IMPACT, TimelineKind.PROTECT -> {
                                        val c = clipFor(item)
                                        if (c != null) onPlayClip(c.first.id, c.second) else {
                                            vm.toast(noClipText)
                                            focusMap(item)
                                        }
                                    }
                                    else -> focusMap(item)
                                }
                            }
                            2 -> SplitsSection(analysis, Modifier.fillMaxWidth())
                            3 -> SpeedHistogramSection(analysis, Modifier.fillMaxWidth())
                            else -> ClipsTab()
                        }
                    }
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen) {
        // 全画面: 地図だけ＋下から引き出すシート（自転車ルートロガーと同じ）
        FullscreenMapWithSheet(
            title = trip?.let { stringResource(R.string.trip_sheet_title, it.name, Format.km(it.distanceM)) } ?: "",
            map = { top, bottom -> MapPane(Modifier.fillMaxSize(), topInset = top, bottomPad = bottom) },
            sheet = { InfoPane(Modifier.fillMaxWidth().weight(1f)) },
        )
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val onStopped = { vm.updateSettings { it.copy(tripMapRatio = ratio) } }
            if (landscape) {
                // 横長: 地図が左、情報が右
                val full = maxWidth
                Row(Modifier.fillMaxSize()) {
                    MapPane(Modifier.fillMaxHeight().width(full * ratio))
                    RatioHandle(vertical = false, totalPx = with(density) { full.toPx() }, ratio = ratio, onRatio = { ratio = it }, onStopped = onStopped)
                    InfoPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                val full = maxHeight
                Column(Modifier.fillMaxSize()) {
                    MapPane(Modifier.fillMaxWidth().height(full * ratio))
                    RatioHandle(vertical = true, totalPx = with(density) { full.toPx() }, ratio = ratio, onRatio = { ratio = it }, onStopped = onStopped)
                    InfoPane(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(stringResource(R.string.rename), trip?.name ?: "", onDone = { if (it.isNotBlank()) vm.renameTrip(tripId, it) }, onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.trip_delete_confirm, trip?.name ?: ""), text = stringResource(R.string.trip_delete_note), confirm = stringResource(R.string.delete),
            onConfirm = { vm.deleteTrip(tripId); onBack() }, onDismiss = { deleting = false },
        )
    }
    deletingLog?.let { (l, n) ->
        val mine = if (l.isVideo) clips.filter { it.logId == l.id } else emptyList()
        ConfirmDialog(
            title = stringResource(if (l.isGps) R.string.log_delete_gps_title else R.string.log_delete_video_title, n),
            text = if (l.isGps) stringResource(R.string.log_delete_gps_text, Format.km(l.distanceM))
            else stringResource(R.string.log_delete_video_text, mine.size, mine.count { it.protected }),
            confirm = stringResource(R.string.delete),
            onConfirm = { vm.deleteLog(l) },
            onDismiss = { deletingLog = null },
        )
    }
    viewingPhoto?.let { p -> PhotoViewer(vm, photos, p, onDismiss = { viewingPhoto = null }) }
    if (showFinished && trip != null) {
        AlertDialog(
            onDismissRequest = { showFinished = false },
            title = { Text(stringResource(R.string.trip_finished_title)) },
            text = { Text(stringResource(R.string.trip_finished_text, trip?.name ?: "", Format.km(trip?.distanceM ?: 0.0))) },
            confirmButton = { TextButton(onClick = { showFinished = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** 駐車の位置（P の印）。 */
@Composable
@com.google.maps.android.compose.GoogleMapComposable
fun ParkMarkers(parks: List<jp.kjtr.drivelogger.data.ParkEntity>) {
    for (p in parks) {
        val lat = p.lat ?: continue
        val lon = p.lon ?: continue
        val state = remember(p.id, lat, lon) { MarkerState(LatLng(lat, lon)) }
        com.google.maps.android.compose.MarkerComposable(p.id, state = state, anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 4.5f) {
            Box(
                Modifier.size(28.dp).background(Color.White, RoundedCornerShape(6.dp)).padding(2.dp).background(Color(0xFF6A1B9A), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("P", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }
}

@Composable
fun eventLabel(e: TripEventEntity): String = stringResource(
    when (e.type) {
        TripEventEntity.TYPE_IMPACT -> R.string.event_impact
        TripEventEntity.TYPE_PROTECT -> R.string.event_protect
        TripEventEntity.TYPE_LOCATION_OFF -> R.string.event_location_off
        TripEventEntity.TYPE_NO_FIX -> R.string.event_no_fix
        TripEventEntity.TYPE_RESTARTED -> R.string.event_restarted
        TripEventEntity.TYPE_INTERRUPTED -> R.string.event_interrupted
        TripEventEntity.TYPE_THERMAL -> R.string.event_thermal
        TripEventEntity.TYPE_CAMERA_LOST -> R.string.event_camera_lost
        TripEventEntity.TYPE_STORAGE_FULL -> R.string.event_storage_full
        TripEventEntity.TYPE_VIDEO -> R.string.event_video
        else -> R.string.event_other
    },
)

