package jp.kjtr.drivelogger.share

import jp.kjtr.drivelogger.dashcam.BurnLayout
import jp.kjtr.drivelogger.dashcam.LCircle
import jp.kjtr.drivelogger.dashcam.LRect
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.playback.SyncPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SNS 用の書き出しのプリセット（DESIGN.md §5.1）。上限は 2026-10 時点の各サービスの案内の目安（変わることがある）。
 * [maxDurationMs] / [maxBytes] が null なら上限なし。[shortSide] は出力の短い辺（null = 元のまま）。
 */
enum class SnsPreset(
    val maxDurationMs: Long?,
    val maxBytes: Long?,
    val shortSide: Int?,
    /** 縦 9:16 に切り抜くのを選べる。 */
    val canCrop: Boolean,
) {
    X(140_000L, 512L * 1024 * 1024, 1080, false),
    INSTAGRAM_REELS(90_000L, null, 1080, true),
    YOUTUBE(null, null, null, false),
    LINE(300_000L, null, 720, false),
    CUSTOM(null, null, null, true),
}

/** 書き出しの選択（画面で選ぶ）。カスタムのときだけ [customShortSide]（null = 元のまま）・[customBitrateBps] を使う。 */
data class ExportChoice(
    val preset: SnsPreset,
    val cropVertical: Boolean = false,
    val customShortSide: Int? = null,
    val customBitrateBps: Int = 8_000_000,
    val audio: Boolean = true,
)

/** 書き出しの計画（出力の大きさ・ビットレート・見積もり・切り抜き）。 */
data class ExportPlan(
    val outW: Int,
    val outH: Int,
    val videoBps: Int,
    val audioBps: Int,
    val estimatedBytes: Long,
    /** 正立した元の画面での切り抜き（null = 切り抜かない）。 */
    val crop: LRect?,
    /** 長さがプリセットの上限を超えている（書き出せない。範囲を短くする）。 */
    val tooLong: Boolean,
    /** 大きさの上限に収めるため、ビットレート（や解像度）を下げた。 */
    val reducedForSize: Boolean,
)

/**
 * 書き出しの計画（純ロジック。単体テスト: SnsPlanTest）。
 *
 * 1. 出力の短い辺 = プリセット（元より大きくはしない）。縦 9:16 の切り抜きなら元の中央を 9:16 に切ってから縮める。
 * 2. ビットレート = 解像度ごとの既定（YouTube は録画と同じ高画質、LINE は軽め）。元の平均ビットレートより上げない。
 * 3. 見積もり = (映像 + 音声) × 長さ ÷ 8 × 1.02 + 64 KB。大きさの上限を超えそうならビットレートを下げ、
 *    それでも解像度の最低を下回るなら解像度を 1 段ずつ下げる（1080 → 720 → 480）。
 */
object ExportPlanner {
    const val AUDIO_BPS = 128_000
    private const val OVERHEAD = 1.02
    private const val OVERHEAD_BYTES = 64 * 1024L
    private val STEPS = listOf(2160, 1440, 1080, 720, 480, 360)

    /** 解像度（短い辺）ごとの既定のビットレート。 */
    fun defaultBitrate(preset: SnsPreset, shortSide: Int): Int = when (preset) {
        SnsPreset.YOUTUBE -> when {
            shortSide >= 2160 -> 35_000_000
            shortSide >= 1440 -> 20_000_000
            shortSide >= 1080 -> 12_000_000
            shortSide >= 720 -> 6_000_000
            else -> 3_000_000
        }
        SnsPreset.LINE -> when {
            shortSide >= 1080 -> 5_000_000
            shortSide >= 720 -> 3_000_000
            else -> 1_500_000
        }
        else -> when {
            shortSide >= 2160 -> 25_000_000
            shortSide >= 1440 -> 12_000_000
            shortSide >= 1080 -> 8_000_000
            shortSide >= 720 -> 5_000_000
            else -> 2_500_000
        }
    }

    /** これより下げると見られない最低のビットレート（下回るなら解像度を下げる）。 */
    fun minBitrate(shortSide: Int): Int = when {
        shortSide >= 1440 -> 5_000_000
        shortSide >= 1080 -> 2_500_000
        shortSide >= 720 -> 1_200_000
        shortSide >= 480 -> 600_000
        else -> 300_000
    }

    fun estimateBytes(videoBps: Int, audioBps: Int, durationMs: Long): Long =
        ((videoBps.toLong() + audioBps) / 8.0 * (durationMs / 1000.0) * OVERHEAD).toLong() + OVERHEAD_BYTES

    /**
     * [srcW]×[srcH] = 正立した元の大きさ、[srcBitrateBps] = 元の平均ビットレート（分からなければ null）、[hasAudio] = 音声を入れるか。
     */
    fun plan(choice: ExportChoice, srcW: Int, srcH: Int, durationMs: Long, hasAudio: Boolean, srcBitrateBps: Int? = null): ExportPlan {
        val preset = choice.preset
        val crop = if (choice.cropVertical && preset.canCrop) verticalCrop(srcW, srcH) else null
        val baseW = crop?.width?.roundToInt() ?: srcW
        val baseH = crop?.height?.roundToInt() ?: srcH
        val srcShort = min(baseW, baseH).coerceAtLeast(2)
        val wantShort = if (preset == SnsPreset.CUSTOM) choice.customShortSide else preset.shortSide
        var short = min(wantShort ?: srcShort, srcShort)
        val audioBps = if (hasAudio && choice.audio) AUDIO_BPS else 0
        fun capped(bps: Int) = if (srcBitrateBps != null && srcBitrateBps > 0) min(bps, max(srcBitrateBps, minBitrate(short))) else bps
        var video = capped(if (preset == SnsPreset.CUSTOM) choice.customBitrateBps else defaultBitrate(preset, short))
        var reduced = false
        val maxBytes = preset.maxBytes
        if (maxBytes != null && durationMs > 0) {
            val budget = maxBytes * 0.95
            if (estimateBytes(video, audioBps, durationMs) > budget) {
                reduced = true
                // 上限に収まる映像のビットレート（解像度によらない）
                val fit = ((budget - OVERHEAD_BYTES) / OVERHEAD * 8.0 / (durationMs / 1000.0) - audioBps).toInt()
                while (fit < minBitrate(short)) short = STEPS.firstOrNull { it < short } ?: break
                video = min(capped(defaultBitrate(preset, short)), fit).coerceAtLeast(100_000)
            }
        }
        val (ow, oh) = scaledSize(baseW, baseH, short)
        return ExportPlan(
            outW = ow, outH = oh, videoBps = video, audioBps = audioBps,
            estimatedBytes = estimateBytes(video, audioBps, durationMs),
            crop = crop,
            tooLong = preset.maxDurationMs != null && durationMs > preset.maxDurationMs,
            reducedForSize = reduced,
        )
    }

    /** 短い辺を [short] に（縦横比は保つ・偶数に丸める）。 */
    fun scaledSize(w: Int, h: Int, short: Int): Pair<Int, Int> {
        val s = min(w, h).toDouble()
        val k = (short / s).coerceAtMost(1.0)
        return even(w * k) to even(h * k)
    }

    private fun even(v: Double): Int = (v / 2.0).roundToInt().coerceAtLeast(1) * 2

    /** 元の中央を縦 9:16 に切り抜く範囲（元が既に 9:16 より縦長なら上下を切る）。 */
    fun verticalCrop(w: Int, h: Int): LRect {
        val target = 9.0 / 16.0
        val aspect = w.toDouble() / h
        return if (aspect > target) {
            val cw = h * target
            val l = ((w - cw) / 2).toFloat()
            LRect(l, 0f, l + cw.toFloat(), h.toFloat())
        } else {
            val ch = w / target
            val t = ((h - ch) / 2).toFloat()
            LRect(0f, t, w.toFloat(), t + ch.toFloat())
        }
    }
}

/** 時間帯 [startMs, endMs)。 */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
    operator fun contains(t: Long) = t in startMs until endMs
}

/**
 * 「速度を隠す」時間帯の計算（純ロジック。単体テスト: SpeedHideTest）。**実際より低い数字を出すことはしない。隠すだけ。**
 *
 * - GPS の点（時刻の順）の隣り合う 2 点の間を、どちらかの速度がしきい値を超えていれば「超えている」とする
 *   （焼き込みの速度は直前の点の値を出し続けるので、補間した値でなく両端の大きい方で判定する）。
 * - 点の間が空いているところ（GPS が来ていない）も、両端のどちらかが超えていれば隠す（安全側）。
 * - 1 点だけの短い山も隠す（その点の時刻 ± 余裕）。
 * - 前後に [marginMs] の余裕（動画の時刻と GPS の時刻の差・焼き込みの遅れの分）を付け、[mergeGapMs] 以内の隙間はつなぐ（ちらつかないように）。
 */
object SpeedHide {
    fun intervals(
        points: List<SyncPoint>, fromMs: Long, toMs: Long, thresholdKmh: Double,
        marginMs: Long = 1_000, mergeGapMs: Long = 1_000,
    ): List<Span> {
        if (points.isEmpty() || toMs <= fromMs) return emptyList()
        val limit = thresholdKmh / 3.6
        fun over(p: SyncPoint) = (p.speedMps ?: 0.0) > limit
        val raw = ArrayList<Span>()
        for (i in points.indices) {
            val p = points[i]
            if (over(p)) raw.add(Span(p.timeMs, p.timeMs))
            if (i + 1 < points.size) {
                val q = points[i + 1]
                if (q.timeMs < p.timeMs) continue
                // 間（間が空いていても、どちらかが超えていれば隠す）
                if (over(p) || over(q)) raw.add(Span(p.timeMs, q.timeMs))
            }
        }
        val widened = raw.map { Span(it.startMs - marginMs, it.endMs + marginMs) }.sortedBy { it.startMs }
        val merged = ArrayList<Span>()
        for (s in widened) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs + mergeGapMs) {
                merged[merged.size - 1] = Span(last.startMs, max(last.endMs, s.endMs))
            } else merged.add(s)
        }
        return merged.mapNotNull { s ->
            val a = max(s.startMs, fromMs)
            val b = min(s.endMs, toMs)
            if (b > a) Span(a, b) else null
        }
    }

    fun totalMs(spans: List<Span>): Long = spans.sumOf { it.lengthMs }
}

/** 書き出す 1 本分（動画ファイルの [inMs]〜[outMs]）。[wallStartMs] = ファイルの開始時刻（壁時計）。 */
data class ExportPart(val clipId: Long, val wallStartMs: Long, val inMs: Long, val outMs: Long) {
    val lengthMs: Long get() = outMs - inMs
}

/**
 * つなげた動画の時間軸（純ロジック。単体テスト: SnsPlanTest）。出力の時刻 0 = 最初のファイルの [ExportPart.inMs]。
 */
class ExportTimeline(val parts: List<ExportPart>) {
    /** 各ファイルが出力のどこから始まるか [ms]。 */
    val offsets: List<Long> = parts.runningFold(0L) { acc, p -> acc + p.lengthMs }.dropLast(1)
    val durationMs: Long = parts.sumOf { it.lengthMs }

    /** 壁時計の時間帯 → [i] 番目のファイルの出力の時刻（そのファイルの中での 0 起点）。 */
    fun localSpans(i: Int, wall: List<Span>): List<Span> {
        val p = parts[i]
        val a = p.wallStartMs + p.inMs
        val b = p.wallStartMs + p.outMs
        return wall.mapNotNull { s ->
            val s0 = max(s.startMs, a)
            val s1 = min(s.endMs, b)
            if (s1 > s0) Span(s0 - a, s1 - a) else null
        }
    }

    /** 出力の時刻 → 壁時計。 */
    fun wallAt(outMs: Long): Long {
        var i = offsets.indexOfLast { it <= outMs }.coerceAtLeast(0)
        if (i >= parts.size) i = parts.size - 1
        val p = parts[i]
        return p.wallStartMs + p.inMs + (outMs - offsets[i]).coerceIn(0, p.lengthMs)
    }

    companion object {
        /**
         * 時間軸の [startMs]〜[endMs]（ファイル [lengths] を順につないだもの）を、ファイルごとの範囲に分ける。
         * 戻り値は (ファイルの番号, そのファイルの中の開始, 終了)。
         */
        fun split(lengths: List<Long>, startMs: Long, endMs: Long): List<Triple<Int, Long, Long>> {
            val out = ArrayList<Triple<Int, Long, Long>>()
            var base = 0L
            for ((i, len) in lengths.withIndex()) {
                val a = max(startMs, base)
                val b = min(endMs, base + len)
                if (b > a) out.add(Triple(i, a - base, b - base))
                base += len
            }
            return out
        }
    }
}

/**
 * 前後に続けて録ったファイル（ループ録画の続き）を探す（純ロジック。単体テスト: SnsPlanTest）。
 * 前のファイルの終わりから次の開始まで [maxGapMs] 以内・同じ大きさ（向き）・仕上がったものだけ。前後それぞれ [maxEachSide] 本まで。
 */
object ClipChain {
    /**
     * [id] の前後の続きのファイル。ループ録画と長時間録画はつながない。
     * 長時間録画（0.7.0〜）は、同じ動画ログ（1 回の［録画］〜［録画 停止］）の続きのファイルなら [maxEachSide] の制限なしで全部つなぐ
     * （分割して保存した 1 本の録画として再生・書き出しする。[longLimit] = false なら長時間録画も [maxEachSide] まで）。
     */
    fun around(clips: List<ClipEntity>, id: Long, maxGapMs: Long = 5_000, maxEachSide: Int = 3, longLimit: Boolean = false): List<ClipEntity> {
        val sorted = clips.filter { it.status == ClipEntity.STATUS_DONE && it.durationMs > 0 }.sortedBy { it.startedAt }
        val i = sorted.indexOfFirst { it.id == id }
        if (i < 0) return clips.filter { it.id == id }
        val long = sorted[i].isLong
        val limit = if (long && !longLimit) Int.MAX_VALUE else maxEachSide
        fun joins(a: ClipEntity, b: ClipEntity) =
            b.startedAt - (a.startedAt + a.durationMs) in -2_000..maxGapMs && a.width == b.width && a.height == b.height &&
                a.isLong == b.isLong && (!long || a.logId == b.logId)
        var lo = i
        while (lo > 0 && i - lo < limit && joins(sorted[lo - 1], sorted[lo])) lo--
        var hi = i
        while (hi < sorted.size - 1 && hi - i < limit && joins(sorted[hi], sorted[hi + 1])) hi++
        return sorted.subList(lo, hi + 1)
    }

    /** 再生でつなぐファイル（0.7.0〜）: 長時間録画なら同じ録画の続きを全部、ループ録画はそのファイルだけ（今までどおり）。 */
    fun forPlayback(clips: List<ClipEntity>, id: Long): List<ClipEntity> {
        val c = clips.firstOrNull { it.id == id } ?: return emptyList()
        return if (c.isLong && c.status == ClipEntity.STATUS_DONE) around(clips, id) else listOf(c)
    }
}

/** 覆う 1 か所（フレームの px）。 */
sealed interface Cover {
    data class Box(val rect: LRect, val kind: Kind, val alignRight: Boolean) : Cover
    data class Disc(val circle: LCircle) : Cover
    enum class Kind { SPEED, LAT_LON, MAP }
}

/**
 * 覆う場所の計算（純ロジック。単体テスト: CoverPlanTest）。配置 [layout]（録画した正立の大きさの px）を、
 * 書き出しのオーバーレイのフレーム [frameW]×[frameH]（切り抜く前の正立の大きさ）に合わせる。
 * 縦横が入れ替わっている（向きの違う配置を当てた）ときは null（覆わない・使えない）。
 */
object CoverPlan {
    fun covers(layout: BurnLayout, frameW: Int, frameH: Int, hideSpeed: Boolean, hideLatLon: Boolean, hideMap: Boolean): List<Cover>? {
        if (layout.width <= 0 || layout.height <= 0) return null
        if ((layout.width > layout.height) != (frameW > frameH) && layout.width != layout.height) return null
        val sx = frameW.toFloat() / layout.width
        val sy = frameH.toFloat() / layout.height
        val out = ArrayList<Cover>()
        if (hideMap) layout.map?.let { out.add(Cover.Box(it.scale(sx, sy), Cover.Kind.MAP, false)) }
        if (hideLatLon) layout.latLonText?.let { out.add(Cover.Box(it.scale(sx, sy), Cover.Kind.LAT_LON, layout.alignRight)) }
        if (hideSpeed) {
            layout.speedText?.let { out.add(Cover.Box(it.scale(sx, sy), Cover.Kind.SPEED, layout.alignRight)) }
            layout.speedGauge?.let { out.add(Cover.Disc(LCircle(it.cx * sx, it.cy * sy, it.r * min(sx, sy)))) }
        }
        return out
    }

    /** 元の正立の画面の矩形を、切り抜き [crop] と縮小（出力 [outW]×[outH]）の後の座標に（範囲の外なら null）。 */
    fun toOutput(r: LRect, srcW: Int, srcH: Int, crop: LRect?, outW: Int, outH: Int): LRect? {
        val c = crop ?: LRect(0f, 0f, srcW.toFloat(), srcH.toFloat())
        if (!r.intersects(c)) return null
        val kx = outW / c.width
        val ky = outH / c.height
        val l = (max(r.left, c.left) - c.left) * kx
        val t = (max(r.top, c.top) - c.top) * ky
        val rr = (min(r.right, c.right) - c.left) * kx
        val b = (min(r.bottom, c.bottom) - c.top) * ky
        return LRect(l, t, rr, b)
    }

    /** 切り抜きで項目が欠ける（一部だけ外に出る）か。 */
    fun partlyCut(r: LRect, crop: LRect?): Boolean {
        val c = crop ?: return false
        if (!r.intersects(c)) return false
        return r.left < c.left || r.right > c.right || r.top < c.top || r.bottom > c.bottom
    }
}
