package jp.kjtr.drivelogger.geo

import kotlin.math.max

/** 地図に描く線の間引き。描画が重くならないよう、形を保ったまま点を減らす。 */
object Simplify {

    /**
     * Douglas–Peucker（許容誤差 [toleranceM] m）。始点と終点は必ず残す。
     * 再帰は使わずスタックで回す（10 万点でも溢れない）。
     */
    fun douglasPeucker(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size <= 2) return points
        val keep = douglasPeuckerKeep(points, toleranceM)
        val out = ArrayList<LatLon>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    /** [douglasPeucker] の残す点の印（元の点に付いた標高・時刻などを保ったまま間引くとき用）。 */
    fun douglasPeuckerKeep(points: List<LatLon>, toleranceM: Double): BooleanArray {
        if (points.size <= 2) return BooleanArray(points.size) { true }
        val lat0 = points[points.size / 2].lat
        val kx = Geo.metersPerDegLon(lat0)
        val ky = Geo.METERS_PER_DEG_LAT
        val xs = DoubleArray(points.size) { points[it].lon * kx }
        val ys = DoubleArray(points.size) { points[it].lat * ky }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val tol2 = toleranceM * toleranceM
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            val dx = xs[e] - xs[s]
            val dy = ys[e] - ys[s]
            val len2 = dx * dx + dy * dy
            var maxD2 = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val px = xs[i] - xs[s]
                val py = ys[i] - ys[s]
                val d2 = if (len2 <= 0.0) {
                    px * px + py * py
                } else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    val fx = px - t * dx
                    val fy = py - t * dy
                    fx * fx + fy * fy
                }
                if (d2 > maxD2) {
                    maxD2 = d2; idx = i
                }
            }
            if (maxD2 > tol2 && idx > 0) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return keep
    }

    /**
     * 地図表示用。まず [toleranceM] で Douglas–Peucker、それでも [maxPoints] を超えるなら等間隔に抜く
     * （始点・終点は残す）。
     */
    fun forDisplay(points: List<LatLon>, maxPoints: Int = 4000, toleranceM: Double = 3.0): List<LatLon> {
        val dp = douglasPeucker(points, toleranceM)
        return stride(dp, maxPoints)
    }

    fun <T> stride(points: List<T>, maxPoints: Int): List<T> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val step = max(1, (points.size + maxPoints - 1) / maxPoints)
        val out = ArrayList<T>(maxPoints + 1)
        var i = 0
        while (i < points.size) {
            out.add(points[i])
            i += step
        }
        if (out.last() !== points.last()) out.add(points.last())
        return out
    }
}
