package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.PhotoEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.playback.TimelineItem
import jp.kjtr.drivelogger.playback.TimelineKind
import java.util.Locale

/**
 * トリップの出来事のタイムライン（録画した区間・写真・保護・衝撃・停車を時刻順に）。写真は縮小版つき。
 * タップでその場面へ（[onClick]: 動画なら再生、写真なら拡大、停車などは地図をその位置へ）。
 */
@Composable
fun TimelineSection(items: List<TimelineItem>, photos: Map<Long, PhotoEntity>, hasClipAt: (TimelineItem) -> Boolean, onClick: (TimelineItem) -> Unit) {
    if (items.size <= 2) {
        Text(stringResource(R.string.timeline_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    }
    Text(stringResource(R.string.timeline_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    items.forEachIndexed { i, item ->
        TimelineRow(item, photos[item.photoId ?: -1L], first = i == 0, last = i == items.lastIndex, playable = hasClipAt(item), onClick = { onClick(item) })
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, photo: PhotoEntity?, first: Boolean, last: Boolean, playable: Boolean, onClick: () -> Unit) {
    val (icon, color) = iconOf(item)
    val clickable = item.kind != TimelineKind.START && item.kind != TimelineKind.END || item.lat != null
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).clickable(enabled = clickable, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.clock(item.startMs), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp).padding(start = 2.dp),
        )
        // 縦の線と丸（駐車の区間の中の行は字下げ）
        if (item.inPark) Box(Modifier.width(18.dp).fillMaxHeight().padding(start = 12.dp).background(Color(0xFF6A1B9A).copy(alpha = 0.35f)))
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).weight(1f).background(if (first) Color.Transparent else MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else MaterialTheme.colorScheme.outlineVariant))
            }
            Box(Modifier.size(24.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(titleOf(item), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            detailOf(item, playable)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (photo != null) {
            val thumb = rememberThumb(photo.thumbPath)
            Box(Modifier.padding(4.dp).size(width = 72.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFBDBDBD))) {
                if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        } else if (playable && item.kind in setOf(TimelineKind.VIDEO, TimelineKind.IMPACT, TimelineKind.PROTECT, TimelineKind.PHOTO)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.timeline_play), tint = colorResource(R.color.recording), modifier = Modifier.padding(end = 6.dp))
        } else if (clickable) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

@Composable
private fun iconOf(item: TimelineItem): Pair<ImageVector, Color> = when (item.kind) {
    TimelineKind.START -> Icons.Filled.Flag to colorResource(R.color.brand_primary)
    TimelineKind.END -> Icons.Filled.SportsScore to colorResource(R.color.brand_primary)
    TimelineKind.GPS -> Icons.Filled.Route to colorResource(R.color.brand_primary)
    TimelineKind.VIDEO -> Icons.Filled.Videocam to colorResource(R.color.recording)
    TimelineKind.PARK -> Icons.Filled.LocalParking to Color(0xFF6A1B9A)
    TimelineKind.PHOTO -> Icons.Filled.PhotoCamera to Color(0xFF1565C0)
    TimelineKind.PROTECT -> Icons.Filled.Shield to colorResource(R.color.protect)
    TimelineKind.IMPACT -> Icons.Filled.Warning to colorResource(R.color.protect_dark)
    TimelineKind.STOP -> Icons.Filled.PauseCircle to Color(0xFF546E7A)
    TimelineKind.OTHER -> Icons.Filled.Info to Color(0xFF757575)
}

@Composable
private fun titleOf(item: TimelineItem): String = when (item.kind) {
    TimelineKind.START -> stringResource(R.string.timeline_start)
    TimelineKind.END -> stringResource(R.string.timeline_end)
    TimelineKind.GPS -> stringResource(R.string.timeline_gps, item.logNumber)
    TimelineKind.VIDEO -> if (item.logNumber > 0) stringResource(R.string.timeline_video_n, item.logNumber) else stringResource(R.string.timeline_video)
    TimelineKind.PARK -> stringResource(R.string.timeline_park)
    TimelineKind.PHOTO -> stringResource(R.string.timeline_photo)
    TimelineKind.STOP -> stringResource(R.string.timeline_stop)
    else -> eventLabel(TripEventEntity(tripId = 0, type = item.eventType ?: "", startedAt = item.startMs))
}

@Composable
private fun detailOf(item: TimelineItem, playable: Boolean): String? {
    val parts = ArrayList<String>()
    item.endMs?.let { end -> parts.add("– " + Format.clock(end) + "（" + Format.durationJa(end - item.startMs) + "）") }
    if (item.kind == TimelineKind.IMPACT && item.value != null) parts.add(String.format(Locale.ROOT, "%.2f G", item.value))
    if (item.kind == TimelineKind.PARK && item.value != null) parts.add(stringResource(R.string.timeline_walk, Format.distance(item.value)))
    if (item.kind == TimelineKind.GPS && item.value != null) parts.add(Format.km(item.value))
    when (item.kind) {
        TimelineKind.VIDEO, TimelineKind.IMPACT, TimelineKind.PROTECT ->
            parts.add(stringResource(if (playable) R.string.timeline_tap_play else R.string.timeline_no_clip))
        TimelineKind.PHOTO -> parts.add(stringResource(if (item.lat != null) R.string.timeline_tap_photo else R.string.timeline_tap_photo_noloc))
        TimelineKind.STOP, TimelineKind.PARK -> parts.add(stringResource(R.string.timeline_tap_map))
        else -> Unit
    }
    return parts.joinToString("  ").ifBlank { null }
}
