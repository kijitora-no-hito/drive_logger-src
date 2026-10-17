package jp.kjtr.drivelogger.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 緯度経度（度）。 */
data class LatLon(val lat: Double, val lon: Double)

/** 緯度経度の範囲。 */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    companion object {
        fun of(points: Iterable<LatLon>): GeoBounds? {
            var s = Double.POSITIVE_INFINITY
            var w = Double.POSITIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon
                if (p.lon > e) e = p.lon
            }
            return if (any) GeoBounds(s, w, n, e) else null
        }

        fun union(list: List<GeoBounds>): GeoBounds? =
            if (list.isEmpty()) null else GeoBounds(
                list.minOf { it.south }, list.minOf { it.west }, list.maxOf { it.north }, list.maxOf { it.east },
            )
    }
}

object Geo {
    /** 地球の平均半径 [m]（IUGG）。 */
    const val EARTH_RADIUS_M = 6_371_008.8

    private const val DEG = PI / 180.0

    /** 2 点間の大円距離 [m]（haversine）。 */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distanceM(a: LatLon, b: LatLon): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

    /** 折れ線の長さ [m]。 */
    fun lengthM(points: List<LatLon>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distanceM(points[i - 1], points[i])
        return sum
    }

    /** [lat0] 付近で、経度 1 度・緯度 1 度が何 m か（正距円筒の近似。数 km 以内の線分の計算用）。 */
    fun metersPerDegLon(lat0: Double): Double = EARTH_RADIUS_M * DEG * cos(lat0 * DEG)
    const val METERS_PER_DEG_LAT = EARTH_RADIUS_M * PI / 180.0
}
