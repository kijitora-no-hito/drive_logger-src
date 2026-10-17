package jp.kjtr.drivelogger.playback

import jp.kjtr.drivelogger.geo.Bearing
import jp.kjtr.drivelogger.geo.Geo

/** 同期に使う点（時刻の順に並べる）。 */
data class SyncPoint(val timeMs: Long, val lat: Double, val lon: Double, val speedMps: Double?, val bearingDeg: Double?)

/** ある時刻の位置（補間したもの）。 */
data class SyncFix(val timeMs: Long, val lat: Double, val lon: Double, val speedMps: Double?, val bearingDeg: Double?, val index: Int)

/**
 * 動画の再生位置と GPS 記録の同期（純ロジック。単体テスト: TrackSyncTest。DESIGN.md §5）。
 *
 * 再生位置の時刻 = 動画の開始時刻 + 再生位置。その前後の点を時刻で線形補間する（方位は短い方を回る）。
 * 前後の点が [maxGapMs]（既定 10 秒）以上離れている・範囲の外（[edgeMs] 超）なら null（「位置なし」）。
 * 速度は点の速度（無ければ 2 点間の距離÷時間）。
 */
class TrackSync(private val points: List<SyncPoint>, private val maxGapMs: Long = 10_000, private val edgeMs: Long = 3_000) {
    init {
        require(points.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }) { "points must be sorted by time" }
    }

    fun at(t: Long): SyncFix? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (t <= first.timeMs) return if (first.timeMs - t <= edgeMs) fix(first, 0, t) else null
        if (t >= last.timeMs) return if (t - last.timeMs <= edgeMs) fix(last, points.size - 1, t) else null
        // t を挟む i（points[i].time <= t < points[i+1].time）
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timeMs <= t) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = b.timeMs - a.timeMs
        if (span > maxGapMs) {
            // 近い方の点が十分近ければその点
            return when {
                t - a.timeMs <= edgeMs -> fix(a, lo, t)
                b.timeMs - t <= edgeMs -> fix(b, hi, t)
                else -> null
            }
        }
        if (span <= 0) return fix(a, lo, t)
        val f = (t - a.timeMs).toDouble() / span
        val lat = a.lat + (b.lat - a.lat) * f
        val lon = a.lon + (b.lon - a.lon) * f
        val segSpeed = Geo.distanceM(a.lat, a.lon, b.lat, b.lon) / (span / 1000.0)
        val sa = a.speedMps ?: segSpeed
        val sb = b.speedMps ?: segSpeed
        val speed = sa + (sb - sa) * f
        val bearing = when {
            a.bearingDeg != null && b.bearingDeg != null -> Bearing.lerp(a.bearingDeg, b.bearingDeg, f)
            else -> a.bearingDeg ?: b.bearingDeg
        }
        return SyncFix(t, lat, lon, speed, bearing, if (f < 0.5) lo else hi)
    }

    private fun fix(p: SyncPoint, i: Int, t: Long) = SyncFix(t, p.lat, p.lon, p.speedMps, p.bearingDeg, i)

    companion object {
        /** 動画（開始 [startMs]・長さ [durationMs]）の再生位置 [positionMs] の時刻。 */
        fun wallTime(startMs: Long, positionMs: Long): Long = startMs + positionMs.coerceAtLeast(0)
    }
}
