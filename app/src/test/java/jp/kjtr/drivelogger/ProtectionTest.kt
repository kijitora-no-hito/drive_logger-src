package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.ImpactDetector
import jp.kjtr.drivelogger.dashcam.ProtectTargets
import jp.kjtr.drivelogger.dashcam.ProtectionPlanner
import jp.kjtr.drivelogger.dashcam.estimateSegmentBytes
import jp.kjtr.drivelogger.dashcam.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTest {
    private val seg = 180_000L

    @Test
    fun earlyInSegmentProtectsCurrentAndPrevious() {
        assertEquals(ProtectTargets(current = true, previous = true, next = false), ProtectionPlanner.targets(eventAt = 10_000, currentStartedAt = 0, segmentMs = seg))
    }

    @Test
    fun nearTheEndAlsoProtectsNext() {
        // 残り 20 秒 < 30 秒
        assertEquals(ProtectTargets(true, true, true), ProtectionPlanner.targets(eventAt = 160_000, currentStartedAt = 0, segmentMs = seg))
        // 残りちょうど 30 秒は次まで要らない
        assertEquals(ProtectTargets(true, true, false), ProtectionPlanner.targets(eventAt = 150_000, currentStartedAt = 0, segmentMs = seg))
    }

    @Test
    fun betweenSegmentsProtectsPreviousAndNext() {
        assertEquals(ProtectTargets(current = false, previous = true, next = true), ProtectionPlanner.targets(5_000, null, seg))
    }

    @Test
    fun segmentEstimate() {
        // 1080p30 = 12 Mbps → 1 分 = 90 MB × 1.1 = 99 MB
        assertEquals((12_000_000L / 8.0 * 60 * 1.1).toLong(), estimateSegmentBytes(VideoQuality.FHD, 30, 60_000, audio = false))
        assertTrue(estimateSegmentBytes(VideoQuality.FHD, 60, 60_000, false) > estimateSegmentBytes(VideoQuality.FHD, 30, 60_000, false))
    }
}

class ImpactDetectorTest {
    private val g = ImpactDetector.STANDARD_GRAVITY

    /** 20 ms ごと（50 Hz）に [n] 回、同じ値を流す。最初の衝撃を返す。 */
    private fun ImpactDetector.run(from: Long, n: Int, ax: Double, ay: Double, az: Double): ImpactDetector.Impact? {
        var hit: ImpactDetector.Impact? = null
        for (i in 0 until n) {
            val r = feed(from + i * 20L, ax, ay, az)
            if (hit == null) hit = r
        }
        return hit
    }

    @Test
    fun gravityAloneInAnyOrientationDoesNotTrigger() {
        val d = ImpactDetector(thresholdG = 1.2)
        assertNull(d.run(0, 500, 0.0, g, 0.0))
        val d2 = ImpactDetector(thresholdG = 1.2)
        assertNull(d2.run(0, 500, g * 0.6, 0.0, g * 0.8))
    }

    /** [ms] の間（20 ms ごと）、上向きに [extraG] G の衝撃（重力に足す）。最初の衝撃を返す。 */
    private fun ImpactDetector.pulse(from: Long, ms: Long, extraG: Double): ImpactDetector.Impact? = run(from, (ms / 20).toInt(), 0.0, g, extraG * g)

    @Test
    fun sustainedPulseAboveThresholdTriggersWithPeak() {
        val d = ImpactDetector(thresholdG = 1.8)
        assertNull(d.run(0, 250, 0.0, g, 0.0)) // 5 秒（暖機 3 秒を過ぎる）
        // 衝突・急停止相当: 2.5 G が 100 ms
        val hit = d.pulse(5_000, 100, 2.5)
        assertNotNull(hit)
        assertTrue("peak ${hit!!.peakG}", hit.peakG in 2.2..2.6)
    }

    /** 0.8.0〜: 1〜2 サンプル（20〜40 ms 未満）のトゲは数えない（段差・小石）。 */
    @Test
    fun shortSpikeDoesNotTrigger() {
        val d = ImpactDetector(thresholdG = 1.8)
        d.run(0, 250, 0.0, g, 0.0)
        assertNull(d.feed(5_000, 0.0, g, 3.5 * g))
        assertNull(d.feed(5_020, 0.0, g, 3.0 * g))
        assertNull(d.run(5_040, 50, 0.0, g, 0.0))
    }

    @Test
    fun belowThresholdDoesNotTrigger() {
        val d = ImpactDetector(thresholdG = 1.8)
        d.run(0, 250, 0.0, g, 0.0)
        assertNull(d.pulse(5_000, 200, 1.5))
    }

    @Test
    fun warmUpIgnoresEarlySpikes() {
        val d = ImpactDetector(thresholdG = 1.2)
        d.run(0, 50, 0.0, g, 0.0) // 1 秒
        assertNull(d.pulse(1_000, 100, 3.0))
    }

    @Test
    fun cooldownSuppressesRepeatedTriggers() {
        val d = ImpactDetector(thresholdG = 1.2)
        d.run(0, 250, 0.0, g, 0.0)
        assertNotNull(d.pulse(5_000, 100, 3.0))
        // 揺れ戻し・続く衝撃（10 秒以内）は数えない
        assertNull(d.run(5_100, 5, 0.0, g, -3 * g))
        assertNull(d.run(5_200, 200, 0.0, g, 0.0))
        assertNull(d.pulse(9_400, 100, 3.0))
        // 10 秒過ぎたらまた数える
        d.run(9_500, 300, 0.0, g, 0.0)
        assertNotNull(d.pulse(16_000, 100, 3.0))
    }

    @Test
    fun offDetectsNothingButReportsG() {
        val d = ImpactDetector(thresholdG = null)
        d.run(0, 250, 0.0, g, 0.0)
        assertNull(d.pulse(5_000, 100, 4.0))
        assertTrue(d.currentG > 3.0)
    }

    /** 自転車の荒れた路面（振動 RMS 約 0.4 G＋ときどき 2.5〜3.5 G の 20 ms の段差）を 60 秒。 */
    private fun bumpyRide(d: ImpactDetector, from: Long, seconds: Int, seed: Long): Int {
        val rnd = java.util.Random(seed)
        var hits = 0
        var t = from
        val end = from + seconds * 1000L
        while (t < end) {
            var a = rnd.nextGaussian() * 0.4
            if (rnd.nextInt(100) == 0) a += 2.5 + rnd.nextDouble()
            if (d.feed(t, rnd.nextGaussian() * 0.2 * g, g, a * g) != null) hits++
            t += 20
        }
        return hits
    }

    /** 0.8.0〜 乗り物に合わせた検知: 自転車の段差の振動では検知せず、転倒・衝突相当（4.5 G が 120 ms）は検知する（しきい値は 1.8 × 1.6 ＋ 振動 0.46 × 2 ≈ 3.8 G）。 */
    @Test
    fun bicycleVibrationIsIgnoredButCrashIsDetected() {
        val bike = jp.kjtr.drivelogger.record.Vehicle.BICYCLE
        val med = jp.kjtr.drivelogger.dashcam.ImpactSensitivity.MEDIUM.thresholdG!!
        // 0.7.0 の検知（1 サンプル・振動の分なし）では何度も検知していた
        val old = ImpactDetector(thresholdG = med, minDurationMs = 0, vibrationK = 0.0)
        old.run(0, 250, 0.0, g, 0.0)
        val oldHits = bumpyRide(old, 5_000, 60, 3)
        println("bicycle 60 s: 0.7.0 hits $oldHits")
        assertTrue(oldHits >= 3)
        val d = ImpactDetector(thresholdG = med * bike.impactScale, minDurationMs = bike.impactMinMs, vibrationK = bike.impactVibrationK)
        d.run(0, 250, 0.0, g, 0.0)
        assertEquals(0, bumpyRide(d, 5_000, 60, 3))
        println("bicycle: vibration %.2f G → threshold %.2f G".format(d.vibrationG, d.effectiveThresholdG))
        assertNotNull(d.pulse(65_000, 120, 4.5))
    }

    /** 車: 穴・段差のトゲ（3 G・20 ms）は数えず、急停止・衝突相当（2.5 G・80 ms）は検知する。普段の振動（0.1 G）でしきい値はほとんど上がらない。 */
    @Test
    fun carPotholeIsIgnoredButCollisionIsDetected() {
        val car = jp.kjtr.drivelogger.record.Vehicle.CAR
        val d = ImpactDetector(thresholdG = 1.8 * car.impactScale, minDurationMs = car.impactMinMs, vibrationK = car.impactVibrationK)
        val rnd = java.util.Random(1)
        var t = 0L
        repeat(1500) { d.feed(t, 0.0, g, rnd.nextGaussian() * 0.1 * g); t += 20 }
        assertTrue("threshold ${d.effectiveThresholdG}", d.effectiveThresholdG!! < 2.15)
        assertNull(d.feed(t, 0.0, g, 3.0 * g))
        t += 20
        assertNull(d.run(t, 100, 0.0, g, 0.0))
        t += 2000
        assertNotNull(d.pulse(t, 80, 2.5))
    }

    /** 徒歩は検知しない（しきい値なし）。 */
    @Test
    fun walkDoesNotDetect() {
        assertTrue(!jp.kjtr.drivelogger.record.Vehicle.WALK.impactEnabled)
    }

    @Test
    fun sustainedTiltIsAbsorbedByGravityEstimate() {
        // ホルダーの向きがゆっくり変わっても（重力の向きが変わる）検知しない
        val d = ImpactDetector(thresholdG = 1.2)
        d.run(0, 250, 0.0, g, 0.0)
        var hit: ImpactDetector.Impact? = null
        for (i in 0..500) {
            val a = Math.toRadians(90.0 * i / 500)
            val r = d.feed(5_000 + i * 20L, g * kotlin.math.sin(a), g * kotlin.math.cos(a), 0.0)
            if (r != null) hit = r
        }
        assertNull(hit)
    }
}
