package jp.kjtr.drivelogger.record

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 融合の設定（DESIGN.md §4.2）。雑音は 1σ。 */
data class FusionConfig(
    /** 前後の加速度（センサー）の雑音 [m/s²]。 */
    val accelNoise: Double = 0.6,
    /** 加速度が無い（向きの補正がまだ）ときの速度の変化の想定 [m/s²]。 */
    val accelNoiseNoImu: Double = 2.5,
    /** ヨーレート（ジャイロ）の雑音 [rad/s]。 */
    val yawNoise: Double = 0.03,
    /** ジャイロが無いときの向きの変化の想定 [rad/s]。 */
    val yawNoiseNoGyro: Double = 0.3,
    /** 位置の予測の雑音 [m²/s]。 */
    val posNoise: Double = 0.2,
    /** GPS の速度の雑音 [m/s]。 */
    val speedSigma: Double = 0.6,
    /** GPS の方位の雑音 [度]。 */
    val bearingSigmaDeg: Double = 12.0,
    /** GPS の位置の雑音の下限 [m]（精度の値が小さすぎるとき）。 */
    val minPosSigma: Double = 2.5,
    /** GPS が来ないとき、推測航法を続ける長さ [ms]（加速度が使えるとき / 使えないとき）。 */
    val maxDeadReckonMs: Long = 30_000,
    val maxDeadReckonNoImuMs: Long = 8_000,
    /** 推定の位置の誤差がこれを超えたら打ち切る [m]。 */
    val maxSigmaM: Double = 60.0,
    /** 位置の外れ値の判定（マハラノビス距離²、2 自由度）。 */
    val gateChi2: Double = 25.0,
    /** 外れ値が続いたら GPS に合わせ直す回数。 */
    val resetAfterRejects: Int = 4,
)

/** 融合した位置（[timeMs] は融合の時計＝elapsedRealtime の ms）。 */
data class FusedFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    /** 進行方向 [度]（分からなければ null）。 */
    val headingDeg: Double?,
    /** 位置の誤差の目安（1σ）[m]。 */
    val sigmaM: Double,
    /** GPS が来ていない間の推測。 */
    val deadReckoning: Boolean,
)

/**
 * GPS と加速度・ジャイロの融合（拡張カルマンフィルタ。純ロジック。単体テスト: FusionTest）。
 *
 * - 状態: 局所の平面（最初の点を原点に東 x・北 y [m]）の位置・速さ v [m/s]・進行方向 θ [rad、北から時計回り]。
 * - 予測（センサーのたび）: 位置 += v·(sin θ, cos θ)·dt、v += 前後の加速度·dt、θ += ヨーレート·dt。
 *   加速度は車の向きにそろえた前後の加速度（[jp.kjtr.drivelogger.gauge.VehicleGEstimator]）、ヨーレートはジャイロの「上」まわりの回転（左回りが正なので符号を反転）。
 * - 更新（GPS のたび）: 位置（精度で重み付け）・速さ・方位（速いときだけ）。位置が外れすぎる点は捨て、続いたら GPS に合わせ直す。
 * - GPS が来ない間は予測だけ（推測航法）。[FusionConfig.maxDeadReckonMs] を超えるか誤差が大きくなったら打ち切り、次の GPS からやり直す。
 *
 * 限界: スマホの加速度は雑音とずれが大きく、長い推測航法はずれる（DESIGN.md §4.2）。
 */
class GpsImuFusion(private val cfg: FusionConfig = FusionConfig()) {
    private val x = DoubleArray(4)
    private val p = Array(4) { DoubleArray(4) }
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerDegLon = 0.0
    private var initialized = false
    private var headingKnown = false
    private var stateTime = 0L
    private var lastGpsAt = Long.MIN_VALUE
    private var lastAccel: Double? = null
    private var lastAccelAt = Long.MIN_VALUE
    private var lastYaw: Double? = null
    private var lastYawAt = Long.MIN_VALUE
    private var rejects = 0
    private var afterHold = false
    // 方位がまだ分からないとき、動いた向きから決めるための基準
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var lastRawX = Double.NaN
    private var lastRawY = Double.NaN
    private var lastRawAt = Long.MIN_VALUE

    val isValid: Boolean get() = initialized

    fun reset() {
        initialized = false
        headingKnown = false
        lastGpsAt = Long.MIN_VALUE
        rejects = 0
        lastRawX = Double.NaN
    }

    /** センサー（加速度・ジャイロ）の値を受け取り、[tMs] まで予測を進める。分からない値は null。 */
    fun onImu(tMs: Long, forwardAccel: Double?, yawRate: Double?) {
        if (forwardAccel != null) { lastAccel = forwardAccel; lastAccelAt = tMs }
        if (yawRate != null) { lastYaw = yawRate; lastYawAt = tMs }
        if (initialized && tMs > stateTime) predictTo(tMs)
    }

    /**
     * GPS の 1 点。融合した位置を返す（最初の点・合わせ直した点はその点のまま）。外れ値として捨てたときも今の推定を返す。
     */
    fun onGps(tMs: Long, lat: Double, lon: Double, accuracyM: Float?, speedMps: Float?, bearingDeg: Float?): FusedFix {
        val posSigma = max((accuracyM?.toDouble() ?: 15.0) * 0.75, cfg.minPosSigma)
        if (!initialized || tMs - lastGpsAt > deadReckonLimit(tMs) || estimateSigma() > cfg.maxSigmaM) {
            init(tMs, lat, lon, posSigma, speedMps, bearingDeg)
            return output(false)
        }
        if (tMs > stateTime) predictTo(tMs)
        val (zx, zy) = toLocal(lat, lon)
        // 端末の速度が 0 なのに位置は動いている（エミュレータの geo fix など）なら、速度・方位は使わない
        val posDt = if (!lastRawX.isNaN() && tMs > lastRawAt) (tMs - lastRawAt) / 1000.0 else 0.0
        val posSpeed = if (posDt > 0) hypot(zx - lastRawX, zy - lastRawY) / posDt else null
        val bogusZero = speedMps != null && speedMps < 0.5f && posSpeed != null && posSpeed > 2.0
        lastRawX = zx; lastRawY = zy; lastRawAt = tMs

        // 位置（2 次元のゲート）
        val r = posSigma * posSigma
        val s00 = p[0][0] + r
        val s11 = p[1][1] + r
        val s01 = p[0][1]
        val det = s00 * s11 - s01 * s01
        val dx = zx - x[0]
        val dy = zy - x[1]
        val d2 = if (det > 0) (dx * dx * s11 - 2 * dx * dy * s01 + dy * dy * s00) / det else 0.0
        // 向きが分からないうちは動きを予測できないので、外れ値の判定はしない
        if ((headingKnown || afterHold) && d2 > cfg.gateChi2) {
            rejects++
            // 停車の後の最初の測位は外れ値にしない（停車中に動いていた・測位を止めていた分）
            if (afterHold || rejects >= cfg.resetAfterRejects) {
                afterHold = false
                init(tMs, lat, lon, posSigma, speedMps, bearingDeg)
                return output(false)
            }
            lastGpsAt = tMs
            return output(false)
        }
        rejects = 0
        afterHold = false
        scalarUpdate(0, zx, r)
        scalarUpdate(1, zy, r)
        if (speedMps != null && !bogusZero && speedMps >= 0f) {
            scalarUpdate(2, speedMps.toDouble(), cfg.speedSigma * cfg.speedSigma)
        } else if (posSpeed != null && posDt > 0) {
            // 端末の速度が使えないときは、位置の差からの速さを（ゆるく）使う
            val sv = max(1.5, posSigma * 1.4 / posDt)
            scalarUpdate(2, posSpeed, sv * sv)
        }
        val bs = cfg.bearingSigmaDeg * PI / 180.0
        if (bearingDeg != null && !bogusZero && speedMps != null && speedMps >= 3f && x[2] >= 2.0) {
            val z = bearingDeg * PI / 180.0
            if (!headingKnown) {
                setHeading(z, bs * bs)
            } else {
                scalarUpdate(3, z, bs * bs)
            }
        } else if (!headingKnown && x[2] >= 2.0 && hypot(x[0] - anchorX, x[1] - anchorY) >= 15.0) {
            // 方位が来ないときは動いた向きから
            setHeading(atan2(x[0] - anchorX, x[1] - anchorY), (15.0 * PI / 180.0).let { it * it })
        }
        if (x[2] < 0) x[2] = 0.0
        lastGpsAt = tMs
        maybeRecenter()
        return output(false)
    }

    /** [tMs] の推定（推測航法）。GPS が長く来ない・誤差が大きいなら null（打ち切り）。 */
    fun estimate(tMs: Long): FusedFix? {
        if (!initialized) return null
        if (tMs - lastGpsAt > deadReckonLimit(tMs)) return null
        if (tMs > stateTime) predictTo(tMs)
        if (estimateSigma() > cfg.maxSigmaM) return null
        return output(true)
    }

    /** 停車: 位置を [lat]/[lon] に止め、速さ 0 にする（向きはそのまま）。動き出しはこの位置からの移動になる。 */
    fun hold(tMs: Long, lat: Double, lon: Double) {
        if (!initialized) {
            init(tMs, lat, lon, cfg.minPosSigma, 0f, null)
        }
        val (hx, hy) = toLocal(lat, lon)
        x[0] = hx; x[1] = hy; x[2] = 0.0
        for (i in 0..3) for (j in 0..3) if (i != j && (i < 3 || j < 3)) p[i][j] = 0.0
        p[0][0] = cfg.minPosSigma * cfg.minPosSigma
        p[1][1] = p[0][0]
        p[2][2] = 0.05
        stateTime = tMs
        lastGpsAt = tMs
        anchorX = hx; anchorY = hy
        lastRawX = Double.NaN
        afterHold = true
    }

    private fun deadReckonLimit(tMs: Long): Long =
        if (lastAccelAt != Long.MIN_VALUE && tMs - lastAccelAt < 2_000) cfg.maxDeadReckonMs else cfg.maxDeadReckonNoImuMs

    private fun init(tMs: Long, lat: Double, lon: Double, posSigma: Double, speedMps: Float?, bearingDeg: Float?) {
        lat0 = lat
        lon0 = lon
        mPerDegLon = M_PER_DEG * cos(lat * PI / 180.0)
        x[0] = 0.0; x[1] = 0.0
        x[2] = (speedMps?.toDouble() ?: 0.0).coerceAtLeast(0.0)
        for (row in p) row.fill(0.0)
        p[0][0] = posSigma * posSigma
        p[1][1] = posSigma * posSigma
        // 0 は「止まっている」とも「速度が出ない端末」とも取れるので強くは信じない
        p[2][2] = if (speedMps != null && speedMps >= 0.5f) 1.0 else 25.0
        if (bearingDeg != null && speedMps != null && speedMps >= 3f) {
            x[3] = bearingDeg * PI / 180.0
            p[3][3] = (15.0 * PI / 180.0).let { it * it }
            headingKnown = true
        } else {
            x[3] = 0.0
            p[3][3] = PI * PI
            headingKnown = false
        }
        anchorX = 0.0; anchorY = 0.0
        lastRawX = 0.0; lastRawY = 0.0; lastRawAt = tMs
        stateTime = tMs
        lastGpsAt = tMs
        rejects = 0
        afterHold = false
        initialized = true
    }

    private fun setHeading(rad: Double, variance: Double) {
        x[3] = rad
        for (i in 0..3) { p[3][i] = 0.0; p[i][3] = 0.0 }
        p[3][3] = variance
        headingKnown = true
    }

    private fun predictTo(tMs: Long) {
        var t = stateTime
        while (t < tMs) {
            val step = min(MAX_STEP_MS, tMs - t)
            predict(step / 1000.0, t)
            t += step
        }
        stateTime = tMs
    }

    private fun predict(dt: Double, tMs: Long) {
        val accelOk = lastAccelAt != Long.MIN_VALUE && tMs - lastAccelAt < 1_000
        val yawOk = lastYawAt != Long.MIN_VALUE && tMs - lastYawAt < 1_000
        val a = if (accelOk) lastAccel ?: 0.0 else 0.0
        val w = if (yawOk && headingKnown) lastYaw ?: 0.0 else 0.0
        val sa = if (accelOk) cfg.accelNoise else cfg.accelNoiseNoImu
        val sw = if (yawOk) cfg.yawNoise else cfg.yawNoiseNoGyro
        val th = x[3]
        val v = x[2]
        if (!headingKnown) {
            // 向きが分からないうちは位置を動かさず、動けたはずの分だけ位置の誤差を広げる
            x[2] = (x[2] + a * dt).coerceAtLeast(0.0)
            // 0.8.0〜: 向きが分からない間の位置の広がりは「速さ × 経過時間」で増える（刻みごとの二乗の和だと小さすぎて、
            // 低速（7 km/h 未満）で GPS に 10 m 近く遅れていた）。拡散の形で v² の数倍を足す
            val spread = v + LOW_SPEED_SPREAD_MPS
            p[0][0] += spread * spread * dt * LOW_SPEED_SPREAD_GAIN + cfg.posNoise * dt
            p[1][1] += spread * spread * dt * LOW_SPEED_SPREAD_GAIN + cfg.posNoise * dt
            p[2][2] += sa * sa * dt
            return
        }
        val s = sin(th)
        val c = cos(th)
        x[0] += v * s * dt
        x[1] += v * c * dt
        x[2] = (x[2] + a * dt).coerceAtLeast(0.0)
        x[3] = wrap(x[3] + w * dt)
        // P = F P Fᵀ + Q（F = I + 位置の行の v と θ の項）
        val f02 = s * dt
        val f03 = v * c * dt
        val f12 = c * dt
        val f13 = -v * s * dt
        val fp = Array(4) { i -> DoubleArray(4) { j -> p[i][j] } }
        for (j in 0..3) {
            fp[0][j] = p[0][j] + f02 * p[2][j] + f03 * p[3][j]
            fp[1][j] = p[1][j] + f12 * p[2][j] + f13 * p[3][j]
        }
        for (i in 0..3) {
            val a0 = fp[i][0] + fp[i][2] * f02 + fp[i][3] * f03
            val a1 = fp[i][1] + fp[i][2] * f12 + fp[i][3] * f13
            p[i][0] = a0
            p[i][1] = a1
            p[i][2] = fp[i][2]
            p[i][3] = fp[i][3]
        }
        p[0][0] += cfg.posNoise * dt
        p[1][1] += cfg.posNoise * dt
        p[2][2] += sa * sa * dt
        p[3][3] = min(p[3][3] + sw * sw * dt, PI * PI)
    }

    /** 1 つの成分 [i] の観測 [z]（分散 [r]）。 */
    private fun scalarUpdate(i: Int, z: Double, r: Double) {
        var y = z - x[i]
        if (i == 3) y = wrap(y)
        val s = p[i][i] + r
        if (s <= 0) return
        val k = DoubleArray(4) { p[it][i] / s }
        for (j in 0..3) x[j] += k[j] * y
        x[3] = wrap(x[3])
        val row = DoubleArray(4) { p[i][it] }
        for (a in 0..3) for (b in 0..3) p[a][b] -= k[a] * row[b]
        // 対称に保つ
        for (a in 0..3) for (b in a + 1..3) {
            val m = (p[a][b] + p[b][a]) / 2
            p[a][b] = m; p[b][a] = m
        }
    }

    private fun estimateSigma(): Double = sqrt(max(p[0][0], 0.0) + max(p[1][1], 0.0))

    private fun output(dr: Boolean): FusedFix {
        val lat = lat0 + x[1] / M_PER_DEG
        val lon = lon0 + x[0] / mPerDegLon
        val heading = if (headingKnown) ((x[3] * 180.0 / PI) % 360.0 + 360.0) % 360.0 else null
        return FusedFix(stateTime, lat, lon, x[2], heading, estimateSigma(), dr)
    }

    /** 原点から 20 km 以上離れたら、平面の近似のずれを抑えるため今の位置を原点に置き直す。 */
    private fun maybeRecenter() {
        if (abs(x[0]) < 20_000 && abs(x[1]) < 20_000) return
        val lat = lat0 + x[1] / M_PER_DEG
        val lon = lon0 + x[0] / mPerDegLon
        anchorX -= x[0]; anchorY -= x[1]
        if (!lastRawX.isNaN()) { lastRawX -= x[0]; lastRawY -= x[1] }
        lat0 = lat
        lon0 = lon
        mPerDegLon = M_PER_DEG * cos(lat * PI / 180.0)
        x[0] = 0.0; x[1] = 0.0
    }

    private fun toLocal(lat: Double, lon: Double): Pair<Double, Double> = (lon - lon0) * mPerDegLon to (lat - lat0) * M_PER_DEG

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val MAX_STEP_MS = 100L
        /** 向きが分からない間の位置の広がり（速さに足す分 [m/s] と倍率）。 */
        private const val LOW_SPEED_SPREAD_MPS = 0.5
        private const val LOW_SPEED_SPREAD_GAIN = 4.0
        fun wrap(a: Double): Double {
            var r = a % (2 * PI)
            if (r > PI) r -= 2 * PI
            if (r < -PI) r += 2 * PI
            return r
        }
    }
}

/** 停車の判定の設定（DESIGN.md §4.3）。 */
data class StopConfig(
    /** 静か: 直近 [windowMs] の重力を除いた加速度の平均 [m/s²] がこれ未満（最大はその 3 倍未満）。 */
    val quietAccel: Double = 0.25,
    /** 静か: ジャイロの大きさの最大 [rad/s]。 */
    val quietGyro: Double = 0.06,
    val windowMs: Long = 2_000,
    /** GPS の速さがこれ未満 [m/s]（3 km/h）。 */
    val stillSpeedMps: Double = 0.8,
    /** 静か＋遅いがこれだけ続いたら停車 [ms]。 */
    val stopAfterMs: Long = 4_000,
    /** 動き出し: 直近 [moveWindowMs] の加速度の平均がこれ以上 [m/s²]。 */
    val moveAccel: Double = 0.6,
    val moveWindowMs: Long = 400,
    /** 動き出し: ジャイロの大きさ [rad/s]。 */
    val moveGyro: Double = 0.2,
    /** 動き出し: GPS（停車中の確かめ）の速さ [m/s]。 */
    val moveSpeedMps: Double = 2.0,
)

/**
 * 停車の判定（純ロジック。単体テスト: StopDetectorTest）。
 * 加速度・ジャイロが静か（揺れが小さい）かつ GPS の速さがほぼ 0 の状態が [StopConfig.stopAfterMs] 続いたら停車。
 * 停車中は、加速度・ジャイロの動き（または GPS の速さ）ですぐ解除する。
 */
class StopDetector(private val cfg: StopConfig = StopConfig()) {
    enum class Event { STOP, MOVE }

    private class M(val t: Long, val acc: Double, val gyro: Double?)

    private val window = ArrayDeque<M>()
    var stopped = false
        private set
    private var lastSpeed: Double? = null
    private var lastSpeedAt = Long.MIN_VALUE
    private var calmSince = Long.MIN_VALUE

    fun reset() {
        window.clear()
        stopped = false
        lastSpeed = null
        lastSpeedAt = Long.MIN_VALUE
        calmSince = Long.MIN_VALUE
    }

    /** 加速度（重力を除いた大きさ [m/s²]）とジャイロ（大きさ [rad/s]、無ければ null）。 */
    fun onMotion(tMs: Long, linAccel: Double, gyro: Double?): Event? {
        window.addLast(M(tMs, linAccel, gyro))
        while (window.isNotEmpty() && tMs - window.first().t > cfg.windowMs) window.removeFirst()
        if (stopped && moving(tMs)) {
            stopped = false
            calmSince = Long.MIN_VALUE
            return Event.MOVE
        }
        return null
    }

    /** GPS の速さ（記録の速さ）[m/s]。 */
    fun onSpeed(tMs: Long, speedMps: Double): Event? {
        lastSpeed = speedMps
        lastSpeedAt = tMs
        if (stopped && speedMps >= cfg.moveSpeedMps) {
            stopped = false
            calmSince = Long.MIN_VALUE
            return Event.MOVE
        }
        return null
    }

    /** 定期的に（4 回/秒など）。停車になったら STOP。 */
    fun tick(tMs: Long): Event? {
        if (stopped) return null
        val speed = lastSpeed
        val slow = speed != null && speed < cfg.stillSpeedMps && tMs - lastSpeedAt < 5_000
        if (slow && quiet(tMs)) {
            if (calmSince == Long.MIN_VALUE) calmSince = tMs
            if (tMs - calmSince >= cfg.stopAfterMs) {
                stopped = true
                return Event.STOP
            }
        } else {
            calmSince = Long.MIN_VALUE
        }
        return null
    }

    private fun quiet(tMs: Long): Boolean {
        if (window.isEmpty()) return false
        if (tMs - window.first().t < cfg.windowMs * 0.8) return false
        var sum = 0.0
        var maxA = 0.0
        var maxG = 0.0
        for (m in window) {
            sum += m.acc
            maxA = max(maxA, m.acc)
            m.gyro?.let { maxG = max(maxG, it) }
        }
        return sum / window.size < cfg.quietAccel && maxA < cfg.quietAccel * 3 && maxG < cfg.quietGyro
    }

    private fun moving(tMs: Long): Boolean {
        var sum = 0.0
        var n = 0
        var maxG = 0.0
        for (m in window) {
            if (tMs - m.t > cfg.moveWindowMs) continue
            sum += m.acc
            n++
            m.gyro?.let { maxG = max(maxG, it) }
        }
        return (n > 0 && sum / n >= cfg.moveAccel) || maxG >= cfg.moveGyro
    }
}
