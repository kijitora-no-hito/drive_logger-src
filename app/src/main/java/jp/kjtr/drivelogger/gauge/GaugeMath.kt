package jp.kjtr.drivelogger.gauge

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 画面の HUD・焼き込みの表し方。 */
enum class MeterStyle { TEXT, GAUGES }

/**
 * 車の向きにそろえた G（[longG] 前向きが正、[latG] 右向きが正）。向きの補正ができていなければ [calibrated] = false で、
 * 大きさ [totalG]（水平の成分）だけ使う。分からなければ [totalG] も null。
 */
data class GReading(
    val longG: Double? = null,
    val latG: Double? = null,
    val totalG: Double? = null,
    val calibrated: Boolean = false,
)

/**
 * メーターの角度などの計算（純ロジック。単体テスト: GaugeMathTest）。
 * 角度は android.graphics.Canvas と同じく「0° = 右（3 時）、時計回りが正」。
 */
object GaugeMath {
    /** 速度計の目盛りの始まり（左下 7 時半）と振れ幅（270°）。 */
    const val SPEED_START_DEG = 135f
    const val SPEED_SWEEP_DEG = 270f

    /** 上限の選択肢 [km/h]。 */
    val MAX_CHOICES = listOf(120, 180, 240)

    /** 針の角度。分からなければ 0 km/h の位置。上限を超えたら上限。 */
    fun speedAngle(kmh: Double?, maxKmh: Int): Float {
        val v = if (kmh == null || kmh.isNaN()) 0.0 else kmh.coerceIn(0.0, maxKmh.toDouble())
        return SPEED_START_DEG + SPEED_SWEEP_DEG * (v / maxKmh).toFloat()
    }

    /** 数字を振る目盛りの間隔 [km/h]（数字が 7〜10 個になるように）。 */
    fun majorStep(maxKmh: Int): Int = if (maxKmh <= 180) 20 else 40

    /** 細かい目盛りの間隔。 */
    fun minorStep(maxKmh: Int): Int = majorStep(maxKmh) / 2

    /** 中心 ([cx], [cy]) から角度 [deg]・半径 [r] の点。 */
    fun polar(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> = polarX(cx, r, deg) to polarY(cy, r, deg)

    /** [polar] の x だけ（描くたびの割り当てを避けるとき）。 */
    fun polarX(cx: Float, r: Float, deg: Float): Float = (cx + r * cos(deg * PI / 180.0)).toFloat()

    fun polarY(cy: Float, r: Float, deg: Float): Float = (cy + r * sin(deg * PI / 180.0)).toFloat()

    /** 方位 [headingDeg]（0 = 北・時計回り）を Canvas の角度に（北 = 上 = -90°）。 */
    fun bearingToCanvas(headingDeg: Double): Float = (headingDeg - 90.0).toFloat()

    /** コンパスの文字盤の回し方（進む向きが上に来るよう、-heading 回す）。 */
    fun compassDialRotation(headingDeg: Double?): Float = if (headingDeg == null || headingDeg.isNaN()) 0f else -headingDeg.toFloat()

    /** G メーターのいちばん外の円 [G]。 */
    const val G_FULL_SCALE = 0.6

    /**
     * G メーターの点の位置（中心からの差、半径 [r] の円の中に収める）。
     * 体が押される向きで表す: 加速 → 下、ブレーキ → 上、右に曲がる → 左。
     */
    fun gDot(longG: Double, latG: Double, r: Float, fullScale: Double = G_FULL_SCALE): Pair<Float, Float> {
        var x = -latG / fullScale * r
        var y = longG / fullScale * r
        val d = hypot(x, y)
        val max = r * 0.92
        if (d > max) {
            x *= max / d
            y *= max / d
        }
        return x.toFloat() to y.toFloat()
    }
}
