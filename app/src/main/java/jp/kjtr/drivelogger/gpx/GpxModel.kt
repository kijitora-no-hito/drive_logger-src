package jp.kjtr.drivelogger.gpx

import jp.kjtr.drivelogger.geo.LatLon

/** GPX の 1 点（trkpt / rtept）。 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMs: Long? = null,
) {
    fun latLon() = LatLon(lat, lon)
}

/** GPX の wpt。 */
data class GpxWaypoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMs: Long? = null,
    val name: String? = null,
    val desc: String? = null,
    val type: String? = null,
    val sym: String? = null,
)

/** trk 1 本（trkseg ごとの点列）または rte 1 本（区間 1 つ）。 */
data class GpxTrack(
    val name: String?,
    val segments: List<List<GpxPoint>>,
    /** rte から読んだもの。 */
    val isRoute: Boolean = false,
) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

data class GpxData(
    /** metadata/name（1.1）または gpx 直下の name（1.0）。 */
    val name: String?,
    val desc: String?,
    val tracks: List<GpxTrack>,
    val waypoints: List<GpxWaypoint>,
    /** gpx 要素の version 属性。 */
    val version: String?,
    /** 点が多すぎたので等間隔に間引いた。 */
    val decimated: Boolean = false,
    /** 途中で壊れていたので、読めたところまでにした。 */
    val partial: Boolean = false,
    /** 経由地が上限を超えたので後ろを捨てた。 */
    val waypointsTruncated: Boolean = false,
) {
    val pointCount: Int get() = tracks.sumOf { it.pointCount }
}

/** GPX として読めなかった（中身が無い・XML でない・大きすぎる）。 */
class GpxException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NOT_GPX, EMPTY, TOO_LARGE, BROKEN }
}
