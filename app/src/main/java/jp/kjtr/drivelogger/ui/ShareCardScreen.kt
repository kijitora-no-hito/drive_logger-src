package jp.kjtr.drivelogger.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.maps.GoogleMap as RawMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.SettingsStore
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.geo.GeoBounds
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.geo.Simplify
import jp.kjtr.drivelogger.photo.CardContent
import jp.kjtr.drivelogger.photo.CardSize
import jp.kjtr.drivelogger.photo.CardStat
import jp.kjtr.drivelogger.photo.CardStats
import jp.kjtr.drivelogger.photo.CardTripStats
import jp.kjtr.drivelogger.photo.ShareCardLayout
import jp.kjtr.drivelogger.photo.ShareCardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * SNS 用の画像（自転車ルートロガーの ShareCardScreen を車向けに）。上の地図（Google マップ）を画像の地図の枠と同じ縦横比で表示し、
 * ［画像を作る］でスナップショット（ロゴ・著作権表示ごと）を撮って、写真・統計・タイトルと 1 枚にする。地図が読み込めない（キーが通らない等）・
 * ［地図を使わない］ときは、軌跡だけを白地に描く。記録中のトリップなら「ここまで」の内容で作る（記録は止めない）。
 */
@OptIn(ExperimentalMaterial3Api::class, MapsComposeExperimentalApi::class)
@Composable
fun ShareCardScreen(vm: MainViewModel, tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val trip by remember(tripId) { vm.trips.trip(tripId) }.collectAsState(null)
    val photos by remember(tripId) { vm.photos.forTrip(tripId) }.collectAsState(emptyList())
    val parks by remember(tripId) { vm.trips.parks(tripId) }.collectAsState(emptyList())
    val parkLocs = parks.mapNotNull { p -> p.lat?.let { la -> p.lon?.let { LatLon(la, it) } } }
    // 記録中のトリップなら、統計と軌跡は記録中の最新の値を使う（記録は止めない。画像は「ここまで」の内容）
    val rec by vm.session.state.collectAsState()
    val live = rec?.takeIf { it.tripId == tripId }
    val liveLine by vm.session.line.collectAsState()
    val savedSegments by produceState(emptyList<List<LatLon>>(), tripId) {
        value = withContext(Dispatchers.Default) {
            TripRepository.segmentsOf(vm.trips.displayPoints(tripId)).map { s -> Simplify.forDisplay(s.map { LatLon(it.lat, it.lon) }, 3000) }
        }
    }
    // 記録中は軌跡が伸び続けるので、画面を開いた時点の軌跡で固定する（地図の範囲合わせが動き続けないように）
    val liveSegments = remember(tripId, live != null) {
        if (live != null) liveLine.filter { it.size >= 2 }.map { Simplify.forDisplay(it, 3000) } else null
    }
    val segments = liveSegments?.takeIf { it.isNotEmpty() } ?: savedSegments

    var size by remember { mutableStateOf(CardSize.SQUARE) }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var useMap by remember { mutableStateOf(true) }
    var showApp by remember { mutableStateOf(true) }
    var title by remember(trip?.id) { mutableStateOf(trip?.name ?: "") }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var initSelected by remember { mutableStateOf(false) }
    LaunchedEffect(photos) {
        if (!initSelected && photos.isNotEmpty()) {
            selected = photos.filter { it.lat != null }.ifEmpty { photos }.take(ShareCardLayout.MAX_PHOTOS).map { it.id }
            initSelected = true
        }
        selected = selected.filter { id -> photos.any { it.id == id } }
    }
    val chosen = selected.mapNotNull { id -> photos.firstOrNull { it.id == id } }
    val layout = ShareCardLayout.compute(size, chosen.size, showApp)

    var rawMap by remember { mutableStateOf<RawMap?>(null) }
    var mapLoadedOnce by remember { mutableStateOf(false) }
    var mapViewSize by remember { mutableStateOf(IntSize.Zero) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var usedFallback by remember { mutableStateOf(false) }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.68, 139.76), 5f) }

    // 統計（保存したトリップか、記録中の「ここまで」）
    val t0 = trip
    val stats = when {
        live != null -> CardTripStats(live.distanceM, System.currentTimeMillis() - live.startedAt, live.movingMs, live.stillMs, live.maxSpeedMps)
        t0 != null -> CardTripStats(t0.distanceM, (t0.endedAt ?: t0.startedAt) - t0.startedAt, t0.movingMs, t0.stillMs, t0.maxSpeedMps)
        else -> null
    }
    val threshold = settings.snsThresholdKmh
    val picked = stats?.let { CardStats.pick(it, settings.cardHideMax, settings.cardHideOverThreshold, threshold) } ?: emptyList()
    val hiddenByThreshold = if (stats != null && settings.cardHideOverThreshold) CardStats.hiddenByThreshold(stats, threshold) else emptyList()

    fun bounds(): GeoBounds? = GeoBounds.of(segments.flatten() + parkLocs + chosen.mapNotNull { p -> p.lat?.let { LatLon(it, p.lon!!) } })
    LaunchedEffect(rawMap, segments, mapViewSize, size, selected, parks) {
        val b = bounds() ?: return@LaunchedEffect
        if (rawMap == null || mapViewSize.width == 0) return@LaunchedEffect
        // 枠の縦横比が変わった直後は地図の大きさがまだ前のままのことがあるので、少し待ってから合わせる
        kotlinx.coroutines.delay(200)
        cameraState.fitTo(b, with(density) { 28.dp.roundToPx() }, mapViewSize.width, mapViewSize.height)
    }

    fun statLabel(s: CardStat) = context.getString(
        when (s) {
            CardStat.DISTANCE -> R.string.stat_distance
            CardStat.ELAPSED -> R.string.stat_elapsed
            CardStat.AVG -> R.string.stat_avg
            CardStat.MAX -> R.string.stat_max
            CardStat.STILL -> R.string.stat_still
        },
    )

    fun statValue(s: CardStat, st: CardTripStats) = when (s) {
        CardStat.DISTANCE -> Format.km(st.distanceM)
        CardStat.ELAPSED -> Format.duration(st.elapsedMs)
        CardStat.AVG -> Format.speed(st.avgSpeedMps) + " km/h"
        CardStat.MAX -> Format.speed(st.maxSpeedMps) + " km/h"
        CardStat.STILL -> Format.duration(st.stillMs)
    }

    fun generate() {
        val t = trip ?: return
        val st = stats ?: return
        working = true
        result = null
        scope.launch {
            val map = rawMap
            // 地図のスナップショット（読み込み完了を待つ。キーが通らないと完了しないので諦めて代替表示）
            // 写真のピンの位置（chosen の順）のあとに駐車の位置（parkLocs の順）を並べて返す
            suspend fun shoot(m: RawMap, waitLoaded: Boolean): Pair<Bitmap, List<PointF?>>? = suspendCancellableCoroutine { cont ->
                val take = {
                    m.snapshot { bmp ->
                        if (bmp == null) {
                            if (cont.isActive) cont.resume(null)
                        } else {
                            val proj = m.projection
                            fun norm(lat: Double, lon: Double): PointF {
                                val pt = proj.toScreenLocation(LatLng(lat, lon))
                                return PointF(pt.x.toFloat() / bmp.width, pt.y.toFloat() / bmp.height)
                            }
                            val pins = chosen.map { p -> if (p.lat == null || p.lon == null) null else norm(p.lat, p.lon) } + parkLocs.map { norm(it.lat, it.lon) }
                            if (cont.isActive) cont.resume(bmp to pins)
                        }
                    }
                }
                if (waitLoaded) m.setOnMapLoadedCallback { take() } else take()
            }
            val snap: Pair<Bitmap, List<PointF?>>? = if (useMap && map != null) {
                withTimeoutOrNull(5_000) { shoot(map, waitLoaded = true) }
                    ?: if (mapLoadedOnce) withTimeoutOrNull(3_000) { shoot(map, waitLoaded = false) } else null
            } else null
            usedFallback = useMap && snap == null
            val bmp = withContext(Dispatchers.Default) {
                val photoBmps = chosen.mapIndexed { i, p ->
                    val cell = layout.photos.getOrNull(i)
                    vm.photos.loadPhoto(p, ((cell?.let { maxOf(it.width, it.height) } ?: 400f) * 1.2f).toInt())
                }
                val sub = Format.dateTime(t.startedAt) + (if (live != null) " ・ " + context.getString(R.string.card_so_far) else "")
                ShareCardRenderer.render(
                    layout,
                    CardContent(
                        title = title.ifBlank { t.name }, subtitle = sub,
                        stats = picked.map { statLabel(it) to statValue(it, st) },
                        appName = if (showApp) context.getString(R.string.app_name) else null,
                        mapBitmap = snap?.first, pinsOnSnapshot = snap?.second?.take(chosen.size) ?: chosen.map { null },
                        route = segments, photoLocations = chosen.map { p -> p.lat?.let { LatLon(it, p.lon!!) } },
                        photos = photoBmps,
                        parksOnSnapshot = snap?.second?.drop(chosen.size)?.filterNotNull().orEmpty(),
                        parkLocations = parkLocs,
                    ),
                )
            }
            result = bmp
            working = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.card_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (live != null) {
                Text(stringResource(R.string.card_recording_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // 画像の地図の枠と同じ縦横比の地図（動かして範囲を調整できる）
            Box(
                Modifier.fillMaxWidth().aspectRatio(layout.map.width / layout.map.height).clip(RoundedCornerShape(10.dp))
                    .onSizeChanged { mapViewSize = it },
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(mapType = mapType),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, rotationGesturesEnabled = false),
                    onMapLoaded = { mapLoadedOnce = true },
                ) {
                    MapEffect(Unit) { m -> rawMap = m }
                    for (seg in segments) {
                        if (seg.size >= 2) Polyline(points = remember(seg) { seg.map { it.toLatLng() } }, color = Color(0xFF1E88E5), width = with(density) { 5.dp.toPx() })
                    }
                    // 駐車の位置は画像に描く（地図の上に置くと 2 重になるので、ここでは出さない）
                }
            }
            Text(stringResource(R.string.card_map_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.card_size), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardSize.entries.forEach { s ->
                    FilterChip(selected = size == s, onClick = { size = s; result = null }, label = {
                        Text(stringResource(when (s) { CardSize.SQUARE -> R.string.card_square; CardSize.PORTRAIT -> R.string.card_portrait; CardSize.LANDSCAPE -> R.string.card_landscape }))
                    })
                }
            }
            Text(stringResource(R.string.card_map_type), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MapType.NORMAL to R.string.map_normal, MapType.SATELLITE to R.string.map_satellite, MapType.TERRAIN to R.string.map_terrain).forEach { (t, label) ->
                    FilterChip(selected = useMap && mapType == t, onClick = { useMap = true; mapType = t; result = null }, label = { Text(stringResource(label)) })
                }
                FilterChip(selected = !useMap, onClick = { useMap = false; result = null }, label = { Text(stringResource(R.string.card_no_map)) })
            }
            OutlinedTextField(value = title, onValueChange = { title = it; result = null }, label = { Text(stringResource(R.string.card_title_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.card_show_app), null, showApp) { showApp = it; result = null }

            // 速度の項目（0.3.0 の「速度を隠す」と同じしきい値の考え方。低い数字に偽ることはせず、項目ごと出さない）
            Text(stringResource(R.string.card_speed_section), style = MaterialTheme.typography.labelLarge)
            SwitchRow(stringResource(R.string.card_hide_max), null, settings.cardHideMax) { v -> vm.updateSettings { it.copy(cardHideMax = v) }; result = null }
            SwitchRow(stringResource(R.string.card_hide_over, threshold), stringResource(R.string.card_hide_over_desc), settings.cardHideOverThreshold) { v ->
                vm.updateSettings { it.copy(cardHideOverThreshold = v) }; result = null
            }
            if (settings.cardHideOverThreshold) {
                val choices = SettingsStore.SNS_THRESHOLDS
                Slider(
                    value = choices.indexOf(threshold).coerceAtLeast(0).toFloat(),
                    onValueChange = { v -> val kmh = choices[v.roundToInt().coerceIn(0, choices.lastIndex)]; if (kmh != threshold) { vm.updateSettings { it.copy(snsThresholdKmh = kmh) }; result = null } },
                    valueRange = 0f..choices.lastIndex.toFloat(), steps = (choices.size - 2).coerceAtLeast(0),
                )
                if (hiddenByThreshold.isNotEmpty()) {
                    Text(
                        stringResource(R.string.card_hidden_note, hiddenByThreshold.joinToString("・") { statLabel(it) }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(stringResource(R.string.card_photos, ShareCardLayout.MAX_PHOTOS), style = MaterialTheme.typography.labelLarge)
            if (photos.isEmpty()) Text(stringResource(R.string.card_no_photos), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { p ->
                    val idx = selected.indexOf(p.id)
                    val thumb = rememberThumb(p.thumbPath)
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(6.dp))
                            .border(if (idx >= 0) 3.dp else 1.dp, if (idx >= 0) Color(0xFF1565C0) else Color(0x33000000), RoundedCornerShape(6.dp))
                            .clickable {
                                selected = if (idx >= 0) selected - p.id else if (selected.size < ShareCardLayout.MAX_PHOTOS) selected + p.id else selected
                                result = null
                            },
                    ) {
                        if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (idx >= 0) {
                            Box(Modifier.padding(4.dp).size(22.dp).background(Color(0xFF1565C0), CircleShape), contentAlignment = Alignment.Center) {
                                Text("${idx + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (photos.isNotEmpty()) Text(stringResource(R.string.card_photos_hint), style = MaterialTheme.typography.bodySmall)

            Button(onClick = { generate() }, enabled = !working && trip != null, modifier = Modifier.fillMaxWidth()) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.card_generate))
            }
            result?.let { bmp ->
                if (usedFallback) Text(stringResource(R.string.card_fallback_used), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Image(
                    bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).border(1.dp, Color(0x33000000)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val uri = vm.photos.saveToLibrary(bmp, cardFileName(trip?.name ?: "card"))
                            vm.toast(context.getString(if (uri != null) R.string.card_saved else R.string.card_save_failed))
                        }
                    }) { Text(stringResource(R.string.card_save)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                                    dir.listFiles()?.forEach { it.delete() }
                                    val f = File(dir, cardFileName(trip?.name ?: "card") + ".jpg")
                                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                                }.getOrNull()
                            } ?: return@launch
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.share))) }
                        }
                    }) { Text(stringResource(R.string.share)) }
                }
            }
        }
    }
}

private fun cardFileName(name: String) = "DL_card_" + Format.safeFileName(name).replace(' ', '_') + "_" + System.currentTimeMillis() / 1000
