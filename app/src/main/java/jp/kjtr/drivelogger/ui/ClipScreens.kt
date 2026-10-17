package jp.kjtr.drivelogger.ui

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInText
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.playback.TrackSync
import jp.kjtr.drivelogger.record.RecorderStatus
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * 動画の一覧（日付ごと。保護だけ・長時間録画だけの絞り込み）。0.7.0〜 長時間録画の容量の合計と、長時間録画 1 回ごとのまとまり
 * （［再生］＝つないで再生・［ギャラリーへ］・［削除］）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClipListScreen(vm: MainViewModel, onBack: () -> Unit, onPlay: (Long) -> Unit, onSns: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val all by remember { vm.clips.all() }.collectAsState(emptyList())
    val trips by remember { vm.trips.finished() }.collectAsState(emptyList())
    var onlyProtected by rememberSaveable { mutableStateOf(false) }
    var onlyLong by rememberSaveable { mutableStateOf(false) }
    val list = when {
        onlyLong -> all.filter { it.isLong }
        onlyProtected -> all.filter { it.protected && !it.isLong }
        else -> all
    }
    val tripNames = remember(trips) { trips.associate { it.id to it.name } }
    val groups = remember(list) { list.groupBy { Format.dayHeader(it.startedAt) } }
    val total = all.sumOf { it.sizeBytes }
    val prot = all.filter { it.protected && !it.isLong }.sumOf { it.sizeBytes }
    val longGroups = remember(all) { longRecordings(all) }
    var galleryOf by remember { mutableStateOf<List<ClipEntity>?>(null) }
    var deleteOf by remember { mutableStateOf<List<ClipEntity>?>(null) }
    val galleryPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toGallery(files: List<ClipEntity>, overlay: Boolean) {
        if (Build.VERSION.SDK_INT < 29 && !RecorderStatus.granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            galleryPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.exportClipsToGallery(files, overlay)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clips_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(stringResource(R.string.clips_summary, all.size, Format.bytes(total), Format.bytes(prot)), style = MaterialTheme.typography.bodySmall)
                if (longGroups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.clips_long_summary, longGroups.size, longGroups.sumOf { it.size }, Format.bytes(longGroups.sumOf { g -> g.sumOf { it.sizeBytes } })),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = colorResource(R.color.recording),
                    )
                }
                Text(stringResource(R.string.clips_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = onlyProtected, onClick = { onlyProtected = !onlyProtected; onlyLong = false }, label = { Text(stringResource(R.string.clips_only_protected)) })
                    if (longGroups.isNotEmpty() || onlyLong) {
                        FilterChip(selected = onlyLong, onClick = { onlyLong = !onlyLong; onlyProtected = false }, label = { Text(stringResource(R.string.clips_only_long)) })
                    }
                }
                if (list.isEmpty()) Text(stringResource(R.string.clips_empty), Modifier.padding(vertical = 24.dp))
            }
            // 長時間録画だけ: 1 回の録画ごとのまとまり（つないで再生・まとめてギャラリーへ・まとめて削除）
            if (onlyLong) {
                items(longGroups, key = { "g_" + it.first().id }) { g ->
                    LongRecordingCard(
                        g, tripName = g.first().tripId?.let { tripNames[it] },
                        onPlay = { onPlay(g.first().id) }, onGallery = { galleryOf = g }, onDelete = { deleteOf = g },
                    )
                }
                item { Text(stringResource(R.string.clips_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
            }
            groups.forEach { (day, clips) ->
                item(key = "h_$day") { Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
                items(clips, key = { it.id }) { c ->
                    ClipRow(
                        vm, c, tripName = c.tripId?.let { tripNames[it] }, onClick = { onPlay(c.id) },
                        onSns = if (c.status == ClipEntity.STATUS_DONE) ({ onSns(c.id) }) else null,
                    )
                }
            }
        }
    }
    galleryOf?.let { g -> LongGalleryDialog(g, onDismiss = { galleryOf = null }) { overlay -> galleryOf = null; toGallery(g, overlay) } }
    deleteOf?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.long_delete_confirm, g.size, Format.bytes(g.sumOf { it.sizeBytes })), text = null,
            confirm = stringResource(R.string.delete), onConfirm = { vm.deleteClips(g) }, onDismiss = { deleteOf = null },
        )
    }
}

/**
 * 長時間録画 1 回ごとのまとまり（0.7.0〜）。同じ動画ログ（ドライブを消して外れたものは続けて録ったファイル）を開始の順に。新しい録画が先。
 */
fun longRecordings(all: List<ClipEntity>): List<List<ClipEntity>> {
    val sorted = all.filter { it.isLong }.sortedWith(compareBy({ it.startedAt }, { it.id }))
    val out = ArrayList<MutableList<ClipEntity>>()
    for (c in sorted) {
        val g = out.lastOrNull()
        val prev = g?.lastOrNull()
        val same = prev != null && if (c.logId != null || prev.logId != null) c.logId == prev.logId
        else c.startedAt - (prev.startedAt + prev.durationMs) in -2_000..5_000
        if (same) g!!.add(c) else out.add(mutableListOf(c))
    }
    return out.reversed()
}

/** 長時間録画 1 回分のカード（開始〜終了・長さ・ファイル数・大きさ・［再生］［ギャラリーへ］［削除］）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LongRecordingCard(g: List<ClipEntity>, tripName: String?, onPlay: () -> Unit, onGallery: () -> Unit, onDelete: () -> Unit) {
    val first = g.first()
    val last = g.last()
    val end = last.endedAt ?: (last.startedAt + last.durationMs)
    val recording = g.any { it.status == ClipEntity.STATUS_RECORDING }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colorResource(R.color.recording).copy(alpha = 0.10f)).padding(10.dp),
    ) {
        Text(
            stringResource(R.string.long_group_title, Format.dayHeader(first.startedAt), Format.time(first.startedAt), Format.dateTimeShort(first.startedAt, end), Format.duration(g.sumOf { it.durationMs })),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.long_group_detail, g.size, Format.bytes(g.sumOf { it.sizeBytes })), style = MaterialTheme.typography.bodySmall)
        if (tripName != null) Text(tripName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, enabled = first.status != ClipEntity.STATUS_RECORDING) {
                Icon(Icons.Filled.PlayArrow, null); Text(" " + stringResource(R.string.long_group_play))
            }
            OutlinedButton(onClick = onGallery, enabled = !recording) { Icon(Icons.Filled.PhotoLibrary, null); Text(" " + stringResource(R.string.long_group_gallery)) }
            OutlinedButton(onClick = onDelete, enabled = !recording) { Icon(Icons.Filled.Delete, null); Text(" " + stringResource(R.string.long_group_delete)) }
        }
    }
}

/** 長時間録画のギャラリーへの書き出し方（ファイルのまま n 本 / 表示を重ねて 1 本に）。 */
@Composable
private fun LongGalleryDialog(g: List<ClipEntity>, onDismiss: () -> Unit, onChoose: (overlay: Boolean) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.long_gallery_title, g.size, Format.bytes(g.sumOf { it.sizeBytes }))) },
        text = { Text(stringResource(R.string.long_gallery_text)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.material3.TextButton(onClick = { onChoose(false) }) { Text(stringResource(R.string.long_gallery_files, g.size)) }
                androidx.compose.material3.TextButton(onClick = { onChoose(true) }) { Text(stringResource(R.string.long_gallery_overlay)) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** 動画 1 本の行（サムネイル・開始時刻・長さ・大きさ・保護の印）。 */
@Composable
fun ClipRow(vm: MainViewModel, c: ClipEntity, tripName: String? = null, onClick: () -> Unit, onSns: (() -> Unit)? = null) {
    val thumb by produceState<Bitmap?>(null, c.id, c.status) { value = vm.thumbnail(c) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = c.status != ClipEntity.STATUS_RECORDING, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (c.protected) {
                Icon(Icons.Filled.Shield, contentDescription = stringResource(R.string.clip_protected), tint = colorResource(R.color.protect), modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp))
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                Format.clock(c.startedAt) + "  " + if (c.status == ClipEntity.STATUS_RECORDING) stringResource(R.string.clip_recording) else Format.duration(c.durationMs),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(
                    // 録画の方式（0.7.0〜）
                    stringResource(if (c.isLong) R.string.record_mode_long_short else R.string.record_mode_loop_short),
                    Format.bytes(c.sizeBytes).takeIf { c.sizeBytes > 0 },
                    if (c.height > 0) "${minOf(c.width, c.height)}p" else null,
                    if (c.protected) stringResource(if (c.protectReason == ClipEntity.REASON_IMPACT) R.string.clip_protected_impact else R.string.clip_protected) else null,
                    if (c.status == ClipEntity.STATUS_FAILED) stringResource(R.string.clip_failed) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (tripName != null) Text(tripName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onSns != null) {
            IconButton(onClick = onSns) { Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.sns_open)) }
        }
    }
}

/**
 * 再生画面（DESIGN.md §5）。動画の横（縦画面は下）に、再生位置の時刻に合わせた地図の位置・速度・方位。
 * ［ギャラリーに書き出す］［共有］［保護する / 外す］［削除］。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, clipId: Long, startAtMs: Long = 0L, onBack: () -> Unit, onSns: (Long) -> Unit = {}) {
    val context = LocalContext.current
    // 長時間録画（0.7.0〜）は同じ録画の続きのファイルをつないで再生する（プレイリスト）。ループ録画はそのファイルだけ
    var chain by remember(clipId) { mutableStateOf<List<ClipEntity>?>(null) }
    LaunchedEffect(clipId) { chain = jp.kjtr.drivelogger.share.ClipChain.forPlayback(vm.clips.allNow(), clipId) }
    val parts = chain
    var index by remember(clipId) { mutableIntStateOf(-1) }
    val startIndex = parts?.indexOfFirst { it.id == clipId }?.coerceAtLeast(0) ?: 0
    val currentId = parts?.getOrNull(if (index >= 0) index else startIndex)?.id ?: clipId
    // ファイルが替わった直後も画面を出し直さないよう、つないだ一覧の行を初めの値に
    val partNow = parts?.firstOrNull { it.id == currentId }
    val clip by remember(currentId) { vm.clips.clip(currentId) }.collectAsState(partNow)
    val settings by vm.settings.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val c = clip
    val points by produceState(emptyList<SyncPoint>(), parts) {
        val p = parts
        value = when {
            p.isNullOrEmpty() -> emptyList()
            p.size == 1 -> vm.syncPoints(p.first())
            else -> vm.syncPointsBetween(p.first().startedAt - 15_000, p.last().let { it.startedAt + it.durationMs } + 15_000)
        }
    }
    val sync = remember(points) { TrackSync(points) }
    val line = remember(points) { listOf(points.map { LatLon(it.lat, it.lon) }) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var deleting by remember { mutableStateOf(false) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(parts) {
        val p = parts ?: return@LaunchedEffect
        if (p.isEmpty()) return@LaunchedEffect
        // タイムラインの出来事から開いたときは、その場面から
        player.setMediaItems(p.map { MediaItem.fromUri(android.net.Uri.fromFile(vm.clips.fileOf(it))) }, startIndex, startAtMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            if (player.mediaItemCount > 0) index = player.currentMediaItemIndex
            delay(200)
        }
    }
    // あとから重ねる表示（焼き込みの無い動画。元の映像には入っていない）
    val canOverlay = c != null && vm.canOverlayLater(c) && settings.burnIn
    var showOverlay by rememberSaveable { mutableStateOf(true) }
    var galleryAsk by remember { mutableStateOf(false) }
    var pendingGalleryOverlay by remember { mutableStateOf(true) }
    // 長時間録画をつないで再生している（ファイルが 2 本以上）
    val joined = parts != null && parts.size > 1
    var longGalleryAsk by remember { mutableStateOf(false) }
    val galleryPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) c?.let { vm.exportClipsToGallery(if (joined) parts.orEmpty() else listOf(it), pendingGalleryOverlay) }
    }
    fun toGallery(overlay: Boolean) {
        val cc = c ?: return
        pendingGalleryOverlay = overlay
        if (Build.VERSION.SDK_INT < 29 && !RecorderStatus.granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            galleryPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.exportClipsToGallery(if (joined) parts.orEmpty() else listOf(cc), overlay)
    }

    if (c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (parts != null) Text(stringResource(R.string.clip_missing)) }
        return
    }
    val wall = TrackSync.wallTime((partNow ?: c).startedAt, positionMs)
    val fix = sync.at(wall)

    @Composable
    fun SyncPane(modifier: Modifier) {
        Column(modifier) {
            DriveMap(
                lat = fix?.lat, lon = fix?.lon, headingDeg = fix?.bearingDeg, track = line, mapKind = settings.mapKind, full = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(BurnInText.dateTime(wall), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fix?.speedMps?.let { "%d".format((it * 3.6).toInt()) } ?: "--", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(" km/h  " + BurnInText.heading(fix?.bearingDeg), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(if (fix == null) stringResource(R.string.player_no_position) else BurnInText.latLon(fix.lat, fix.lon), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    fun Actions() {
        // つないで再生している長時間録画: いまのファイルの番号と全体の長さ
        if (joined) {
            Text(
                stringResource(R.string.player_part, (if (index >= 0) index else startIndex) + 1, parts.orEmpty().size, Format.duration(parts.orEmpty().sumOf { it.durationMs })) +
                    " · " + stringResource(R.string.record_mode_long),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            if (c.status == ClipEntity.STATUS_DONE) {
                Button(onClick = { player.pause(); onSns(c.id) }) { Icon(Icons.Filled.Upload, null); Text(" " + stringResource(R.string.sns_open)) }
            }
            OutlinedButton(onClick = { if (joined) longGalleryAsk = true else if (canOverlay) galleryAsk = true else toGallery(false) }) {
                Icon(Icons.Filled.PhotoLibrary, null); Text(" " + stringResource(R.string.clip_to_gallery))
            }
            if (canOverlay) {
                FilterChip(selected = showOverlay, onClick = { showOverlay = !showOverlay }, label = { Text(stringResource(R.string.player_show_overlay)) })
            }
            OutlinedButton(onClick = { vm.shareClip(context, c) }) { Icon(Icons.Filled.Share, null); Text(" " + stringResource(R.string.share)) }
            // 長時間録画は自動では消さないので保護は無い
            if (!c.isLong) {
                OutlinedButton(onClick = { vm.setClipProtected(c, !c.protected) }) {
                    Icon(Icons.Filled.Shield, null); Text(" " + stringResource(if (c.protected) R.string.clip_unprotect else R.string.clip_protect))
                }
            }
            OutlinedButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, null); Text(" " + stringResource(R.string.delete)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Format.dayHeader(c.startedAt) + " " + Format.clock(c.startedAt), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val fullW = maxWidth
            val fullH = maxHeight
            val overlay: (@Composable () -> Unit)? = if (canOverlay && showOverlay) {
                { PlaybackOverlay(vm, c, wall, fix, points) }
            } else null
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.6f).fillMaxHeight()) {
                        VideoPane(player, Modifier.fillMaxWidth().weight(1f), overlay)
                        Actions()
                    }
                    SyncPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    VideoPane(player, Modifier.fillMaxWidth().height(fullW * 9 / 16), overlay)
                    Actions()
                    SyncPane(Modifier.fillMaxWidth().height(fullH * 0.55f))
                }
            }
        }
    }
    if (galleryAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { galleryAsk = false },
            title = { Text(stringResource(R.string.gallery_ask_title)) },
            text = { Text(stringResource(R.string.gallery_ask_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.TextButton(onClick = { galleryAsk = false; toGallery(true) }) { Text(stringResource(R.string.gallery_with_overlay)) }
                    androidx.compose.material3.TextButton(onClick = { galleryAsk = false; toGallery(false) }) { Text(stringResource(R.string.gallery_plain)) }
                    androidx.compose.material3.TextButton(onClick = { galleryAsk = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    if (longGalleryAsk) {
        LongGalleryDialog(parts.orEmpty(), onDismiss = { longGalleryAsk = false }) { overlay -> longGalleryAsk = false; toGallery(overlay) }
    }
    if (deleting && joined) {
        // 長時間録画: このファイルだけ / 録画を全部
        val all = parts.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.long_delete_confirm, all.size, Format.bytes(all.sumOf { it.sizeBytes }))) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.TextButton(onClick = { deleting = false; player.stop(); vm.deleteClips(all); onBack() }) {
                        Text(stringResource(R.string.delete_long_all, all.size))
                    }
                    androidx.compose.material3.TextButton(onClick = { deleting = false; player.stop(); vm.deleteClip(c); onBack() }) {
                        Text(stringResource(R.string.delete_this_file))
                    }
                    androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    } else if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.clip_delete_confirm), text = if (c.protected) stringResource(R.string.clip_delete_protected_note) else null,
            confirm = stringResource(R.string.delete), onConfirm = { vm.deleteClip(c); onBack() }, onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun VideoPane(player: ExoPlayer, modifier: Modifier, overlay: (@Composable () -> Unit)? = null) {
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
        overlay?.invoke()
    }
}

/**
 * 再生の上に、あとから重ねる表示を描く（書き出しと同じ [jp.kjtr.drivelogger.dashcam.BurnInRenderer]。元の映像には入っていない）。
 * 動画が画面に収まる矩形（縦横比を保って中央）に、動画の大きさで描いて縮める。
 */
@Composable
private fun PlaybackOverlay(vm: MainViewModel, c: ClipEntity, wall: Long, fix: jp.kjtr.drivelogger.playback.SyncFix?, points: List<SyncPoint>) {
    val settings by vm.settings.collectAsState()
    val items = remember(settings) { vm.defaultOverlayItems(settings) }
    val config = remember(settings, items) {
        jp.kjtr.drivelogger.share.LaterOverlayPlan.configFor(jp.kjtr.drivelogger.service.DriveController.burnConfig(settings), items)
    }
    val renderer = remember(config.map != null) { jp.kjtr.drivelogger.dashcam.BurnInRenderer(jp.kjtr.drivelogger.dashcam.DashcamEngine.APP_NAME, if (config.map != null) vm.app.tiles else null) }
    val track = remember(points) { jp.kjtr.drivelogger.share.LaterOverlayPlan.track(points) }
    val vw = c.width.coerceAtLeast(2)
    val vh = c.height.coerceAtLeast(2)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val scale = minOf(size.width / vw, size.height / vh)
        val left = (size.width - vw * scale) / 2
        val top = (size.height - vh * scale) / 2
        val values = jp.kjtr.drivelogger.dashcam.BurnInValues(fix?.speedMps?.let { it * 3.6 }, fix?.lat, fix?.lon, fix?.bearingDeg)
        drawIntoCanvas { cv ->
            val n = cv.nativeCanvas
            n.save()
            n.translate(left, top)
            n.scale(scale, scale)
            renderer.drawUpright(n, vw, vh, wall, values, config, track)
            n.restore()
        }
    }
}
