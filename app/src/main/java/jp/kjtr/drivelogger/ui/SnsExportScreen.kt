package jp.kjtr.drivelogger.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.SettingsStore
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.share.ClipChain
import jp.kjtr.drivelogger.share.CoverPlan
import jp.kjtr.drivelogger.share.ExportChoice
import jp.kjtr.drivelogger.share.ExportPart
import jp.kjtr.drivelogger.share.ExportPlanner
import jp.kjtr.drivelogger.share.ExportRequest
import jp.kjtr.drivelogger.share.ExportSource
import jp.kjtr.drivelogger.share.ExportState
import jp.kjtr.drivelogger.share.ExportTimeline
import jp.kjtr.drivelogger.share.SnsPreset
import jp.kjtr.drivelogger.share.SpeedHide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SNS 用の書き出し（DESIGN.md §5.1）。範囲の切り出し・プリセット・速度（と位置・地図）を隠す → 書き出し → 共有 / ギャラリーに保存。
 * **アップロードはしない**（共有の画面で各 SNS のアプリに渡す）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnsExportScreen(vm: MainViewModel, clipId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val all by remember { vm.clips.all() }.collectAsState(emptyList())
    val exportState by vm.app.sns.state.collectAsState()
    val clip = all.firstOrNull { it.id == clipId }
    val chain = remember(all, clipId) { ClipChain.around(all, clipId) }
    var joined by rememberSaveable { mutableStateOf(false) }
    val files: List<ClipEntity> = if (joined && chain.size > 1) chain else listOfNotNull(clip)
    val lengths = files.map { it.durationMs.coerceAtLeast(1) }
    val total = lengths.sum()
    val firstIndex = files.indexOfFirst { it.id == clipId }.coerceAtLeast(0)
    val baseOffset = lengths.take(firstIndex).sum()

    // 範囲（つないだ時間軸の ms）
    var start by remember(files.map { it.id }) { mutableLongStateOf(baseOffset) }
    var end by remember(files.map { it.id }) { mutableLongStateOf(baseOffset + (lengths.getOrNull(firstIndex) ?: 0L)) }
    val preset = settings.snsPreset
    LaunchedEffect(files.map { it.id }, preset) {
        // 上限があるなら初めは上限に収める
        val max = preset.maxDurationMs
        if (max != null && end - start > max) end = start + max
    }

    // 再生（範囲の確認）
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var previewOut by remember { mutableStateOf(false) }
    LaunchedEffect(files.map { it.id }, previewOut) {
        if (previewOut) return@LaunchedEffect
        player.setMediaItems(files.map { MediaItem.fromUri(android.net.Uri.fromFile(vm.clips.fileOf(it))) })
        player.prepare()
        seekGlobal(player, lengths, start)
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = if (previewOut) player.currentPosition else lengths.take(player.currentMediaItemIndex).sum() + player.currentPosition
            delay(200)
        }
    }

    // GPS の点・配置
    val wallFrom = files.firstOrNull()?.startedAt ?: 0L
    val wallTo = files.lastOrNull()?.let { it.startedAt + it.durationMs } ?: 0L
    val points by produceState(emptyList<SyncPoint>(), wallFrom, wallTo) { value = vm.syncPointsBetween(wallFrom - 15_000, wallTo + 15_000) }
    val layouts = remember(files, settings) { files.map { vm.burnLayoutOf(it) } }
    val anyBurn = files.any { it.burnIn }
    val anyEstimated = layouts.any { it?.estimated == true }
    val speedBurned = layouts.any { it?.hasSpeed == true }
    val latLonBurned = layouts.any { it?.latLonText != null }
    val mapBurned = layouts.any { it?.map != null }
    // あとから重ねる表示（焼き込みの無い動画）: 入れる項目を選ぶ。入れない項目は描かない
    val anyLater = files.any { vm.canOverlayLater(it) }
    var items by remember { mutableStateOf(vm.defaultOverlayItems()) }
    val mapAvailable = settings.burnMap
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    val parts = remember(files, start, end) {
        ExportTimeline.split(lengths, start, end).map { (i, a, b) -> ExportPart(files[i].id, files[i].startedAt, a, b) }
    }
    val timeline = remember(parts) { ExportTimeline(parts) }
    val wallSpans = remember(points, parts, settings.snsThresholdKmh) {
        if (parts.isEmpty()) emptyList() else SpeedHide.intervals(
            points, parts.first().wallStartMs + parts.first().inMs, parts.last().wallStartMs + parts.last().outMs, settings.snsThresholdKmh.toDouble(),
        )
    }
    val hideSpeed = settings.snsHideSpeed && speedBurned
    val laterHideSpeed = settings.snsHideSpeed && anyLater && items.speed
    val hideLatLon = settings.snsHideLatLon && latLonBurned
    val hideMap = settings.snsHideMap && mapBurned

    val first = files.firstOrNull()
    val hasAudio = files.isNotEmpty() && files.all { it.hasAudio }
    var audio by rememberSaveable { mutableStateOf(true) }
    var customShort by rememberSaveable { mutableStateOf<Int?>(1080) }
    var customBps by rememberSaveable { mutableStateOf(8_000_000) }
    val choice = ExportChoice(preset, settings.snsCropVertical, customShort, customBps, audio)
    val srcBps = files.sumOf { it.sizeBytes }.let { b -> if (total > 0 && b > 0) (b * 8_000 / total).toInt() else null }
    val plan = remember(choice, first?.width, first?.height, end - start, hasAudio, srcBps) {
        ExportPlanner.plan(choice, first?.width ?: 1280, first?.height ?: 720, end - start, hasAudio, srcBps)
    }
    val cutWarning = plan.crop != null && layouts.filterNotNull().any { l ->
        listOfNotNull(l.speedText, l.speedGauge?.bounds, l.latLonText, l.map).any { CoverPlan.partlyCut(it, plan.crop) || !it.intersects(plan.crop) }
    }

    var warnShare by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dontShowAgain by remember { mutableStateOf(false) }
    val galleryPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val d = exportState as? ExportState.Done
        if (ok && d != null) vm.saveSnsToGallery(d.file, wallFrom + start)
    }

    fun withShareWarning(action: () -> Unit) {
        if (settings.snsShareWarnHidden) action() else {
            dontShowAgain = false
            warnShare = action
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sns_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        if (clip == null) {
            Text(stringResource(R.string.clip_missing), Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val videoH = (maxWidth * 9 / 16).coerceAtMost(maxHeight * 0.45f)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(videoH).background(Color.Black),
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.sns_no_upload), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // ---- 範囲 ----
                    Section(stringResource(R.string.sns_range))
                    if (chain.size > 1) {
                        FilterChip(
                            selected = joined, onClick = { joined = !joined; previewOut = false },
                            label = { Text(stringResource(R.string.sns_join, chain.size, Format.duration(chain.sumOf { it.durationMs }))) },
                        )
                    }
                    if (total > 0) {
                        RangeSlider(
                            value = start.toFloat()..end.toFloat(),
                            onValueChange = { r ->
                                start = r.start.toLong()
                                end = r.endInclusive.toLong().coerceAtLeast(start + 1_000).coerceAtMost(total)
                            },
                            valueRange = 0f..total.toFloat(),
                        )
                    }
                    Text(
                        stringResource(R.string.sns_range_value, Format.duration(start), Format.duration(end), Format.duration(end - start), Format.duration(position)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (!previewOut) start = position.coerceIn(0, (end - 1_000).coerceAtLeast(0)) }) { Text(stringResource(R.string.sns_set_start)) }
                        OutlinedButton(onClick = { if (!previewOut) end = position.coerceIn(start + 1_000, total) }) { Text(stringResource(R.string.sns_set_end)) }
                        OutlinedButton(onClick = {
                            previewOut = false
                            seekGlobal(player, lengths, start)
                            player.play()
                        }) { Icon(Icons.Filled.PlayArrow, null); Text(stringResource(R.string.sns_play_range)) }
                    }

                    // ---- プリセット ----
                    Section(stringResource(R.string.sns_preset))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in SnsPreset.entries) {
                            FilterChip(selected = preset == p, onClick = { vm.updateSettings { it.copy(snsPreset = p) } }, label = { Text(presetLabel(p)) })
                        }
                    }
                    Text(presetNote(preset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (preset.canCrop) {
                        SwitchRow(stringResource(R.string.sns_crop), stringResource(R.string.sns_crop_desc), settings.snsCropVertical) { v -> vm.updateSettings { it.copy(snsCropVertical = v) } }
                    }
                    if (preset == SnsPreset.CUSTOM) {
                        Text(stringResource(R.string.sns_custom_size), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in listOf(null, 1080, 720, 480)) {
                                FilterChip(selected = customShort == s, onClick = { customShort = s }, label = { Text(if (s == null) stringResource(R.string.sns_size_source) else "${s}p") })
                            }
                        }
                        Text(stringResource(R.string.sns_custom_bitrate), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (b in listOf(2_000_000, 4_000_000, 8_000_000, 12_000_000, 20_000_000)) {
                                FilterChip(selected = customBps == b, onClick = { customBps = b }, label = { Text("${b / 1_000_000} Mbps") })
                            }
                        }
                    }
                    if (hasAudio) SwitchRow(stringResource(R.string.sns_audio), null, audio) { audio = it }
                    if (plan.tooLong) {
                        Text(stringResource(R.string.sns_too_long, presetLabel(preset), Format.duration(preset.maxDurationMs ?: 0)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { end = start + (preset.maxDurationMs ?: 0) }) { Text(stringResource(R.string.sns_fit_length)) }
                    }
                    Text(
                        stringResource(
                            R.string.sns_estimate, plan.outW, plan.outH,
                            String.format(Locale.ROOT, "%.1f", plan.videoBps / 1_000_000.0), Format.bytes(plan.estimatedBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    )
                    if (plan.reducedForSize) Text(stringResource(R.string.sns_reduced, Format.bytes(preset.maxBytes ?: 0)), style = MaterialTheme.typography.bodySmall)
                    if (cutWarning) Text(stringResource(R.string.sns_crop_cut), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                    // ---- 入れる表示（あとから重ねる） ----
                    if (anyLater) {
                        Section(stringResource(R.string.sns_items))
                        Text(stringResource(R.string.sns_items_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = items.date, onClick = { items = items.copy(date = !items.date) }, label = { Text(stringResource(R.string.burn_date)) })
                            FilterChip(selected = items.speed, onClick = { items = items.copy(speed = !items.speed) }, label = { Text(stringResource(R.string.burn_speed)) })
                            FilterChip(selected = items.heading, onClick = { items = items.copy(heading = !items.heading) }, label = { Text(stringResource(R.string.burn_heading)) })
                            FilterChip(selected = items.latLon, onClick = { items = items.copy(latLon = !items.latLon) }, label = { Text(stringResource(R.string.burn_latlon)) })
                            FilterChip(selected = items.map && mapAvailable, enabled = mapAvailable, onClick = { items = items.copy(map = !items.map) }, label = { Text(stringResource(R.string.sns_item_map)) })
                            FilterChip(selected = items.appName, onClick = { items = items.copy(appName = !items.appName) }, label = { Text(stringResource(R.string.burn_app)) })
                        }
                        if (!mapAvailable) Text(stringResource(R.string.sns_item_map_off), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.sns_items_style, if (settings.burnStyle == jp.kjtr.drivelogger.gauge.MeterStyle.GAUGES) stringResource(R.string.style_gauges) else stringResource(R.string.style_text)), style = MaterialTheme.typography.bodySmall)
                        SwitchRow(stringResource(R.string.sns_hide_speed_later), stringResource(R.string.sns_hide_speed_later_desc), settings.snsHideSpeed, enabled = items.speed) { v -> vm.updateSettings { it.copy(snsHideSpeed = v) } }
                        if (laterHideSpeed) {
                            SwitchRow(stringResource(R.string.sns_hide_dashes), null, settings.hideSpeedDashes) { v -> vm.updateSettings { it.copy(hideSpeedDashes = v) } }
                        }
                    }
                    // ---- 隠す（焼き込んだ動画） ----
                    if (anyBurn || !anyLater) Section(stringResource(R.string.sns_hide))
                    if (!anyBurn) {
                        if (!anyLater) Text(stringResource(R.string.sns_no_burn), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.sns_burned_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (anyEstimated) Text(stringResource(R.string.sns_layout_estimated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        SwitchRow(stringResource(R.string.sns_hide_speed), stringResource(R.string.sns_hide_speed_desc), settings.snsHideSpeed, enabled = speedBurned) { v -> vm.updateSettings { it.copy(snsHideSpeed = v) } }
                        if (!speedBurned) Text(stringResource(R.string.sns_no_speed_burned), style = MaterialTheme.typography.bodySmall)
                    }
                    if ((settings.snsHideSpeed && speedBurned) || laterHideSpeed) {
                        run {
                            Text(stringResource(R.string.sns_threshold, settings.snsThresholdKmh), style = MaterialTheme.typography.bodyMedium)
                            val th = SettingsStore.SNS_THRESHOLDS
                            Slider(
                                value = settings.snsThresholdKmh.toFloat(),
                                onValueChange = { v -> vm.updateSettings { it.copy(snsThresholdKmh = (v / 5f).roundToInt() * 5) } },
                                valueRange = th.first().toFloat()..th.last().toFloat(), steps = th.size - 2,
                            )
                            val hiddenMs = SpeedHide.totalMs(wallSpans)
                            Text(
                                if (wallSpans.isEmpty()) stringResource(R.string.sns_hidden_none, settings.snsThresholdKmh)
                                else stringResource(R.string.sns_hidden_summary, Format.durationJa(hiddenMs), wallSpans.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (points.isEmpty()) Text(stringResource(R.string.sns_no_gps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (anyBurn) {
                        if (latLonBurned) SwitchRow(stringResource(R.string.sns_hide_latlon), null, settings.snsHideLatLon) { v -> vm.updateSettings { it.copy(snsHideLatLon = v) } }
                        if (mapBurned) SwitchRow(stringResource(R.string.sns_hide_map), stringResource(R.string.sns_hide_map_desc), settings.snsHideMap) { v -> vm.updateSettings { it.copy(snsHideMap = v) } }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    when (val st = exportState) {
                        is ExportState.Running -> {
                            Text(stringResource(R.string.sns_exporting, st.percent))
                            LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.sns_exporting_note), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { vm.app.sns.cancel() }) { Text(stringResource(R.string.cancel)) }
                        }
                        is ExportState.Done -> {
                            Text(stringResource(R.string.sns_done, Format.duration(st.durationMs), Format.bytes(st.sizeBytes)), fontWeight = FontWeight.Bold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { withShareWarning { vm.shareFile(context, st.file) } }) { Icon(Icons.Filled.Share, null); Text(" " + stringResource(R.string.share)) }
                                OutlinedButton(onClick = {
                                    if (Build.VERSION.SDK_INT < 29 && !RecorderStatus.granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                        galleryPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else vm.saveSnsToGallery(st.file, wallFrom + start)
                                }) { Icon(Icons.Filled.PhotoLibrary, null); Text(" " + stringResource(R.string.sns_save_gallery)) }
                                OutlinedButton(onClick = {
                                    previewOut = true
                                    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(st.file)))
                                    player.prepare()
                                    player.play()
                                }) { Icon(Icons.Filled.PlayArrow, null); Text(" " + stringResource(R.string.sns_play_output)) }
                                TextButton(onClick = { previewOut = false; vm.app.sns.reset() }) { Text(stringResource(R.string.sns_again)) }
                            }
                        }
                        is ExportState.Failed -> {
                            Text(stringResource(R.string.sns_failed, st.message), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.app.sns.reset() }) { Text(stringResource(R.string.close)) }
                        }
                        else -> {
                            if (st == ExportState.Cancelled) Text(stringResource(R.string.sns_cancelled), style = MaterialTheme.typography.bodySmall)
                            if (preparing) Text(stringResource(R.string.sns_preparing_map), style = MaterialTheme.typography.bodySmall)
                            Button(
                                enabled = !preparing && !plan.tooLong && parts.isNotEmpty() && end - start >= 1_000,
                                onClick = {
                                    player.pause()
                                    val label = when (preset) {
                                        SnsPreset.X -> "x"
                                        SnsPreset.INSTAGRAM_REELS -> "reels"
                                        SnsPreset.YOUTUBE -> "youtube"
                                        SnsPreset.LINE -> "line"
                                        SnsPreset.CUSTOM -> "custom"
                                    }
                                    val its = items.copy(map = items.map && mapAvailable)
                                    val sources = parts.mapIndexed { i, p ->
                                        val c = files.first { it.id == p.clipId }
                                        val later = vm.canOverlayLater(c)
                                        ExportSource(
                                            file = vm.clips.fileOf(c), part = p, layout = if (later) null else layouts[files.indexOf(c)],
                                            speedSpans = if (hideSpeed && !later) timeline.localSpans(i, wallSpans) else emptyList(),
                                            hasAudio = c.hasAudio,
                                            later = if (later) vm.laterSpec(p, its, points, if (laterHideSpeed) timeline.localSpans(i, wallSpans) else emptyList(), settings.hideSpeedDashes) else null,
                                        )
                                    }
                                    val req = ExportRequest(sources, plan, hideSpeed, hideLatLon, hideMap, audio, label)
                                    if (its.map && anyLater) {
                                        // 地図を重ねるときは、走った範囲のタイルをそろえてから
                                        preparing = true
                                        scope.launch {
                                            vm.warmMapTiles(points, wallFrom + start, wallFrom + end, first?.width ?: 1280, first?.height ?: 720)
                                            preparing = false
                                            vm.app.sns.start(req)
                                        }
                                    } else vm.app.sns.start(req)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.sns_export)) }
                        }
                    }
                    Text(stringResource(R.string.sns_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    warnShare?.let { action ->
        AlertDialog(
            onDismissRequest = { warnShare = null },
            title = { Text(stringResource(R.string.sns_warn_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.sns_warn_text))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Text(stringResource(R.string.sns_warn_dont_show))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowAgain) vm.updateSettings { it.copy(snsShareWarnHidden = true) }
                    warnShare = null
                    action()
                }) { Text(stringResource(R.string.sns_warn_ok)) }
            },
            dismissButton = { TextButton(onClick = { warnShare = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** つないだ時間軸の [ms] へ移る。 */
private fun seekGlobal(player: ExoPlayer, lengths: List<Long>, ms: Long) {
    var rest = ms
    for ((i, len) in lengths.withIndex()) {
        if (rest < len || i == lengths.size - 1) {
            player.seekTo(i, rest.coerceIn(0, len))
            return
        }
        rest -= len
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun presetLabel(p: SnsPreset) = stringResource(
    when (p) {
        SnsPreset.X -> R.string.preset_x
        SnsPreset.INSTAGRAM_REELS -> R.string.preset_reels
        SnsPreset.YOUTUBE -> R.string.preset_youtube
        SnsPreset.LINE -> R.string.preset_line
        SnsPreset.CUSTOM -> R.string.preset_custom
    },
)

@Composable
private fun presetNote(p: SnsPreset) = stringResource(
    when (p) {
        SnsPreset.X -> R.string.preset_x_note
        SnsPreset.INSTAGRAM_REELS -> R.string.preset_reels_note
        SnsPreset.YOUTUBE -> R.string.preset_youtube_note
        SnsPreset.LINE -> R.string.preset_line_note
        SnsPreset.CUSTOM -> R.string.preset_custom_note
    },
)
