package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.BurnInLayout
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.gauge.GaugeMath
import jp.kjtr.drivelogger.gauge.VehicleGEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GaugeMathTest {
    @Test
    fun speedNeedleAngle() {
        assertEquals(135f, GaugeMath.speedAngle(0.0, 180), 1e-4f)
        assertEquals(405f, GaugeMath.speedAngle(180.0, 180), 1e-4f)
        // 真ん中（90 km/h / 180）はまっすぐ上（270° = -90°）
        assertEquals(270f, GaugeMath.speedAngle(90.0, 180), 1e-4f)
        assertEquals(270f, GaugeMath.speedAngle(60.0, 120), 1e-4f)
        assertEquals(270f, GaugeMath.speedAngle(120.0, 240), 1e-4f)
        // 上限を超えたら上限・負や不明は 0
        assertEquals(405f, GaugeMath.speedAngle(300.0, 120), 1e-4f)
        assertEquals(135f, GaugeMath.speedAngle(-5.0, 180), 1e-4f)
        assertEquals(135f, GaugeMath.speedAngle(null, 180), 1e-4f)
        assertEquals(135f, GaugeMath.speedAngle(Double.NaN, 180), 1e-4f)
    }

    @Test
    fun ticks() {
        assertEquals(20, GaugeMath.majorStep(120))
        assertEquals(20, GaugeMath.majorStep(180))
        assertEquals(40, GaugeMath.majorStep(240))
        assertEquals(10, GaugeMath.minorStep(180))
    }

    @Test
    fun polarAndCompass() {
        val (x, y) = GaugeMath.polar(100f, 100f, 50f, 270f)
        assertEquals(100f, x, 1e-3f)
        assertEquals(50f, y, 1e-3f)
        assertEquals(-90f, GaugeMath.bearingToCanvas(0.0), 1e-4f)
        assertEquals(0f, GaugeMath.bearingToCanvas(90.0), 1e-4f)
        assertEquals(-45f, GaugeMath.compassDialRotation(45.0), 1e-4f)
        assertEquals(0f, GaugeMath.compassDialRotation(null), 1e-4f)
    }

    @Test
    fun gDotIsFeltForce() {
        val r = 100f
        // ブレーキ（前後 -0.3G）→ 上
        val (bx, by) = GaugeMath.gDot(-0.3, 0.0, r)
        assertEquals(0f, bx, 1e-3f)
        assertEquals(-50f, by, 1e-3f)
        // 右に曲がる（右向き +0.3G）→ 左
        val (tx, ty) = GaugeMath.gDot(0.0, 0.3, r)
        assertEquals(-50f, tx, 1e-3f)
        assertEquals(0f, ty, 1e-3f)
        // 外にはみ出さない
        val (ox, oy) = GaugeMath.gDot(2.0, 2.0, r)
        assertEquals(92f, sqrt(ox * ox + oy * oy), 1e-2f)
    }
}

class BurnInMapLayoutTest {
    @Test
    fun mapInOtherCornerIsPlain() {
        assertEquals(1280f - 20f - 260f to 720f - 20f - 260f, BurnInLayout.mapOrigin(BurnInPosition.BOTTOM_RIGHT, BurnInPosition.BOTTOM_LEFT, 1280f, 720f, 260f, 20f, 150f))
        assertEquals(20f to 20f, BurnInLayout.mapOrigin(BurnInPosition.TOP_LEFT, BurnInPosition.BOTTOM_LEFT, 1280f, 720f, 260f, 20f, 150f))
    }

    @Test
    fun mapInSameCornerStacks() {
        // 下の隅: 文字の塊の上に
        assertEquals(20f to 720f - 20f - 260f - 150f - 20f, BurnInLayout.mapOrigin(BurnInPosition.BOTTOM_LEFT, BurnInPosition.BOTTOM_LEFT, 1280f, 720f, 260f, 20f, 150f))
        // 上の隅: 文字の塊の下に
        assertEquals(20f to 20f + 150f + 20f, BurnInLayout.mapOrigin(BurnInPosition.TOP_LEFT, BurnInPosition.TOP_LEFT, 1280f, 720f, 260f, 20f, 150f))
        // 入りきらなければ画面の中に収める
        assertEquals(0f, BurnInLayout.mapOrigin(BurnInPosition.BOTTOM_LEFT, BurnInPosition.BOTTOM_LEFT, 400f, 300f, 260f, 20f, 150f).second, 1e-3f)
    }
}

class VehicleGTest {
    private val g = VehicleGEstimator.G

    // 端末の座標での「上」「前」（ホルダーで斜めに付けた端末。直交する単位ベクトル）
    private val up = norm(doubleArrayOf(0.2, 0.9, 0.38))
    private val fwd = norm(cross(up, doubleArrayOf(1.0, 0.0, 0.0)).let { cross(it, up) })
    private val right = cross(fwd, up)

    private fun norm(v: DoubleArray): DoubleArray {
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / m, v[1] / m, v[2] / m)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** [seconds] 秒、加速度（前 [along]・右 [side] m/s²）を 50 Hz で、GPS を 1 Hz で流す。 */
    private fun run(e: VehicleGEstimator, t0: Long, seconds: Int, v0: Double, along: Double, side: Double, course0: Double, turnDegPerS: Double): Long {
        var t = t0
        var v = v0
        var c = course0
        for (s in 0 until seconds) {
            for (i in 0 until 50) {
                t += 20
                val ax = up[0] * g + fwd[0] * along + right[0] * side
                val ay = up[1] * g + fwd[1] * along + right[1] * side
                val az = up[2] * g + fwd[2] * along + right[2] * side
                e.onAccel(t, ax, ay, az)
            }
            v = (v + along).coerceAtLeast(0.0)
            c += turnDegPerS
            e.onGps(t, v, c)
        }
        return t
    }

    @Test
    fun notCalibratedGivesMagnitudeOnly() {
        val e = VehicleGEstimator()
        var t = run(e, 0, 5, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertFalse(e.reading.calibrated)
        assertEquals(0.0, e.reading.totalG!!, 0.01)
        // 止まっている間に重力を覚えたあと、前に 2 m/s²（まだ向きは分からない）→ 大きさだけ
        t = run(e, t, 1, 0.0, 2.0, 0.0, 0.0, 0.0)
        assertTrue(e.reading.totalG!! > 0.15)
    }

    @Test
    fun learnsForwardFromSpeedChange() {
        val e = VehicleGEstimator()
        var t = run(e, 0, 5, 0.0, 0.0, 0.0, 0.0, 0.0)
        // 加速 2 m/s² を 4 秒（GPS の速度が増える）
        t = run(e, t, 4, 0.0, 2.0, 0.0, 0.0, 0.0)
        val r = e.reading
        assertTrue(r.calibrated)
        assertEquals(2.0 / g, r.longG!!, 0.03)
        assertEquals(0.0, r.latG!!, 0.03)
        // ブレーキ -3 m/s² → 前後が負
        t = run(e, t, 2, 8.0, -3.0, 0.0, 0.0, 0.0)
        assertEquals(-3.0 / g, e.reading.longG!!, 0.05)
    }

    @Test
    fun learnsRightFromTurning() {
        val e = VehicleGEstimator()
        var t = run(e, 0, 5, 0.0, 0.0, 0.0, 0.0, 0.0)
        // 15 m/s で右に 10°/s 曲がる → 右向き 15 × 0.1745 ≈ 2.6 m/s²（GPS の速度は変わらない）
        val side = 15.0 * Math.toRadians(10.0)
        t = run(e, t, 4, 15.0, 0.0, side, 0.0, 10.0)
        val r = e.reading
        assertTrue(r.calibrated)
        assertEquals(side / g, r.latG!!, 0.03)
        assertEquals(0.0, r.longG!!, 0.03)
        assertNotNull(r.totalG)
    }

    @Test
    fun resetForgets() {
        val e = VehicleGEstimator()
        var t = run(e, 0, 5, 0.0, 0.0, 0.0, 0.0, 0.0)
        t = run(e, t, 4, 0.0, 2.0, 0.0, 0.0, 0.0)
        assertTrue(e.reading.calibrated)
        e.reset()
        assertFalse(e.reading.calibrated)
        assertEquals(null, e.reading.totalG)
    }
}
