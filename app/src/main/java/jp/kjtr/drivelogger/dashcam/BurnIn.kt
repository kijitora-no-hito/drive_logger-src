package jp.kjtr.drivelogger.dashcam

import jp.kjtr.drivelogger.gauge.GReading
import jp.kjtr.drivelogger.gauge.MeterStyle
import jp.kjtr.drivelogger.map.MapBurnConfig
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** 焼き込む項目と見た目（設定から作る）。 */
data class BurnInConfig(
    val date: Boolean = true,
    val speed: Boolean = true,
    val latLon: Boolean = true,
    val heading: Boolean = true,
    val g: Boolean = false,
    val appName: Boolean = true,
    val position: BurnInPosition = BurnInPosition.BOTTOM_LEFT,
    val size: BurnInSize = BurnInSize.MEDIUM,
    /** 文字で出すか、メーター（速度計・コンパス・G メーター）で出すか。メーターのとき速度・方位・G の行はメーターに替わる。 */
    val style: MeterStyle = MeterStyle.TEXT,
    /** 速度計の上限 [km/h]。 */
    val gaugeMaxKmh: Int = 180,
    /** 地図の焼き込み（null = しない）。 */
    val map: MapBurnConfig? = null,
)

/** 焼き込む値（分からないものは null）。時刻は描くときに入れる。 */
data class BurnInValues(
    val speedKmh: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val headingDeg: Double? = null,
    val g: Double? = null,
    /** 車の向きにそろえた G（メーター用）。 */
    val vehicleG: GReading = GReading(),
)

/** 焼き込む 1 行。[small] はアプリ名など小さく描く行。 */
data class BurnInLine(val text: String, val small: Boolean = false)

/**
 * 焼き込みの文字の組み立て（純ロジック。単体テスト: BurnInTextTest）。
 *
 * 例（すべて ON・左下）:
 * ```
 * 2026/10/13 14:03:27
 * 62 km/h  NE 45°
 * N35.681230 E139.767120
 * G 0.12
 * ドライブロガー
 * ```
 */
object BurnInText {
    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun lines(timeMs: Long, v: BurnInValues, c: BurnInConfig, appName: String, zone: TimeZone = TimeZone.getDefault()): List<BurnInLine> {
        val out = ArrayList<BurnInLine>(5)
        if (c.date) out.add(BurnInLine(dateTime(timeMs, zone)))
        val parts = ArrayList<String>(2)
        if (c.speed) parts.add(speed(v.speedKmh))
        if (c.heading) parts.add(heading(v.headingDeg))
        if (parts.isNotEmpty()) out.add(BurnInLine(parts.joinToString("  ")))
        if (c.latLon) out.add(BurnInLine(latLon(v.lat, v.lon)))
        if (c.g) out.add(BurnInLine(g(v.g)))
        if (c.appName) out.add(BurnInLine(appName, small = true))
        return out
    }

    /** `2026/10/13 14:03:27`（端末のタイムゾーン）。 */
    fun dateTime(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone, Locale.ROOT)
        cal.timeInMillis = timeMs
        return String.format(
            Locale.ROOT, "%04d/%02d/%02d %02d:%02d:%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
    }

    /** `62 km/h`（整数に丸める）。分からなければ `-- km/h`。 */
    fun speed(kmh: Double?): String = if (kmh == null || kmh.isNaN()) "-- km/h" else "${kmh.coerceAtLeast(0.0).roundToInt()} km/h"

    /** `NE 45°`。分からなければ `--`。 */
    fun heading(deg: Double?): String {
        if (deg == null || deg.isNaN()) return "--"
        val d = ((deg % 360.0) + 360.0) % 360.0
        val r = d.roundToInt() % 360
        return CARDINALS[cardinalIndex(d)] + " " + r + "°"
    }

    fun cardinalIndex(deg: Double): Int = ((((deg % 360.0) + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8

    /** `N35.681230 E139.767120`（小数 6 桁。南緯は S、西経は W）。分からなければ `N-- E--`。 */
    fun latLon(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null || lat.isNaN() || lon.isNaN()) return "N-- E--"
        val ns = if (lat < 0) "S" else "N"
        val ew = if (lon < 0) "W" else "E"
        return ns + String.format(Locale.ROOT, "%.6f", abs(lat)) + " " + ew + String.format(Locale.ROOT, "%.6f", abs(lon))
    }

    /** `G 0.12`。分からなければ `G --`。 */
    fun g(g: Double?): String = if (g == null || g.isNaN()) "G --" else "G " + String.format(Locale.ROOT, "%.2f", g)
}

/** 2 次元のアフィン変換（x' = a·x + c·y + tx, y' = b·x + d·y + ty）。android.graphics.Matrix の setValues と同じ並び。 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** Matrix.setValues に渡す 9 個。 */
    fun values(): FloatArray = floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)
}

/**
 * 焼き込みの配置（純ロジック。単体テスト: BurnInLayoutTest）。
 *
 * OverlayEffect の Canvas はバッファの座標（センサーの向き）。動画は再生時に [rotationDegrees]（時計回り）回して正立になるので、
 * 「正立した画面の座標」で描けるように、正立の座標 → バッファの座標の変換を作る。
 */
object BurnInLayout {
    /** 正立したときの大きさ（90°・270° なら幅と高さが入れ替わる）。 */
    fun uprightSize(rotationDegrees: Int, bufW: Int, bufH: Int): Pair<Int, Int> =
        if (norm(rotationDegrees) % 180 == 90) bufH to bufW else bufW to bufH

    fun uprightToBuffer(rotationDegrees: Int, bufW: Int, bufH: Int): Affine {
        val w = bufW.toFloat()
        val h = bufH.toFloat()
        return when (norm(rotationDegrees)) {
            90 -> Affine(a = 0f, b = -1f, c = 1f, d = 0f, tx = 0f, ty = h)
            180 -> Affine(a = -1f, b = 0f, c = 0f, d = -1f, tx = w, ty = h)
            270 -> Affine(a = 0f, b = 1f, c = -1f, d = 0f, tx = w, ty = 0f)
            else -> Affine(1f, 0f, 0f, 1f, 0f, 0f)
        }
    }

    /** 文字の塊（幅 [blockW]・高さ [blockH]）の左上の位置。[margin] は画面の端からの余白。 */
    fun blockOrigin(position: BurnInPosition, width: Float, height: Float, blockW: Float, blockH: Float, margin: Float): Pair<Float, Float> {
        val x = when (position) {
            BurnInPosition.TOP_LEFT, BurnInPosition.BOTTOM_LEFT -> margin
            BurnInPosition.TOP_RIGHT, BurnInPosition.BOTTOM_RIGHT -> width - margin - blockW
        }
        val y = when (position) {
            BurnInPosition.TOP_LEFT, BurnInPosition.TOP_RIGHT -> margin
            BurnInPosition.BOTTOM_LEFT, BurnInPosition.BOTTOM_RIGHT -> height - margin - blockH
        }
        return x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
    }

    /** 1 行の高さ [px]（短い辺 × 割合。最低 12 px）。 */
    fun lineHeight(size: BurnInSize, width: Int, height: Int): Float = (minOf(width, height) * size.lineRatio).coerceAtLeast(12f)

    /** 焼き込みの速度計の半径 [px]（文字 2.2 行分。コンパス・G メーターはその [SMALL_GAUGE] 倍）。 */
    fun gaugeRadius(size: BurnInSize, width: Int, height: Int): Float = lineHeight(size, width, height) * 2.2f

    const val SMALL_GAUGE = 0.62f

    /**
     * 地図の窓（一辺 [s]）の左上。文字・メーターの塊（高さ [blockH]）と同じ隅なら、塊の上（下の隅）／下（上の隅）に積む。
     */
    fun mapOrigin(
        mapPos: BurnInPosition, textPos: BurnInPosition, width: Float, height: Float, s: Float, margin: Float, blockH: Float,
    ): Pair<Float, Float> {
        val (x, y) = blockOrigin(mapPos, width, height, s, s, margin)
        if (mapPos != textPos || blockH <= 0f) return x to y
        val top = mapPos == BurnInPosition.TOP_LEFT || mapPos == BurnInPosition.TOP_RIGHT
        return x to (if (top) y + blockH + margin else y - blockH - margin).coerceIn(0f, (height - s).coerceAtLeast(0f))
    }

    private fun norm(deg: Int): Int = ((deg % 360) + 360) % 360
}
