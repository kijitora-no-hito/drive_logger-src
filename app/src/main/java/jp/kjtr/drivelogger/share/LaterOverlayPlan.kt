package jp.kjtr.drivelogger.share

import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.BurnInValues
import jp.kjtr.drivelogger.dashcam.SpeedDraw
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.playback.TrackSync

/**
 * 書き出し・再生で重ねる項目（0.4.0〜「あとから重ねる」方式）。**入れない項目は最初から描かない**（覆うのではない）。
 * G は走行の記録に残していないので、あとから重ねる方式では描けない（焼き込み方式だけ）。
 */
data class OverlayItems(
    val date: Boolean = true,
    val speed: Boolean = true,
    val heading: Boolean = true,
    val latLon: Boolean = true,
    val map: Boolean = false,
    val appName: Boolean = true,
) {
    val any: Boolean get() = date || speed || heading || latLon || map || appName
}

/** あるフレームに描くもの。 */
data class OverlayFrame(
    val wallMs: Long,
    val config: BurnInConfig,
    val values: BurnInValues,
    val speedDraw: SpeedDraw,
)

/**
 * あとから重ねる表示の計画（純ロジック。単体テスト: LaterOverlayTest）。
 *
 * - 時刻: 書き出す 1 本分の出力の時刻 [localMs]（そのファイルの切り出しの頭が 0）→ 壁時計 = 動画の開始 + 切り出しの頭 + [localMs]。
 *   その時刻の位置・速度・方位を GPS 記録から補間する（[TrackSync]。アプリ内の再生と同じ）。
 * - 項目: [OverlayItems] で選ばなかったものは設定ごと外す（[BurnInConfig] の項目を false・地図は null）。
 * - 速度を隠す: しきい値を超えた時間帯（[hideSpans]、このファイルの出力の 0 起点）だけ速度を描かない（[SpeedDraw.OMIT]）。
 *   設定で「---」を出す方式（[SpeedDraw.DASHES]）も選べる。**低い数字を出すことはしない。**
 */
class LaterOverlayPlan(
    base: BurnInConfig,
    items: OverlayItems,
    private val sync: TrackSync,
    /** このファイルの壁時計の頭（= 動画の開始 + 切り出しの頭）。 */
    private val wallStartMs: Long,
    private val hideSpans: List<Span> = emptyList(),
    private val dashes: Boolean = false,
) {
    val config: BurnInConfig = configFor(base, items)

    fun frame(localMs: Long): OverlayFrame {
        val wall = wallStartMs + localMs.coerceAtLeast(0)
        val fix = sync.at(wall)
        val values = BurnInValues(
            speedKmh = fix?.speedMps?.let { it * 3.6 },
            lat = fix?.lat,
            lon = fix?.lon,
            headingDeg = fix?.bearingDeg,
        )
        val hidden = hideSpans.any { localMs in it }
        val sd = when {
            !hidden -> SpeedDraw.NORMAL
            dashes -> SpeedDraw.DASHES
            else -> SpeedDraw.OMIT
        }
        return OverlayFrame(wall, config, values, sd)
    }

    companion object {
        /** 選んだ項目だけの設定（G は描けないので常に外す）。 */
        fun configFor(base: BurnInConfig, items: OverlayItems): BurnInConfig = base.copy(
            date = items.date, speed = items.speed, heading = items.heading, latLon = items.latLon,
            appName = items.appName, g = false, map = if (items.map) base.map else null,
        )

        /** 地図に重ねる軌跡（同期に使う点から）。 */
        fun track(points: List<SyncPoint>): List<List<LatLon>> = listOf(points.map { LatLon(it.lat, it.lon) })

        /** 描き直す間隔 [ms]（書き出しのフレームごとに描くと重いので、この粒度で同じ絵を使う）。 */
        const val REDRAW_MS = 200L

        /** 描き直しの単位（同じなら前の絵のまま。速度を隠す・出すが切り替わったら必ず描き直す）。 */
        fun redrawKey(localMs: Long, speedDraw: SpeedDraw): Long = (localMs.coerceAtLeast(0) / REDRAW_MS) * 4 + speedDraw.ordinal
    }
}
