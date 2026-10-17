package jp.kjtr.drivelogger.gauge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 端末の加速度センサーを車の向き（前後・左右）にそろえる簡単な補正（純ロジック。単体テスト: VehicleGTest）。
 *
 * - 垂直: GPS の 2 点の間（約 1 秒）の加速度（重力を含む）の平均を、前後どちらの点でも止まっていた（< 1 m/s）ときに重力として強く取り込む。
 *   走っている間はごくゆっくり（約 100 秒）追う。GPS がまだ無いうちはサンプルごとに平均する。
 * - 前向き: GPS の速度の変化（前後の加速度）と、GPS の方位の変化 × 速度（曲がるときの横の加速度）が分かった区間ごとに、
 *   その間の端末の水平の加速度を「前後の加速度 × 水平の加速度」「横の加速度 × 水平の加速度」として足し合わせ、
 *   その向きから車の前・右を求める（新しい手がかりが来るたびに古いものを少しずつ薄める）。
 * - 手がかりが足りないうち（[GReading.calibrated] = false）は水平の大きさだけ出す。
 *
 * 端末をホルダーで付け直したときは、手がかりが新しいものに置き換わるまで少しずれる。
 */
class VehicleGEstimator {
    // 重力（端末の座標、m/s²）。「上」向き（静止時の加速度センサーの値と同じ向き）。
    private var gx = Double.NaN
    private var gy = 0.0
    private var gz = 0.0

    // GPS の 1 区間の間の合計（水平の加速度・生の加速度）
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var ax0 = 0.0
    private var ay0 = 0.0
    private var az0 = 0.0
    private var sn = 0

    // 前向き（F）・右向き（R）の手がかりの合計
    private var fx = 0.0
    private var fy = 0.0
    private var fz = 0.0
    private var rx = 0.0
    private var ry = 0.0
    private var rz = 0.0

    private var gpsSeen = false
    private var lastGpsAt = Long.MIN_VALUE
    private var lastSpeed: Double? = null
    private var lastCourse: Double? = null

    // 出力（少し平滑化）
    private var outLong = 0.0
    private var outLat = 0.0
    private var outTotal = 0.0
    private var hasOut = false

    var reading: GReading = GReading()
        private set

    fun reset() {
        gx = Double.NaN
        sx = 0.0; sy = 0.0; sz = 0.0
        ax0 = 0.0; ay0 = 0.0; az0 = 0.0; sn = 0
        fx = 0.0; fy = 0.0; fz = 0.0
        rx = 0.0; ry = 0.0; rz = 0.0
        gpsSeen = false
        lastGpsAt = Long.MIN_VALUE
        lastSpeed = null
        lastCourse = null
        hasOut = false
        reading = GReading()
    }

    /** 加速度センサー（重力を含む、端末の座標、m/s²）の 1 サンプル。 */
    @Suppress("UNUSED_PARAMETER")
    fun onAccel(timeMs: Long, ax: Double, ay: Double, az: Double) {
        if (gx.isNaN()) {
            gx = ax; gy = ay; gz = az
            return
        }
        if (!gpsSeen) {
            gx += ALPHA_NO_GPS * (ax - gx)
            gy += ALPHA_NO_GPS * (ay - gy)
            gz += ALPHA_NO_GPS * (az - gz)
        }
        val gm = sqrt(gx * gx + gy * gy + gz * gz)
        if (gm < 1.0) return
        val ux = gx / gm
        val uy = gy / gm
        val uz = gz / gm
        // 重力を除いた加速度と、その水平の成分
        val lx = ax - gx
        val ly = ay - gy
        val lz = az - gz
        val up = lx * ux + ly * uy + lz * uz
        val hx = lx - up * ux
        val hy = ly - up * uy
        val hz = lz - up * uz
        sx += hx; sy += hy; sz += hz
        ax0 += ax; ay0 += ay; az0 += az
        sn++

        val total = sqrt(hx * hx + hy * hy + hz * hz) / G
        var cal = false
        var long = 0.0
        var lat = 0.0
        // 前の向き = F + u × R を水平にして正規化
        val cx = fx + (uy * rz - uz * ry)
        val cy = fy + (uz * rx - ux * rz)
        val cz = fz + (ux * ry - uy * rx)
        val cu = cx * ux + cy * uy + cz * uz
        val px = cx - cu * ux
        val py = cy - cu * uy
        val pz = cz - cu * uz
        val pm = sqrt(px * px + py * py + pz * pz)
        if (pm >= CALIBRATED_EVIDENCE) {
            cal = true
            val fwx = px / pm
            val fwy = py / pm
            val fwz = pz / pm
            // 右 = 前 × 上
            val rgx = fwy * uz - fwz * uy
            val rgy = fwz * ux - fwx * uz
            val rgz = fwx * uy - fwy * ux
            long = (hx * fwx + hy * fwy + hz * fwz) / G
            lat = (hx * rgx + hy * rgy + hz * rgz) / G
        }
        if (!hasOut) {
            outLong = long; outLat = lat; outTotal = total
            hasOut = true
        } else {
            outLong += OUT_ALPHA * (long - outLong)
            outLat += OUT_ALPHA * (lat - outLat)
            outTotal += OUT_ALPHA * (total - outTotal)
        }
        reading = if (cal) GReading(outLong, outLat, outTotal, true) else GReading(null, null, outTotal, false)
    }

    /** 重力の向き（端末の座標の「上」の単位ベクトル）。まだ分からなければ null。 */
    fun up(): DoubleArray? {
        if (gx.isNaN()) return null
        val m = sqrt(gx * gx + gy * gy + gz * gz)
        if (m < 1.0) return null
        return doubleArrayOf(gx / m, gy / m, gz / m)
    }

    /** 重力を除いた加速度の大きさ [m/s²]（停車の判定）。 */
    fun linearMagnitude(ax: Double, ay: Double, az: Double): Double {
        if (gx.isNaN()) return 0.0
        val dx = ax - gx
        val dy = ay - gy
        val dz = az - gz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** GPS の 1 点（速度 [speedMps]・進行方向 [courseDeg]、分からなければ null）。 */
    fun onGps(timeMs: Long, speedMps: Double?, courseDeg: Double?) {
        gpsSeen = true
        val prevAt = lastGpsAt
        val prevSpeed = lastSpeed
        val prevCourse = lastCourse
        lastGpsAt = timeMs
        lastSpeed = speedMps
        lastCourse = courseDeg
        val n = sn
        val mx = if (n > 0) sx / n else 0.0
        val my = if (n > 0) sy / n else 0.0
        val mz = if (n > 0) sz / n else 0.0
        val rawX = if (n > 0) ax0 / n else 0.0
        val rawY = if (n > 0) ay0 / n else 0.0
        val rawZ = if (n > 0) az0 / n else 0.0
        sx = 0.0; sy = 0.0; sz = 0.0
        ax0 = 0.0; ay0 = 0.0; az0 = 0.0; sn = 0
        if (n < MIN_SAMPLES || gx.isNaN()) return
        // 重力: 前後とも止まっていた区間の平均を強く、走っている区間はごくゆっくり
        val stopped = speedMps != null && prevSpeed != null && speedMps < STOPPED_MPS && prevSpeed < STOPPED_MPS
        val ga = if (stopped) GRAVITY_STOPPED else GRAVITY_MOVING
        gx += ga * (rawX - gx)
        gy += ga * (rawY - gy)
        gz += ga * (rawZ - gz)

        if (prevAt == Long.MIN_VALUE || speedMps == null || prevSpeed == null) return
        val dt = (timeMs - prevAt) / 1000.0
        if (dt < 0.5 || dt > 3.0) return
        var along = (speedMps - prevSpeed) / dt
        if (abs(along) > MAX_SIGNAL) along = 0.0
        var side = 0.0
        val vMid = (speedMps + prevSpeed) / 2
        if (courseDeg != null && prevCourse != null && vMid >= TURN_MIN_MPS) {
            var d = courseDeg - prevCourse
            while (d > 180) d -= 360
            while (d < -180) d += 360
            side = vMid * (d * PI / 180.0) / dt
            if (abs(side) > MAX_SIGNAL) side = 0.0
        }
        val useAlong = abs(along) >= MIN_SIGNAL
        val useSide = abs(side) >= MIN_SIGNAL
        if (!useAlong && !useSide) return
        fx *= DECAY; fy *= DECAY; fz *= DECAY
        rx *= DECAY; ry *= DECAY; rz *= DECAY
        if (useAlong) {
            fx += along * mx; fy += along * my; fz += along * mz
        }
        if (useSide) {
            rx += side * mx; ry += side * my; rz += side * mz
        }
    }

    companion object {
        const val G = 9.80665
        private const val STOPPED_MPS = 1.0
        private const val ALPHA_NO_GPS = 0.02
        private const val GRAVITY_STOPPED = 0.3
        private const val GRAVITY_MOVING = 0.01
        private const val OUT_ALPHA = 0.15
        private const val MIN_SAMPLES = 5
        private const val TURN_MIN_MPS = 4.0
        /** これより小さい加速度 [m/s²] は手がかりにしない（GPS の速度のゆらぎ）。 */
        private const val MIN_SIGNAL = 0.4
        /** これより大きい加速度 [m/s²] は GPS の跳びとみなして捨てる。 */
        private const val MAX_SIGNAL = 8.0
        private const val DECAY = 0.97
        /** 手がかりの合計がこれ以上で向きが分かったとする。 */
        const val CALIBRATED_EVIDENCE = 4.0
    }
}
