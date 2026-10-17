package jp.kjtr.drivelogger.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** 軌跡のなめらかさの設定（[accel] = 加速度の雑音 [m/s²]。乗り物ごと。[jp.kjtr.drivelogger.record.Vehicle.smoothAccel]）。 */
data class SmoothConfig(
    val accel: Double = 2.0,
    /** これより速く跳んだ点は外れ値 [m/s]。 */
    val maxSpeedMps: Double = 70.0,
    /** 精度（Android の accuracy＝68% の半径）から 1 軸の σ への割り算。 */
    val accuracyToSigma: Double = 1.5,
    val minSigmaM: Double = 1.5,
    /** 精度の無い点の σ [m]。 */
    val defaultSigmaM: Double = 8.0,
    /** 予測からの外れ（2 軸のマハラノビス距離²）がこれを超えた点は使わない。 */
    val gateChi2: Double = 20.0,
    /** 外れが続いたら（本当に動いた・測位し直した）そこからやり直す回数。 */
    val resetAfterRejects: Int = 3,
    /** これより時間があいたら別の区間としてやり直す [ms]。 */
    val resetGapMs: Long = 30_000,
)

/**
 * 軌跡の後処理のなめらかさ（純ロジック。単体テスト: TrackSmootherTest）。0.8.0〜 DESIGN.md §4.8。
 *
 * 等速モデル（位置・速度、東と北は別々）のカルマンフィルタで前から流し、RTS（Rauch–Tung–Striebel）で後ろから戻して両方向の推定にする。
 * - 観測の雑音は各点の精度（accuracy / 1.5、最低 1.5 m）→ 精度の悪い点・推測航法の点は弱く効く。
 * - 外れ値: 前の点から [SmoothConfig.maxSpeedMps] を超える速さで跳んだ点・予測から大きく外れた点（マハラノビス距離² > 20）は使わない
 *   （その時刻の位置は前後から推定した値になる）。外れが 3 回続いたら、そこから測り直す（トンネルを抜けた・本当に動いた）。
 * - 加速度の雑音 [SmoothConfig.accel] が大きいほど角（交差点）に追従し、小さいほどなめらか。乗り物ごとに決める（車 2.0・自転車 1.2・徒歩 0.6 m/s²）。
 * - [SmoothConfig.resetGapMs] より時間があいたらやり直す。点の数・時刻は変えない（位置だけを置き換える）。
 *
 * [Forward] は記録中の線用の前向きだけのフィルタ（遅れが出にくい）。
 */
object TrackSmoother {
    /** 1 点（[accuracyM] が null なら [SmoothConfig.defaultSigmaM]）。 */
    data class In(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

    /** なめらかにした位置（[lat]/[lon] は入力と同じ数・同じ順）。[rejected] = 外れ値として使わなかった点。 */
    class Out(val lat: DoubleArray, val lon: DoubleArray, val rejected: BooleanArray)

    fun smooth(points: List<In>, cfg: SmoothConfig = SmoothConfig()): Out {
        val n = points.size
        val outLat = DoubleArray(n) { points[it].lat }
        val outLon = DoubleArray(n) { points[it].lon }
        val rejected = BooleanArray(n)
        if (n < 3) return Out(outLat, outLon, rejected)
        // 時間があいたところで分けて、それぞれを流す
        var start = 0
        for (i in 1..n) {
            if (i == n || points[i].timeMs - points[i - 1].timeMs > cfg.resetGapMs || points[i].timeMs < points[i - 1].timeMs) {
                if (i - start >= 3) run(points, start, i, cfg, outLat, outLon, rejected)
                start = i
            }
        }
        return Out(outLat, outLon, rejected)
    }

    /** [from] 以上 [to] 未満を 1 本としてなめらかにする（外れが続いてやり直すところでもさらに分ける）。 */
    private fun run(points: List<In>, from: Int, to: Int, cfg: SmoothConfig, outLat: DoubleArray, outLon: DoubleArray, rejected: BooleanArray) {
        val lat0 = points[from].lat
        val lon0 = points[from].lon
        val mx = Geo.METERS_PER_DEG_LAT * cos(lat0 * PI / 180)
        val my = Geo.METERS_PER_DEG_LAT
        val m = to - from
        val zx = DoubleArray(m) { (points[from + it].lon - lon0) * mx }
        val zy = DoubleArray(m) { (points[from + it].lat - lat0) * my }
        val r = DoubleArray(m) { i ->
            val a = points[from + i].accuracyM?.toDouble()?.takeIf { it > 0 }
            val s = if (a == null) cfg.defaultSigmaM else max(a / cfg.accuracyToSigma, cfg.minSigmaM)
            s * s
        }
        // 前向き: 予測（xp, Pp）と更新後（xf, Pf）を東・北それぞれで保存
        val ax = Axis(m)
        val ay = Axis(m)
        val use = BooleanArray(m)
        var pieceStart = 0
        var rejects = 0
        var streakStart = 0
        var lastUsed = -1
        val q = cfg.accel * cfg.accel
        var i = 0
        while (i < m) {
            val first = i == pieceStart
            if (first) {
                ax.init(i, zx[i], r[i])
                ay.init(i, zy[i], r[i])
                use[i] = true
                lastUsed = i
                rejects = 0
                i++
                continue
            }
            val dt = (points[from + i].timeMs - points[from + i - 1].timeMs) / 1000.0
            ax.predict(i, dt, q)
            ay.predict(i, dt, q)
            // 外れ値: 最後に使った点からの速さ・予測からの外れ
            val dtUsed = (points[from + i].timeMs - points[from + lastUsed].timeMs) / 1000.0
            val jump = dtUsed > 0 && hypot(zx[i] - zx[lastUsed], zy[i] - zy[lastUsed]) / dtUsed > cfg.maxSpeedMps
            val d2 = ax.innovation2(i, zx[i], r[i]) + ay.innovation2(i, zy[i], r[i])
            val enoughHistory = i - pieceStart >= 3
            if (jump || (enoughHistory && d2 > cfg.gateChi2)) {
                if (rejects == 0) streakStart = i
                rejects++
                if (rejects >= cfg.resetAfterRejects && !jump) {
                    // 本当に動いた（外れが続いた）: 外れ始めた点から別の 1 本としてやり直す（そこまでを後ろから戻す）
                    backward(ax, ay, use, pieceStart, streakStart, points, from, outLat, outLon, rejected, lat0, lon0, mx, my)
                    pieceStart = streakStart
                    i = streakStart
                    continue
                }
                ax.skip(i)
                ay.skip(i)
                use[i] = false
            } else {
                rejects = 0
                ax.update(i, zx[i], r[i])
                ay.update(i, zy[i], r[i])
                use[i] = true
                lastUsed = i
            }
            i++
        }
        backward(ax, ay, use, pieceStart, m, points, from, outLat, outLon, rejected, lat0, lon0, mx, my)
    }

    private fun backward(
        ax: Axis, ay: Axis, use: BooleanArray, a: Int, b: Int, points: List<In>, from: Int,
        outLat: DoubleArray, outLon: DoubleArray, rejected: BooleanArray, lat0: Double, lon0: Double, mx: Double, my: Double,
    ) {
        if (b - a <= 0) return
        ax.rts(a, b, points, from)
        ay.rts(a, b, points, from)
        for (k in a until b) {
            outLon[from + k] = lon0 + ax.sp[k] / mx
            outLat[from + k] = lat0 + ay.sp[k] / my
            rejected[from + k] = !use[k]
        }
    }

    /** 1 軸（位置 p・速度 v）の等速モデル。 */
    private class Axis(m: Int) {
        // 予測
        val pp = DoubleArray(m); val pv = DoubleArray(m)
        val P00p = DoubleArray(m); val P01p = DoubleArray(m); val P11p = DoubleArray(m)
        // 更新後
        val fp = DoubleArray(m); val fv = DoubleArray(m)
        val P00 = DoubleArray(m); val P01 = DoubleArray(m); val P11 = DoubleArray(m)
        // 両方向
        val sp = DoubleArray(m); val sv = DoubleArray(m)
        val dts = DoubleArray(m)

        fun init(i: Int, z: Double, r: Double) {
            pp[i] = z; pv[i] = 0.0
            P00p[i] = r; P01p[i] = 0.0; P11p[i] = INIT_V_VAR
            fp[i] = z; fv[i] = 0.0
            P00[i] = r; P01[i] = 0.0; P11[i] = INIT_V_VAR
            dts[i] = 0.0
        }

        fun predict(i: Int, dt: Double, q: Double) {
            val j = i - 1
            dts[i] = dt
            pp[i] = fp[j] + fv[j] * dt
            pv[i] = fv[j]
            // P = F P Fᵀ + Q（Q = q·[dt³/3, dt²/2; dt²/2, dt]）
            val a = P00[j]; val b = P01[j]; val c = P11[j]
            P00p[i] = a + 2 * dt * b + dt * dt * c + q * dt * dt * dt / 3
            P01p[i] = b + dt * c + q * dt * dt / 2
            P11p[i] = c + q * dt
        }

        fun innovation2(i: Int, z: Double, r: Double): Double {
            val y = z - pp[i]
            return y * y / (P00p[i] + r)
        }

        fun update(i: Int, z: Double, r: Double) {
            val s = P00p[i] + r
            val k0 = P00p[i] / s
            val k1 = P01p[i] / s
            val y = z - pp[i]
            fp[i] = pp[i] + k0 * y
            fv[i] = pv[i] + k1 * y
            P00[i] = (1 - k0) * P00p[i]
            P01[i] = (1 - k0) * P01p[i]
            P11[i] = P11p[i] - k1 * P01p[i]
        }

        fun skip(i: Int) {
            fp[i] = pp[i]; fv[i] = pv[i]
            P00[i] = P00p[i]; P01[i] = P01p[i]; P11[i] = P11p[i]
        }

        /** RTS で [a] 以上 [b] 未満を後ろから戻す。 */
        @Suppress("UNUSED_PARAMETER")
        fun rts(a: Int, b: Int, points: List<In>, from: Int) {
            val last = b - 1
            sp[last] = fp[last]; sv[last] = fv[last]
            var s00 = P00[last]; var s01 = P01[last]; var s11 = P11[last]
            for (k in last - 1 downTo a) {
                val dt = dts[k + 1]
                // C = Pf Fᵀ Pp⁻¹（2×2）
                val f00 = P00[k]; val f01 = P01[k]; val f11 = P11[k]
                // Pf Fᵀ = [[f00 + dt f01, f01], [f01 + dt f11, f11]]
                val m00 = f00 + dt * f01; val m01 = f01
                val m10 = f01 + dt * f11; val m11 = f11
                val p00 = P00p[k + 1]; val p01 = P01p[k + 1]; val p11 = P11p[k + 1]
                val det = p00 * p11 - p01 * p01
                if (det <= 1e-12) {
                    sp[k] = fp[k]; sv[k] = fv[k]
                    s00 = f00; s01 = f01; s11 = f11
                    continue
                }
                val i00 = p11 / det; val i01 = -p01 / det; val i11 = p00 / det
                val c00 = m00 * i00 + m01 * i01; val c01 = m00 * i01 + m01 * i11
                val c10 = m10 * i00 + m11 * i01; val c11 = m10 * i01 + m11 * i11
                val dp = sp[k + 1] - pp[k + 1]
                val dv = sv[k + 1] - pv[k + 1]
                sp[k] = fp[k] + c00 * dp + c01 * dv
                sv[k] = fv[k] + c10 * dp + c11 * dv
                // Ps = Pf + C (Ps' − Pp') Cᵀ
                val e00 = s00 - p00; val e01 = s01 - p01; val e11 = s11 - p11
                val t00 = c00 * e00 + c01 * e01; val t01 = c00 * e01 + c01 * e11
                val t10 = c10 * e00 + c11 * e01; val t11 = c10 * e01 + c11 * e11
                s00 = f00 + t00 * c00 + t01 * c01
                s01 = f01 + t00 * c10 + t01 * c11
                s11 = f11 + t10 * c10 + t11 * c11
            }
        }

        companion object {
            /** 最初の速度の分散（[m/s]²。分からないので大きく）。 */
            const val INIT_V_VAR = 400.0
        }
    }

    /**
     * 記録中の線用の前向きだけのフィルタ（1 点ずつ。後ろからは戻さない＝遅れが少ない）。外れ値は使わず予測の位置を返す。
     */
    class Forward(private val cfg: SmoothConfig = SmoothConfig()) {
        private var lat0 = Double.NaN
        private var lon0 = 0.0
        private var mx = 0.0
        private var lastT = 0L
        private var px = 0.0; private var vx = 0.0
        private var py = 0.0; private var vy = 0.0
        private var a00 = 0.0; private var a01 = 0.0; private var a11 = 0.0
        private var b00 = 0.0; private var b01 = 0.0; private var b11 = 0.0
        private var n = 0
        private var rejects = 0

        fun reset() {
            lat0 = Double.NaN
            n = 0
            rejects = 0
        }

        /** 1 点を入れて、なめらかにした位置（緯度・経度）を返す。 */
        fun add(p: In): LatLon {
            val a = p.accuracyM?.toDouble()?.takeIf { it > 0 }
            val sig = if (a == null) cfg.defaultSigmaM else max(a / cfg.accuracyToSigma, cfg.minSigmaM)
            val r = sig * sig
            if (lat0.isNaN() || p.timeMs - lastT > cfg.resetGapMs || p.timeMs < lastT) {
                lat0 = p.lat; lon0 = p.lon
                mx = Geo.METERS_PER_DEG_LAT * cos(lat0 * PI / 180)
                px = 0.0; py = 0.0; vx = 0.0; vy = 0.0
                a00 = r; a01 = 0.0; a11 = Axis.INIT_V_VAR
                b00 = r; b01 = 0.0; b11 = Axis.INIT_V_VAR
                lastT = p.timeMs
                n = 1
                rejects = 0
                return LatLon(p.lat, p.lon)
            }
            val dt = (p.timeMs - lastT) / 1000.0
            lastT = p.timeMs
            val q = cfg.accel * cfg.accel
            // 予測
            px += vx * dt; py += vy * dt
            val na00 = a00 + 2 * dt * a01 + dt * dt * a11 + q * dt * dt * dt / 3
            val na01 = a01 + dt * a11 + q * dt * dt / 2
            val na11 = a11 + q * dt
            val nb00 = b00 + 2 * dt * b01 + dt * dt * b11 + q * dt * dt * dt / 3
            val nb01 = b01 + dt * b11 + q * dt * dt / 2
            val nb11 = b11 + q * dt
            a00 = na00; a01 = na01; a11 = na11
            b00 = nb00; b01 = nb01; b11 = nb11
            val zx = (p.lon - lon0) * mx
            val zy = (p.lat - lat0) * Geo.METERS_PER_DEG_LAT
            val yx = zx - px
            val yy = zy - py
            val d2 = yx * yx / (a00 + r) + yy * yy / (b00 + r)
            if (n >= 3 && d2 > cfg.gateChi2) {
                if (++rejects >= cfg.resetAfterRejects) {
                    lat0 = Double.NaN
                    return add(p)
                }
                return LatLon(lat0 + py / Geo.METERS_PER_DEG_LAT, lon0 + px / mx)
            }
            rejects = 0
            n++
            val sx = a00 + r
            val kx0 = a00 / sx; val kx1 = a01 / sx
            px += kx0 * yx; vx += kx1 * yx
            a11 -= kx1 * a01; a01 *= (1 - kx0); a00 *= (1 - kx0)
            val sy = b00 + r
            val ky0 = b00 / sy; val ky1 = b01 / sy
            py += ky0 * yy; vy += ky1 * yy
            b11 -= ky1 * b01; b01 *= (1 - ky0); b00 *= (1 - ky0)
            return LatLon(lat0 + py / Geo.METERS_PER_DEG_LAT, lon0 + px / mx)
        }
    }
}
