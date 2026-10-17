package jp.kjtr.drivelogger.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.DriveLogEntity
import jp.kjtr.drivelogger.data.TripEntity

/** ログの番号（種類ごとに開始の順で 1, 2, …）。 */
fun logNumbers(logs: List<DriveLogEntity>): Map<Long, Int> {
    val out = HashMap<Long, Int>()
    for (kind in listOf(DriveLogEntity.KIND_GPS, DriveLogEntity.KIND_VIDEO)) {
        logs.filter { it.kind == kind }.sortedWith(compareBy({ it.startedAt }, { it.id })).forEachIndexed { i, l -> out[l.id] = i + 1 }
    }
    return out
}

/**
 * ドライブの GPS ログ・動画ログの一覧（0.6.0〜。ドライブの詳細の概要）。時刻・長さ・距離（GPS）・動画の本数（動画）。
 * 各行のメニューで GPX の書き出し・共有（GPS）、再生（動画）、削除。記録中のログは消せない。
 */
@Composable
fun LogList(
    logs: List<DriveLogEntity>,
    clips: List<ClipEntity>,
    now: Long,
    onExportGpx: (DriveLogEntity, Int) -> Unit,
    onShareGpx: (DriveLogEntity, Int) -> Unit,
    onPlay: (ClipEntity) -> Unit,
    onDelete: (DriveLogEntity, Int) -> Unit,
) {
    val numbers = remember(logs) { logNumbers(logs) }
    val gpsCount = logs.count { it.isGps }
    val videoCount = logs.count { it.isVideo }
    Text(
        stringResource(R.string.logs_title, gpsCount, videoCount), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    if (logs.isEmpty()) {
        Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    for (l in logs.sortedWith(compareBy({ it.startedAt }, { it.kind }, { it.id }))) {
        val n = numbers[l.id] ?: 0
        val mine = if (l.isVideo) clips.filter { it.logId == l.id } else emptyList()
        LogRow(
            l, n, mine, now,
            onExportGpx = { onExportGpx(l, n) }, onShareGpx = { onShareGpx(l, n) },
            onPlay = { mine.firstOrNull { it.status != ClipEntity.STATUS_RECORDING || it.durationMs > 0 }?.let(onPlay) },
            onDelete = { onDelete(l, n) },
        )
    }
}

@Composable
private fun LogRow(
    l: DriveLogEntity, n: Int, clips: List<ClipEntity>, now: Long,
    onExportGpx: () -> Unit, onShareGpx: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val open = l.endedAt == null
    val end = l.endedAt ?: now
    val color = if (l.isGps) colorResource(R.color.brand_primary) else colorResource(R.color.recording)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = l.isVideo && clips.isNotEmpty()) { onPlay() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(if (l.isGps) Icons.Filled.Route else Icons.Filled.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 動画ログは録画の方式も（0.7.0〜「録画 2（長時間）」）
                    if (l.isGps) stringResource(R.string.log_gps_n, n)
                    else stringResource(R.string.log_video_n_mode, n, stringResource(if (l.isLong) R.string.record_mode_long_short else R.string.record_mode_loop_short)),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    "  " + Format.clock(l.startedAt) + " – " + (if (open) stringResource(R.string.log_recording) else Format.clock(end)) +
                        "（" + Format.durationJa(end - l.startedAt) + "）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (l.isGps) {
                    if (open) stringResource(R.string.log_gps_open)
                    else stringResource(R.string.log_gps_detail, Format.km(l.distanceM), Format.duration(l.movingMs), Format.speed(if (l.movingMs > 0) l.distanceM / (l.movingMs / 1000.0) else 0.0))
                } else {
                    if (clips.isEmpty()) stringResource(R.string.log_video_gone) else stringResource(R.string.log_video_detail, clips.size, Format.bytes(clips.sumOf { it.sizeBytes }))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (l.isVideo && clips.isNotEmpty()) Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.timeline_play), tint = colorResource(R.color.recording))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (l.isGps && !open) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.log_export_gpx)) }, onClick = { menu = false; onExportGpx() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.log_share_gpx)) }, onClick = { menu = false; onShareGpx() })
                }
                if (l.isVideo && clips.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.log_play)) }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (open) R.string.log_cannot_delete_open else R.string.log_delete)) },
                    enabled = !open, onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/**
 * ［このドライブを再開］（閉じたドライブを開き直して記録を追加する。0.6.0〜）。ほかのドライブが開いていれば、それを終了してから再開するか確かめる。
 * [guard] が true（走行中）なら知らせるだけ。
 */
@Composable
fun ReopenButton(vm: MainViewModel, t: TripEntity, guard: () -> Boolean = { false }, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drive by vm.session.state.collectAsStateWithLifecycle()
    var otherName by remember { mutableStateOf<String?>(null) }
    if (t.isOpen) return
    TextButton(
        onClick = {
            if (guard()) Toast.makeText(context, R.string.restricted_while_moving, Toast.LENGTH_SHORT).show()
            else vm.reopenTrip(t.id, closeOther = false) { otherName = drive?.name ?: "" }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(" " + stringResource(R.string.reopen_drive))
    }
    otherName?.let { other ->
        ConfirmDialog(
            title = stringResource(R.string.reopen_confirm_title),
            text = stringResource(R.string.reopen_confirm_text, other, t.name),
            confirm = stringResource(R.string.reopen_confirm_ok),
            onConfirm = { vm.reopenTrip(t.id, closeOther = true) {} },
            onDismiss = { otherName = null },
        )
    }
}
