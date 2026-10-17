package jp.kjtr.drivelogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import jp.kjtr.drivelogger.data.PhotoEntity
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.MapKind
import jp.kjtr.drivelogger.data.TripDigest
import jp.kjtr.drivelogger.geo.GeoBounds
import jp.kjtr.drivelogger.stats.DriveSummaries
import jp.kjtr.drivelogger.stats.DriveSummary
import jp.kjtr.drivelogger.stats.SummaryPeriod
import jp.kjtr.drivelogger.stats.TimeRange
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** トリップごとの色（色分けのとき）。 */
private val TRIP_PALETTE = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA),
    Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF7CB342),
)

/** まとめの地図に出す写真の上限（多ければ間引く）。 */
private const val MAX_PHOTO_MARKERS = 150

/**
 * 縦向きのまとめの地図の最小の高さ（上 8 + ボタン 6 個 48×6（SmallFloatingActionButton は 40dp だがタッチ領域で 48dp）
 * + 間 6×5 + 8 + −/+ 48 + 下 8 = 390dp）。
 */
private val SUMMARY_MAP_MIN_HEIGHT = 400.dp

/** 全記録のまとめ（統計・全走行の地図・グラフ）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(vm: MainViewModel, onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    val trips by remember { vm.trips.finished() }.collectAsState(emptyList())
    val settings by vm.settings.collectAsState()
    val digests by produceState<List<TripDigest>?>(null, trips) { value = vm.app.summaries.digests(trips) }
    var period by rememberSaveable { mutableStateOf(SummaryPeriod.ALL) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var heat by rememberSaveable { mutableStateOf(false) }
    var mapKind by rememberSaveable { mutableStateOf(settings.mapKind) }
    var selected by remember { mutableStateOf<Long?>(null) }
    // 写真の位置（0.4.0〜。切り替え。多ければ間引く。タップでそのドライブの写真の拡大表示）
    val photos by remember { vm.photos.linked() }.collectAsState(emptyList())
    var showPhotos by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<List<PhotoEntity>, PhotoEntity>?>(null) }
    val photosByTrip = remember(photos) { photos.groupBy { it.tripId } }

    val range = remember(period, customFrom, customTo) {
        DriveSummaries.range(period, System.currentTimeMillis(), custom = TimeRange(customFrom, customTo?.let { it + 86_400_000L }))
    }
    val inRange = remember(digests, range, photosByTrip) {
        digests?.filter { it.facts.startedAt in range }.orEmpty().map { d -> d.copy(facts = d.facts.copy(photos = photosByTrip[d.facts.id]?.size ?: 0)) }
    }
    val summary = remember(inRange) { DriveSummaries.summarize(inRange.map { it.facts }) }
    val mapPhotos = remember(inRange, photosByTrip) {
        jp.kjtr.drivelogger.geo.Simplify.stride(inRange.flatMap { photosByTrip[it.facts.id].orEmpty() }.filter { it.lat != null && it.lon != null }, MAX_PHOTO_MARKERS)
    }

    // 開拓したマスの表示（0.4.0〜。軌跡だけ / マスだけ / 両方。期間に追従。期間の中で初めて開拓したマスは別の色）
    var cellMode by rememberSaveable { mutableStateOf(CellMode.TRACKS) }
    val firstSeen = remember(digests) { jp.kjtr.drivelogger.stats.CellShapes.firstSeen(digests.orEmpty().map { it.facts }) }
    val cellRects by produceState(Pair(emptyList<jp.kjtr.drivelogger.stats.CellRect>(), emptyList<jp.kjtr.drivelogger.stats.CellRect>()), inRange, range, firstSeen) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val cells = inRange.flatMapTo(HashSet()) { it.facts.cells }
            // 全期間なら「初めて」は全部なので分けない
            val fresh = if (range.fromMs == null && range.toMs == null) emptySet() else jp.kjtr.drivelogger.stats.CellShapes.newIn(cells, firstSeen, range)
            jp.kjtr.drivelogger.stats.CellShapes.merge(cells - fresh) to jp.kjtr.drivelogger.stats.CellShapes.merge(fresh)
        }
    }
    val newCellCount = remember(inRange, range, firstSeen) {
        if (range.fromMs == null && range.toMs == null) 0 else jp.kjtr.drivelogger.stats.CellShapes.newIn(inRange.flatMapTo(HashSet()) { it.facts.cells }, firstSeen, range).size
    }
    // 地図の大きさ（つまみ。離したら設定に保存）
    var ratio by remember { androidx.compose.runtime.mutableFloatStateOf(settings.summaryMapRatio) }
    LaunchedEffect(settings.summaryMapRatio) { ratio = settings.summaryMapRatio }
    val density = LocalDensity.current
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    BackHandler(enabled = fullscreen) { fullscreen = false }

    @Composable
    fun MapPane(modifier: Modifier, topInset: androidx.compose.ui.unit.Dp = 0.dp, bottomPad: androidx.compose.ui.unit.Dp = 0.dp) {
        Box(modifier) {
            AllTripsMap(
                inRange, heat = heat, mapKind = mapKind, selected = selected, onSelect = { selected = it },
                fullscreen = fullscreen, onFullscreen = { fullscreen = !fullscreen },
                onToggleHeat = { heat = !heat },
                onToggleKind = { mapKind = MapKind.entries[(mapKind.ordinal + 1) % MapKind.entries.size] },
                photos = if (showPhotos) mapPhotos else emptyList(), showPhotos = showPhotos, onTogglePhotos = { showPhotos = !showPhotos },
                onPhoto = { p -> viewing = photosByTrip[p.tripId].orEmpty() to p },
                cellMode = cellMode, onCellMode = { cellMode = CellMode.entries[(cellMode.ordinal + 1) % CellMode.entries.size] },
                cells = cellRects.first, newCells = cellRects.second, newCellCount = newCellCount,
                topInset = topInset, bottomPad = bottomPad,
                modifier = Modifier.fillMaxSize(),
            )
            val sel = inRange.firstOrNull { it.facts.id == selected }?.facts
            if (sel != null) {
                // 右下のズームの −/+（0.4.1〜）にかからない高さ
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = bottomPad + 60.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sel.name, style = MaterialTheme.typography.titleSmall)
                            Text(Format.dateTime(sel.startedAt) + "  " + Format.km(sel.distanceM) + "  " + Format.duration(sel.endedAt - sel.startedAt), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onOpenTrip(sel.id) }) { Text(stringResource(R.string.summary_open_trip)) }
                    }
                }
            }
        }
    }

    /** 期間・統計・グラフ（地図の下・右、全画面ではシートの中）。 */
    @Composable
    fun InfoContent(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryPeriod.entries.forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { if (p == SummaryPeriod.CUSTOM) picking = true else period = p },
                        label = {
                            Text(
                                if (p == SummaryPeriod.CUSTOM && period == p && customFrom != null) {
                                    Format.dayStamp(customFrom!!) + "〜" + (customTo?.let { Format.dayStamp(it) } ?: "")
                                } else periodLabel(p),
                            )
                        },
                    )
                }
            }
            if (digests == null) {
                Text(stringResource(R.string.analysis_loading), Modifier.padding(vertical = 16.dp))
                return@Column
            }
            StatsGrid(summary, onOpenTrip, newCellCount, onCells = { cellMode = CellMode.BOTH })
            Text(stringResource(R.string.summary_map_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Charts(summary)
        }
    }

    if (fullscreen) {
        FullscreenMapWithSheet(
            title = stringResource(R.string.summary_sheet_title, summary.tripCount, Format.km(summary.distanceM)),
            map = { top, bottom -> MapPane(Modifier.fillMaxSize(), topInset = top, bottomPad = bottom) },
            sheet = { InfoContent(Modifier.fillMaxWidth().weight(1f)) },
        )
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val onStopped = { vm.updateSettings { it.copy(summaryMapRatio = ratio) } }
            if (landscape) {
                // 横長: 地図が左、統計・グラフが右
                val full = maxWidth
                Row(Modifier.fillMaxSize()) {
                    MapPane(Modifier.fillMaxHeight().width(full * ratio))
                    RatioHandle(vertical = false, totalPx = with(density) { full.toPx() }, ratio = ratio, onRatio = { ratio = it }, onStopped = onStopped)
                    InfoContent(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                val full = maxHeight
                Column(Modifier.fillMaxSize()) {
                    // 右のボタン 6 個と −/+ が全部見える高さは確保する（0.4.1〜。つまみで小さくしてもこれより低くしない。
                    // 横向きなどで足りないときはボタン列が縦にスクロールする）
                    // （つまみも止める。止めた位置より下へ引いても動かない）
                    val minRatio = if (full > 0.dp) minOf(SUMMARY_MAP_MIN_HEIGHT / full, jp.kjtr.drivelogger.data.SettingsStore.MAP_RATIO_MAX) else 0f
                    MapPane(Modifier.fillMaxWidth().height(full * maxOf(ratio, minRatio)))
                    RatioHandle(vertical = true, totalPx = with(density) { full.toPx() }, ratio = maxOf(ratio, minRatio), onRatio = { ratio = maxOf(it, minRatio) }, onStopped = onStopped)
                    InfoContent(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    viewing?.let { (list, p) -> PhotoViewer(vm, list, p, onDismiss = { viewing = null }) }
    if (picking) {
        DateRangeDialog(customFrom, customTo, onPicked = { from, to -> customFrom = from; customTo = to; period = SummaryPeriod.CUSTOM }, onDismiss = { picking = false })
    }
}

/** 地図の開拓したマスの表示。 */
enum class CellMode { TRACKS, CELLS, BOTH }

/** 月・曜日・時間帯・速度のグラフ。 */
@Composable
private fun Charts(summary: DriveSummary) {
    Column {
            ChartTitle(stringResource(R.string.summary_monthly))
            BarChart(
                values = summary.monthlyM.map { it.second / 1000.0 },
                labels = summary.monthlyM.map { it.first.substring(2).replace('-', '/') },
                unit = "km", modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            ChartTitle(stringResource(R.string.summary_weekday))
            BarChart(
                values = summary.weekdayMs.map { it / 3_600_000.0 },
                labels = listOf("日", "月", "火", "水", "木", "金", "土"),
                unit = stringResource(R.string.summary_hours_unit), modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            ChartTitle(stringResource(R.string.summary_hour))
            BarChart(
                values = summary.hourMs.map { it / 3_600_000.0 },
                labels = (0..23).map { if (it % 3 == 0) it.toString() else "" },
                unit = stringResource(R.string.summary_hours_unit), modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            ChartTitle(stringResource(R.string.summary_speed))
            BarChart(
                values = summary.speedBinsMs.map { it / 60_000.0 },
                labels = summary.speedBinsMs.indices.map { (it * 10).toString() },
                unit = stringResource(R.string.hist_axis_min), modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            Text(stringResource(R.string.summary_speed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** 期間を選ぶ（端末の日付の 0 時で返す）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(from: Long?, to: Long?, onPicked: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = from, initialSelectedEndDateMillis = to)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val s = state.selectedStartDateMillis
                if (s != null) {
                    // DatePicker は UTC の 0 時。端末の日付の 0 時に直す
                    val off = java.util.TimeZone.getDefault().getOffset(s)
                    onPicked(s - off, (state.selectedEndDateMillis ?: s) - off)
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(500.dp))
    }
}

@Composable
private fun periodLabel(p: SummaryPeriod) = stringResource(
    when (p) {
        SummaryPeriod.ALL -> R.string.period_all
        SummaryPeriod.THIS_YEAR -> R.string.period_year
        SummaryPeriod.THIS_MONTH -> R.string.period_month
        SummaryPeriod.LAST_30_DAYS -> R.string.period_30days
        SummaryPeriod.CUSTOM -> R.string.period_custom
    },
)

@Composable
private fun ChartTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun StatsGrid(s: DriveSummary, onOpenTrip: (Long) -> Unit, newCells: Int = 0, onCells: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                StatCell(stringResource(R.string.summary_distance), String.format(Locale.ROOT, "%.1f", s.distanceM / 1000.0), Modifier.weight(1f), unit = "km")
                StatCell(stringResource(R.string.summary_time), Format.duration(s.movingMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.summary_trips), s.tripCount.toString(), Modifier.weight(1f))
            }
            Row {
                StatCell(stringResource(R.string.stat_avg), Format.speed(s.avgSpeedMps), Modifier.weight(1f), unit = "km/h")
                StatCell(stringResource(R.string.summary_days), s.days.toString(), Modifier.weight(1f), unit = stringResource(R.string.summary_days_unit))
                // タップで地図をマスの表示に
                StatCell(
                    stringResource(R.string.summary_cells), s.cells.toString() + if (newCells > 0) " (+$newCells)" else "",
                    Modifier.weight(1f).clickable(onClick = onCells),
                )
            }
            Row {
                StatCell(stringResource(R.string.summary_ascent), s.ascentM.roundToInt().toString(), Modifier.weight(1f), unit = "m")
                StatCell(stringResource(R.string.summary_video), Format.duration(s.videoMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.summary_protects), s.protects.toString(), Modifier.weight(1f))
            }
            Row {
                StatCell(stringResource(R.string.stat_parks), s.parks.toString(), Modifier.weight(1f), unit = stringResource(R.string.unit_times))
                StatCell(stringResource(R.string.stat_park_time), Format.duration(s.parkMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_photos), s.photos.toString(), Modifier.weight(1f), unit = stringResource(R.string.unit_photos))
            }
            s.maxSpeed?.let { (v, t) ->
                RecordLine(stringResource(R.string.summary_max_speed, Format.speed(v)), t.name) { onOpenTrip(t.id) }
            }
            s.longestDistance?.let { t -> RecordLine(stringResource(R.string.summary_longest_distance, Format.km(t.distanceM)), t.name) { onOpenTrip(t.id) } }
            s.longestTime?.let { t -> RecordLine(stringResource(R.string.summary_longest_time, Format.duration(t.endedAt - t.startedAt)), t.name) { onOpenTrip(t.id) } }
            Text(stringResource(R.string.summary_cells_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RecordLine(title: String, trip: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(trip, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.summary_open_trip)) }
    }
}

/** 全トリップの軌跡を重ねた地図。軌跡をタップでそのトリップを選ぶ。 */
@Composable
private fun AllTripsMap(
    trips: List<TripDigest>, heat: Boolean, mapKind: MapKind, selected: Long?, onSelect: (Long?) -> Unit,
    fullscreen: Boolean, onFullscreen: () -> Unit, onToggleHeat: () -> Unit, onToggleKind: () -> Unit,
    photos: List<PhotoEntity>, showPhotos: Boolean, onTogglePhotos: () -> Unit, onPhoto: (PhotoEntity) -> Unit,
    cellMode: CellMode, onCellMode: () -> Unit, cells: List<jp.kjtr.drivelogger.stats.CellRect>, newCells: List<jp.kjtr.drivelogger.stats.CellRect>, newCellCount: Int,
    topInset: androidx.compose.ui.unit.Dp, bottomPad: androidx.compose.ui.unit.Dp, modifier: Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f) }
    var ready by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bounds = remember(trips) { GeoBounds.of(trips.flatMap { d -> d.lines.coarse.flatten() }) }
    val padPx = with(density) { 24.dp.roundToPx() }
    LaunchedEffect(bounds, ready, size) {
        val b = bounds ?: return@LaunchedEffect
        if (ready && size.width > 0) camera.fitTo(b, padPx, size.width, size.height)
    }
    // ズーム 12 以上で細かい線
    val fine = camera.position.zoom >= 12f
    val heatColor = colorResource(R.color.recording)
    val cellColor = colorResource(R.color.cell_explored)
    val newCellColor = colorResource(R.color.cell_new)
    Box(modifier.onSizeChanged { size = it }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = mapKind.toMapType()),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false),
            onMapClick = { onSelect(null) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topInset, bottom = bottomPad),
        ) {
            OnMapReady { ready = true }
            // 開拓したマス（隣り合うマスをまとめた矩形。表示範囲のものだけ）
            if (cellMode != CellMode.TRACKS) {
                CellPolygons(cells, cellColor, camera, zIndex = 0.5f)
                CellPolygons(newCells, newCellColor, camera, zIndex = 0.6f)
            }
            if (cellMode != CellMode.CELLS) trips.forEachIndexed { i, d ->
                val isSel = d.facts.id == selected
                val color = when {
                    isSel -> Color(0xFF212121)
                    heat -> heatColor.copy(alpha = 0.28f)
                    else -> TRIP_PALETTE[i % TRIP_PALETTE.size].copy(alpha = 0.85f)
                }
                val w = with(density) { (if (isSel) 7.dp else if (heat) 6.dp else 4.dp).toPx() }
                for (seg in if (fine) d.lines.fine else d.lines.coarse) {
                    if (seg.size < 2) continue
                    Polyline(
                        points = remember(seg) { seg.map { it.toLatLng() } }, color = color, width = w, jointType = JointType.ROUND,
                        zIndex = if (isSel) 5f else 2f, clickable = true, onClick = { onSelect(d.facts.id) },
                    )
                }
            }
            if (photos.isNotEmpty()) PhotoMarkers(photos, onClick = onPhoto)
        }
        // 右のボタン列＋独自のズームの ±（地図が小さいと列は縦にスクロール。0.4.1〜、標準のズームボタンとの重なりを無くした）
        MapSideButtons(camera, ready, Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 8.dp, bottom = bottomPad + 8.dp)) {
            SmallFloatingActionButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.summary_fullscreen))
            }
            SmallFloatingActionButton(onClick = { val b = bounds ?: return@SmallFloatingActionButton; if (ready) scope.launch { camera.fitTo(b, padPx, size.width, size.height) } }) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.summary_fit))
            }
            SmallFloatingActionButton(onClick = onToggleHeat) { Icon(Icons.Filled.Palette, contentDescription = stringResource(R.string.summary_color_mode)) }
            SmallFloatingActionButton(onClick = onToggleKind) { Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.set_map_kind)) }
            SmallFloatingActionButton(
                onClick = onTogglePhotos,
                containerColor = if (showPhotos) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (showPhotos) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.summary_photos_toggle)) }
            SmallFloatingActionButton(
                onClick = onCellMode,
                containerColor = if (cellMode != CellMode.TRACKS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (cellMode != CellMode.TRACKS) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.GridOn, contentDescription = stringResource(R.string.summary_cells_toggle)) }
        }
        Column(Modifier.align(Alignment.TopStart).padding(top = topInset + 8.dp, start = 8.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    when (cellMode) {
                        CellMode.TRACKS -> if (heat) R.string.summary_color_heat else R.string.summary_color_trip
                        CellMode.CELLS -> R.string.summary_cells_only
                        CellMode.BOTH -> if (heat) R.string.summary_color_heat else R.string.summary_color_trip
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.hudPanelLight(),
            )
            if (cellMode != CellMode.TRACKS) {
                Row(Modifier.hudPanelLight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(cellColor))
                    Text(" " + stringResource(R.string.summary_cells_legend), style = MaterialTheme.typography.labelSmall)
                    if (newCellCount > 0) {
                        Box(Modifier.padding(start = 8.dp).size(10.dp).background(newCellColor))
                        Text(" " + stringResource(R.string.summary_cells_new_legend, newCellCount), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.hudPanelLight(): Modifier = this
    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    .padding(horizontal = 6.dp, vertical = 3.dp)

/** 簡単な棒グラフ（値・下のラベル・左上の単位）。 */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, unit: String, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (values.isEmpty() || values.all { it <= 0 }) {
        Text(stringResource(R.string.summary_no_data), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val maxV = values.maxOrNull()!!.coerceAtLeast(1e-6)
    val step = jp.kjtr.drivelogger.stats.DriveAnalysis.niceStep(maxV)
    val top = kotlin.math.ceil(maxV / step - 1e-9) * step
    Canvas(modifier.padding(top = 4.dp)) {
        val left = 34.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val topY = 24.dp.toPx()
        val plotH = bottom - topY
        val barW = ((size.width - left) / values.size).coerceAtMost(56.dp.toPx())
        fun y(v: Double) = (bottom - v / top * plotH).toFloat()
        var v = 0.0
        while (v <= top + 1e-9) {
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
            val lab = if (step >= 1) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)
            val r = tm.measure(lab, labelStyle)
            drawText(r, topLeft = Offset(left - r.size.width - 3.dp.toPx(), yy - r.size.height / 2f))
            v += step
        }
        drawText(tm.measure(unit, labelStyle), topLeft = Offset(0f, 0f))
        val gap = (barW * 0.15f).coerceAtMost(4.dp.toPx())
        values.forEachIndexed { i, value ->
            if (value > 0) {
                val yy = y(value)
                drawRect(bar, topLeft = Offset(left + i * barW + gap / 2, yy), size = Size((barW - gap).coerceAtLeast(1f), bottom - yy))
            }
            val l = labels.getOrNull(i).orEmpty()
            if (l.isNotEmpty()) {
                val r = tm.measure(l, labelStyle)
                drawText(r, topLeft = Offset(left + i * barW + barW / 2 - r.size.width / 2f, bottom + 2.dp.toPx()))
            }
        }
    }
}
