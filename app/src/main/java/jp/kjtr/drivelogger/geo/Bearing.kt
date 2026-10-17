package jp.kjtr.drivelogger.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 方位角（自転車ルートロガーの Bearing から、使う分だけ）。 */
object Bearing {
    private const val DEG = PI / 180.0

    /** a から b への初期方位角（度、北 = 0、時計回り、[0, 360)）。 */
    fun initialDeg(a: LatLon, b: LatLon): Double {
        val phi1 = a.lat * DEG
        val phi2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalize(atan2(y, x) / DEG)
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** -180..180 に寄せた差（a − b）。 */
    fun diff(a: Double, b: Double): Double {
        var d = normalize(a - b)
        if (d > 180.0) d -= 360.0
        return d
    }

    /** 角度の線形補間（短い方を回る）。 */
    fun lerp(a: Double, b: Double, t: Double): Double = normalize(a + diff(b, a) * t)
}
