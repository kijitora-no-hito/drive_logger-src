package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.stats.DriveAnalysis
import jp.kjtr.drivelogger.stats.Split
import jp.kjtr.drivelogger.stats.SpeedHistogram
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 区間（スプリット）の表。5 km ごと / 10 km ごとを切り替える。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitsSection(analysis: DriveAnalysis?, modifier: Modifier = Modifier) {
    var unitKm by rememberSaveable { mutableStateOf(5) }
    val splits = remember(analysis, unitKm) { analysis?.splits(unitKm * 1000.0).orEmpty() }
    val extremes = remember(splits) { DriveAnalysis.extremes(splits) }
    val fastColor = colorResource(R.color.split_fast)
    val slowColor = colorResource(R.color.split_slow)
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(5, 10).forEachIndexed { i, u ->
                SegmentedButton(
                    selected = unitKm == u,
                    onClick = { unitKm = u },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                ) { Text(stringResource(if (u == 5) R.string.splits_5km else R.string.splits_10km)) }
            }
        }
        when {
            analysis == null -> Text(stringResource(R.string.analysis_loading), Modifier.padding(vertical = 12.dp))
            splits.isEmpty() -> Text(stringResource(R.string.splits_empty), Modifier.padding(vertical = 12.dp))
            else -> {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                    HeaderCell(stringResource(R.string.split_col_range), 1.35f)
                    HeaderCell(stringResource(R.string.split_col_time), 1.1f, end = true)
                    HeaderCell(stringResource(R.string.split_col_avg), 0.95f, end = true)
                    HeaderCell(stringResource(R.string.split_col_max), 0.95f, end = true)
                }
                HorizontalDivider()
                splits.forEach { s ->
                    val bg = when (s.index) {
                        extremes?.first -> fastColor
                        extremes?.second -> slowColor
                        else -> Color.Transparent
                    }
                    SplitRow(s, Modifier.background(bg))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                if (extremes != null) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(fastColor))
                        Text(" " + stringResource(R.string.split_fastest), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.padding(start = 12.dp).size(12.dp).background(slowColor))
                        Text(" " + stringResource(R.string.split_slowest), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    stringResource(R.string.splits_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float, end: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else null,
        modifier = Modifier.weight(weight),
        maxLines = 1,
    )
}

@Composable
private fun SplitRow(s: Split, modifier: Modifier) {
    val partialLabel = stringResource(R.string.split_partial)
    val range = Format.kmEdge(s.startM) + "–" + Format.kmEdge(s.endM)
    val style = MaterialTheme.typography.bodyMedium.let { if (s.partial) it.copy(fontStyle = FontStyle.Italic) else it }
    val end = androidx.compose.ui.text.style.TextAlign.End
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.35f)) {
            Text(range, style = style, maxLines = 1)
            if (s.partial) Text(partialLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Format.duration(s.movingMs), style = style, textAlign = end, modifier = Modifier.weight(1.1f), maxLines = 1)
        Text(Format.speed(s.avgSpeedMps), style = style.copy(fontWeight = FontWeight.Bold), textAlign = end, modifier = Modifier.weight(0.95f), maxLines = 1)
        Text(Format.speed(s.maxSpeedMps), style = style, textAlign = end, modifier = Modifier.weight(0.95f), maxLines = 1)
    }
}

/** 時速の分布（10 km/h ごとの棒グラフ）。縦軸は時間（分）か距離（km）。棒をタップで値。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedHistogramSection(analysis: DriveAnalysis?, modifier: Modifier = Modifier) {
    var byDistance by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(analysis) { mutableStateOf<Int?>(null) }
    val hist = remember(analysis) { analysis?.histogram(10.0) }
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !byDistance, onClick = { byDistance = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.hist_by_time))
            }
            SegmentedButton(selected = byDistance, onClick = { byDistance = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.hist_by_distance))
            }
        }
        when {
            hist == null -> Text(stringResource(R.string.analysis_loading), Modifier.padding(vertical = 12.dp))
            hist.totalMs <= 0 || hist.bins.isEmpty() -> Text(stringResource(R.string.hist_empty), Modifier.padding(vertical = 12.dp))
            else -> {
                Text(
                    stringResource(R.string.hist_avg, String.format(Locale.ROOT, "%.1f", hist.avgKmh)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                SpeedHistogramChart(
                    hist, byDistance, selected,
                    onSelect = { selected = if (selected == it) null else it },
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 6.dp),
                )
                val sel = selected?.takeIf { it in hist.bins.indices }
                val text = if (sel == null) {
                    stringResource(R.string.hist_tap_hint)
                } else {
                    val b = hist.bins[sel]
                    val from = b.fromKmh.roundToInt().toString()
                    val to = b.toKmh.roundToInt().toString()
                    if (byDistance) {
                        val pct = if (hist.totalM > 0) (b.distanceM / hist.totalM * 100).roundToInt() else 0
                        stringResource(R.string.hist_value_distance, from, to, Format.km(b.distanceM), pct)
                    } else {
                        val pct = (b.timeMs.toDouble() / hist.totalMs * 100).roundToInt()
                        stringResource(R.string.hist_value_time, from, to, Format.durationJa(b.timeMs), pct)
                    }
                }
                Text(
                    text,
                    style = if (sel == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(
                    stringResource(if (byDistance) R.string.hist_note_distance else R.string.hist_note_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedHistogramChart(
    hist: SpeedHistogram,
    byDistance: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val tm = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val selColor = colorResource(R.color.brand_accent)
    val avgColor = colorResource(R.color.track_line)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val avgStyle = TextStyle(fontSize = 11.sp, color = avgColor, fontWeight = FontWeight.Bold)
    val yUnit = stringResource(if (byDistance) R.string.hist_axis_km else R.string.hist_axis_min)
    val xUnit = stringResource(R.string.hist_axis_kmh)
    val avgLabel = stringResource(R.string.hist_avg_short)
    val desc = stringResource(R.string.hist_title)
    // 縦軸の値（分 / km）
    val values = hist.bins.map { if (byDistance) it.distanceM / 1000.0 else it.timeMs / 60_000.0 }
    val maxV = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
    val step = DriveAnalysis.niceStep(maxV)
    val top = ceil(maxV / step - 1e-9) * step
    val n = hist.bins.size

    // 余白（左に縦軸の数字、下に横軸の数字）
    val leftDp = 34.dp
    val bottomDp = 20.dp
    val topDp = 24.dp
    Canvas(
        modifier
            .semantics { contentDescription = desc }
            .pointerInput(hist, byDistance) {
                detectTapGestures { o ->
                    val left = leftDp.toPx()
                    val w = size.width - left
                    if (o.x >= left && w > 0) {
                        val i = ((o.x - left) / (w / n)).toInt()
                        if (i in 0 until n) onSelect(i)
                    }
                }
            },
    ) {
        val left = leftDp.toPx()
        val bottom = size.height - bottomDp.toPx()
        val topY = topDp.toPx()
        val plotW = size.width - left
        val plotH = bottom - topY
        val barW = plotW / n
        fun y(v: Double) = (bottom - v / top * plotH).toFloat()

        // 横の目盛り線と縦軸の数字
        var v = 0.0
        while (v <= top + 1e-9) {
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
            val label = if (step >= 1) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)
            val r = tm.measure(label, labelStyle)
            drawText(r, topLeft = Offset(left - r.size.width - 4.dp.toPx(), yy - r.size.height / 2f))
            v += step
        }
        // 縦軸の単位
        val ur = tm.measure(yUnit, labelStyle)
        drawText(ur, topLeft = Offset(0f, 0f))

        // 棒
        val gap = (barW * 0.12f).coerceAtMost(3.dp.toPx())
        values.forEachIndexed { i, value ->
            if (value <= 0) return@forEachIndexed
            val x0 = left + i * barW + gap / 2
            val yy = y(value)
            drawRect(
                color = if (selected == i) selColor else if (selected == null) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(x0, yy),
                size = Size((barW - gap).coerceAtLeast(1f), bottom - yy),
            )
        }
        drawLine(grid, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.5f)

        // 横軸の数字（重ならない間隔で）
        val minLabelPx = 26.dp.toPx()
        val every = listOf(1, 2, 5, 10, 20).firstOrNull { barW * it >= minLabelPx } ?: 25
        for (i in 0..n) {
            // 0 は縦軸の 0 と重なるので出さない
            if (i == 0 || (i % every != 0 && i != n)) continue
            if (i == n && n % every != 0 && barW * (n % every) < minLabelPx * 0.8f) continue
            val label = (i * hist.binKmh).roundToInt().toString()
            val r = tm.measure(label, labelStyle)
            val x = (left + i * barW - r.size.width / 2f).coerceIn(left - r.size.width / 2f, size.width - r.size.width.toFloat())
            drawText(r, topLeft = Offset(x, bottom + 2.dp.toPx()))
        }
        // 横軸の単位（右上）
        val xr = tm.measure(xUnit, labelStyle)
        drawText(xr, topLeft = Offset(size.width - xr.size.width, 0f))

        // 平均速度の線
        val ax = (left + hist.avgKmh / hist.binKmh * barW).toFloat().coerceIn(left, size.width)
        drawLine(avgColor, Offset(ax, topY), Offset(ax, bottom), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        val ar = tm.measure(avgLabel, avgStyle)
        val axText = (ax - ar.size.width / 2f).coerceIn(left + ur.size.width.toFloat(), (size.width - ar.size.width - xr.size.width - 4.dp.toPx()).coerceAtLeast(left))
        drawText(ar, topLeft = Offset(axText, 0f))
    }
}
