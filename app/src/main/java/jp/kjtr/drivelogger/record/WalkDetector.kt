package jp.kjtr.drivelogger.record

import kotlin.math.sqrt

/** 歩行の判定の設定（DESIGN.md §4.7）。 */
data class WalkConfig(
    /** 揺れの周期を見る長さ [ms]。 */
    val windowMs: Long = 4_000,
    /** 加速度をそろえる刻み [ms]（約 50 Hz）。 */
    val binMs: Long = 20,
    /** 加速度の大きさ（重力を含む |a|）の標準偏差がこれ以上 [m/s²]（歩くと 1〜3、車の振動は多くが 0.5 未満）。 */
    val minStdAccel: Double = 0.6,
    /** 1 歩の周期（[minPeriodMs]〜[maxPeriodMs]＝1.1〜3 Hz）の自己相関がこれ以上なら「周期的な揺れ」。 */
    val minAutocorr: Double = 0.5,
    val minPeriodMs: Long = 330,
    val maxPeriodMs: Long = 900,
    /** 判定し直す間隔 [ms]。 */
    val evalEveryMs: Long = 500,
    /** 周期的な揺れがこれだけ続き、速さが [enterMaxSpeedMps] 未満なら徒歩にする [ms]。 */
    val enterAfterMs: Long = 3_000,
    /** 徒歩にする速さの上限 [m/s]（10 km/h）。 */
    val enterMaxSpeedMps: Double = 10.0 / 3.6,
    /** この速さ以上が [exitAfterMs] 続いたら徒歩をやめる [m/s]（15 km/h。乗り物）。 */
    val exitSpeedMps: Double = 15.0 / 3.6,
    val exitAfterMs: Long = 3_000,
    /** 揺れが無いまま [enterMaxSpeedMps] 以上で [exitNoGaitMs] 動いたら徒歩をやめる（ゆっくり走る車）。 */
    val exitNoGaitMs: Long = 10_000,
    /** 速さはこれより古ければ分からないとみなす [ms]。 */
    val speedStaleMs: Long = 5_000,
)

/**
 * 歩いているかの判定（純ロジック。単体テスト: WalkFusionTest）。0.8.0〜。
 *
 * 加速度の大きさ |a|（重力を含む。端末の向きによらない）を 50 Hz にそろえ、直近 4 秒の
 * (1) ばらつき（標準偏差 ≥ 0.6 m/s²）と (2) 1 歩の周期（0.33〜0.9 秒）の自己相関（≥ 0.5）を見る＝「歩行の周期的な揺れ」。
 * 揺れが 3 秒続き、GPS の速さが 10 km/h 未満（分からなければ可）なら**徒歩**。15 km/h 以上が 3 秒続くか、
 * 揺れが無いまま 10 km/h 以上で 10 秒動いたら徒歩をやめる（乗り物）。止まっている間（信号待ち）は徒歩のまま。
 */
class WalkDetector(private val cfg: WalkConfig = WalkConfig()) {
    var walking = false
        private set

    private val buf = ArrayDeque<Pair<Long, Double>>()
    private var lastEval = Long.MIN_VALUE
    private var periodicSince = Long.MIN_VALUE
    private var lastPeriodicAt = Long.MIN_VALUE
    private var speed: Double? = null
    private var speedAt = Long.MIN_VALUE
    private var fastSince = Long.MIN_VALUE
    private var movingNoGaitSince = Long.MIN_VALUE

    /** 直近の判定（周期的な揺れがあったか）。 */
    var periodic = false
        private set

    fun reset() {
        walking = false
        buf.clear()
        lastEval = Long.MIN_VALUE
        periodicSince = Long.MIN_VALUE
        lastPeriodicAt = Long.MIN_VALUE
        speed = null
        speedAt = Long.MIN_VALUE
        fastSince = Long.MIN_VALUE
        movingNoGaitSince = Long.MIN_VALUE
        periodic = false
    }

    /** 加速度センサー（重力を含む）の大きさ [m/s²]。 */
    fun onAccel(tMs: Long, norm: Double) {
        if (norm.isNaN()) return
        buf.addLast(tMs to norm)
        while (buf.isNotEmpty() && tMs - buf.first().first > cfg.windowMs) buf.removeFirst()
        if (lastEval != Long.MIN_VALUE && tMs - lastEval < cfg.evalEveryMs) return
        lastEval = tMs
        periodic = gait(tMs)
        if (periodic) {
            if (periodicSince == Long.MIN_VALUE) periodicSince = tMs
            lastPeriodicAt = tMs
        } else {
            periodicSince = Long.MIN_VALUE
        }
        update(tMs)
    }

    /** GPS の速さ（端末のドップラー。無ければ記録の速さ）[m/s]。 */
    fun onSpeed(tMs: Long, mps: Double) {
        speed = mps
        speedAt = tMs
        if (mps >= cfg.exitSpeedMps) {
            if (fastSince == Long.MIN_VALUE) fastSince = tMs
        } else {
            fastSince = Long.MIN_VALUE
        }
        if (mps >= cfg.enterMaxSpeedMps && !periodic) {
            if (movingNoGaitSince == Long.MIN_VALUE) movingNoGaitSince = tMs
        } else {
            movingNoGaitSince = Long.MIN_VALUE
        }
        update(tMs)
    }

    private fun update(tMs: Long) {
        val sp = speed?.takeIf { tMs - speedAt <= cfg.speedStaleMs }
        if (!walking) {
            if (periodicSince != Long.MIN_VALUE && tMs - periodicSince >= cfg.enterAfterMs && (sp == null || sp < cfg.enterMaxSpeedMps)) {
                walking = true
                fastSince = Long.MIN_VALUE
                movingNoGaitSince = Long.MIN_VALUE
            }
        } else {
            val fast = fastSince != Long.MIN_VALUE && tMs - fastSince >= cfg.exitAfterMs
            val ride = movingNoGaitSince != Long.MIN_VALUE && tMs - movingNoGaitSince >= cfg.exitNoGaitMs &&
                (lastPeriodicAt == Long.MIN_VALUE || tMs - lastPeriodicAt >= cfg.exitNoGaitMs)
            if (fast || ride) {
                walking = false
                periodicSince = Long.MIN_VALUE
            }
        }
    }

    /** 直近の窓に歩行の周期的な揺れがあるか。 */
    private fun gait(tMs: Long): Boolean {
        if (buf.size < 20 || tMs - buf.first().first < cfg.windowMs / 2) return false
        val n = ((tMs - buf.first().first) / cfg.binMs).toInt() + 1
        val sum = DoubleArray(n)
        val cnt = IntArray(n)
        val t0 = buf.first().first
        for ((t, v) in buf) {
            val i = ((t - t0) / cfg.binMs).toInt().coerceIn(0, n - 1)
            sum[i] += v
            cnt[i]++
        }
        // 空いた刻みは直前の値で埋める
        val x = DoubleArray(n)
        var prev = buf.first().second
        for (i in 0 until n) {
            if (cnt[i] > 0) prev = sum[i] / cnt[i]
            x[i] = prev
        }
        val mean = x.average()
        var v = 0.0
        for (i in 0 until n) {
            x[i] -= mean
            v += x[i] * x[i]
        }
        val std = sqrt(v / n)
        if (std < cfg.minStdAccel) return false
        val l0 = (cfg.minPeriodMs / cfg.binMs).toInt()
        val l1 = (cfg.maxPeriodMs / cfg.binMs).toInt().coerceAtMost(n / 2)
        var best = 0.0
        for (lag in l0..l1) {
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in 0 until n - lag) {
                val a = x[i]
                val b = x[i + lag]
                sxy += a * b
                sxx += a * a
                syy += b * b
            }
            if (sxx <= 0 || syy <= 0) continue
            val r = sxy / sqrt(sxx * syy)
            if (r > best) best = r
        }
        return best >= cfg.minAutocorr
    }
}
