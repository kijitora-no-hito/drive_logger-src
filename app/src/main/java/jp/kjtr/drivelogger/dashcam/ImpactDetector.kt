package jp.kjtr.drivelogger.dashcam

import kotlin.math.sqrt

/**
 * 衝撃の検知（純ロジック。単体テスト: ImpactDetectorTest。DESIGN.md §3.5）。
 *
 * 加速度センサー（重力を含む [m/s²]）から重力を指数移動平均で推定して引き、残りの大きさ [G] がしきい値以上なら衝撃。
 * - 始めてから [warmUpMs] は数えない（重力の推定が落ち着くまで）。
 * - 一度検知したら [cooldownMs] は次を検知しない（同じ衝撃の揺れ戻し・連続の抑制）。
 * - [thresholdG] が null（感度 OFF）なら検知しない（G の値は出す）。
 * - 0.8.0〜（自転車で段差の振動を衝撃と誤検知していた）:
 *   - **続く時間**: しきい値以上が [minDurationMs] 以上続いたときだけ（段差・小石のトゲのような 1〜2 サンプルの揺れは数えない。
 *     衝突・急停止の衝撃は 50〜150 ms 続く）。
 *   - **振動に合わせたしきい値**: 直近（約 2 秒）の揺れの大きさ（RMS）× [vibrationK] をしきい値に足す（荒れた路面・自転車ではしきい値が上がる）。
 */
class ImpactDetector(
    var thresholdG: Double?,
    private val gravityAlpha: Double = 0.05,
    private val warmUpMs: Long = 3_000,
    private val cooldownMs: Long = 10_000,
    var minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    var vibrationK: Double = DEFAULT_VIBRATION_K,
) {
    data class Impact(val timeMs: Long, val peakG: Double)

    private var gx = Double.NaN
    private var gy = 0.0
    private var gz = 0.0
    private var startedAt = Long.MIN_VALUE
    private var lastImpactAt = Long.MIN_VALUE
    private var lastT = Long.MIN_VALUE
    /** 揺れの大きさの 2 乗の移動平均（[G²]）。 */
    private var vib2 = 0.0
    private var aboveSince = Long.MIN_VALUE
    private var runPeak = 0.0

    /** 直近の大きさ [G]（表示用。少し平滑化）。 */
    var currentG = 0.0
        private set

    /** 直近の揺れの大きさ（RMS）[G]。 */
    val vibrationG: Double get() = sqrt(vib2)

    /** いまのしきい値（揺れの分を足したもの）[G]。OFF なら null。 */
    val effectiveThresholdG: Double? get() = thresholdG?.let { it + vibrationK * vibrationG }

    fun reset() {
        gx = Double.NaN
        startedAt = Long.MIN_VALUE
        lastImpactAt = Long.MIN_VALUE
        lastT = Long.MIN_VALUE
        vib2 = 0.0
        aboveSince = Long.MIN_VALUE
        runPeak = 0.0
        currentG = 0.0
    }

    /** 1 サンプル。衝撃なら [Impact]。 */
    fun feed(timeMs: Long, ax: Double, ay: Double, az: Double): Impact? {
        if (gx.isNaN()) {
            gx = ax; gy = ay; gz = az
            startedAt = timeMs
            lastT = timeMs
            return null
        }
        // 0.8.0〜 大きく揺れている間（衝撃の最中）は重力の推定をほとんど動かさない（衝撃が重力に吸われて小さく見えないよう）
        val r0 = sqrt((ax - gx) * (ax - gx) + (ay - gy) * (ay - gy) + (az - gz) * (az - gz)) / STANDARD_GRAVITY
        val alpha = if (r0 > GRAVITY_FREEZE_G) gravityAlpha * 0.05 else gravityAlpha
        gx += alpha * (ax - gx)
        gy += alpha * (ay - gy)
        gz += alpha * (az - gz)
        val dx = ax - gx
        val dy = ay - gy
        val dz = az - gz
        val g = sqrt(dx * dx + dy * dy + dz * dz) / STANDARD_GRAVITY
        currentG = if (g > currentG) g else currentG + 0.2 * (g - currentG)
        val dt = (timeMs - lastT).coerceIn(0, 200)
        lastT = timeMs
        val th = thresholdG
        // しきい値は「この揺れの前まで」の振動で決める（衝撃そのものでしきい値が上がらないよう、判定の後で振動を更新する）
        val eff = th?.let { it + vibrationK * vibrationG }
        // 揺れの RMS（約 2 秒）。衝撃の最中（しきい値を超えている間）は入れない
        if (eff == null || g < eff) {
            val a = (dt / VIBRATION_TAU_MS.toDouble()).coerceAtMost(1.0)
            vib2 += a * (minOf(g, VIBRATION_CAP_G) * minOf(g, VIBRATION_CAP_G) - vib2)
        }
        if (eff == null) return null
        if (timeMs - startedAt < warmUpMs) return null
        if (lastImpactAt != Long.MIN_VALUE && timeMs - lastImpactAt < cooldownMs) {
            aboveSince = Long.MIN_VALUE
            return null
        }
        if (g < eff) {
            aboveSince = Long.MIN_VALUE
            runPeak = 0.0
            return null
        }
        if (aboveSince == Long.MIN_VALUE) {
            aboveSince = timeMs
            runPeak = g
        } else {
            runPeak = maxOf(runPeak, g)
        }
        if (timeMs - aboveSince < minDurationMs) return null
        lastImpactAt = timeMs
        aboveSince = Long.MIN_VALUE
        return Impact(timeMs, runPeak)
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665
        /** 車: しきい値以上が 40 ms（50 Hz で 3 サンプル）続いたら。 */
        const val DEFAULT_MIN_DURATION_MS = 40L
        const val DEFAULT_VIBRATION_K = 2.0
        private const val VIBRATION_TAU_MS = 2_000L
        /** 揺れの RMS に入れる 1 サンプルの上限 [G]（大きな段差 1 回でしきい値が上がりすぎない）。 */
        private const val VIBRATION_CAP_G = 1.0
        /** これより大きく揺れている間は重力の推定を止める [G]。 */
        private const val GRAVITY_FREEZE_G = 0.8
    }
}
