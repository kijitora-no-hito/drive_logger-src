package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.gauge.VehicleGEstimator
import jp.kjtr.drivelogger.record.DriveTracker
import jp.kjtr.drivelogger.record.FixPipeline
import jp.kjtr.drivelogger.record.GpsImuFusion
import jp.kjtr.drivelogger.record.Sample
import jp.kjtr.drivelogger.record.StopDetector
import jp.kjtr.drivelogger.record.WalkDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 徒歩での融合（0.8.0 の原因調査と対策）。合成データ [WalkSim]（GPS 1 Hz＋ポケット・手持ちの加速度・ジャイロ 50 Hz）を、
 * サービス・セッションと同じ順（ImpactMonitor の重力・前後の加速度・ヨーレート → 融合 → DriveTracker、停車の判定、1.5 秒あいたら推測航法）に流す。
 *
 * - 0.7.0 の流れ（常に融合）: 歩くと記録が生の GPS より悪くなる（重力が前後の加速度に漏れて速さの推定が暴れ、GPS が数秒欠けると推測航法が 100 m 以上飛ぶ）。
 * - 0.8.0 の流れ（[FixPipeline]）: 歩行を見つけたら GPS のまま → 生の GPS と同じ。車の走行は今までどおり融合。
 */
class WalkFusionTest {
    private val lat0 = 35.76
    private val lon0 = 139.63
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)

    class Result(
        val rmsRecorded: Double, val rmsRaw: Double, val maxRecorded: Double, val maxRaw: Double,
        val pathRatioRecorded: Double, val pathRatioRaw: Double, val deadReckoned: Int, val stops: Int, val calibratedShare: Double,
        val maxAbsForward: Double, val walkingFromMs: Long?, val walkingShare: Double,
    )

    /**
     * [newPipeline] = false: 0.7.0 の流れ（融合 ON なら常に融合）。true: 0.8.0 の [FixPipeline]（歩行なら GPS のまま）。
     * 停車で測位を止める（ON）も同じ順に。
     */
    private fun run(sim: WalkSim, newPipeline: Boolean): Result {
        val est = VehicleGEstimator()
        val fusion = GpsImuFusion()
        val pipe = FixPipeline()
        val tracker = DriveTracker()
        val stop = StopDetector()
        var lastYaw: Double? = null
        var gi = 0
        val rec = ArrayList<Triple<Long, Double, Double>>()
        var lastGpsAt = Long.MIN_VALUE
        var lastPointAt = Long.MIN_VALUE
        var dr = 0
        var stops = 0
        var stopped = false
        var holdX = 0.0
        var holdY = 0.0
        var cal = 0
        var maxF = 0.0
        var walkingFrom: Long? = null
        var walkingTicks = 0
        fun record(t: Long, x: Double, y: Double, acc: Float, sp: Float?, br: Float?) {
            val r = tracker.feed(Sample(t, lat0 + y / mLat, lon0 + x / mLon, acc, null, sp, br))
            if (r is DriveTracker.Result.Accepted) {
                rec.add(Triple(t, x, y))
                lastPointAt = t
            }
        }
        for (s in sim.imu) {
            val t = s.tMs
            // ジャイロ（ImpactMonitor.onGyro）
            est.up()?.let { up -> lastYaw = -(s.wx * up[0] + s.wy * up[1] + s.wz * up[2]) }
            // 加速度（ImpactMonitor.onAccel）
            est.onAccel(t, s.ax, s.ay, s.az)
            val reading = est.reading
            val fwd = if (reading.calibrated) reading.longG?.let { it * VehicleGEstimator.G } else null
            if (fwd != null) { cal++; maxF = maxOf(maxF, kotlin.math.abs(fwd)) }
            val lin = est.linearMagnitude(s.ax, s.ay, s.az)
            val norm = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            if (!stopped) {
                if (newPipeline) pipe.onMotion(t, fwd, lastYaw, norm) else fusion.onImu(t, fwd, lastYaw)
            }
            if (pipe.walk.walking) {
                walkingTicks++
                if (walkingFrom == null) walkingFrom = t
            }
            if (stop.onMotion(t, lin, sqrt(s.wx * s.wx + s.wy * s.wy + s.wz * s.wz)) == StopDetector.Event.MOVE && stopped) {
                stopped = false
                if (newPipeline) pipe.hold(t, lat0 + holdY / mLat, lon0 + holdX / mLon) else fusion.hold(t, lat0 + holdY / mLat, lon0 + holdX / mLon)
                lastGpsAt = t
            }
            // GPS
            while (gi < sim.gps.size && sim.gps[gi].tMs <= t) {
                val g = sim.gps[gi++]
                if (stopped) continue
                lastGpsAt = g.tMs
                val lat = lat0 + g.y / mLat
                val lon = lon0 + g.x / mLon
                if (newPipeline) {
                    val o = pipe.onFix(g.tMs, Sample(g.tMs, lat, lon, g.accuracy, null, g.speed, g.bearing))
                    record(g.tMs, (o.sample.lon - lon0) * mLon, (o.sample.lat - lat0) * mLat, o.sample.accuracyM ?: 5f, o.sample.speedMps, o.sample.bearingDeg)
                } else {
                    val f = fusion.onGps(g.tMs, lat, lon, g.accuracy, g.speed, g.bearing)
                    record(g.tMs, (f.lon - lon0) * mLon, (f.lat - lat0) * mLat, f.sigmaM.toFloat(), f.speedMps.toFloat(), f.headingDeg?.toFloat())
                }
                stop.onSpeed(g.tMs, tracker.currentSpeedMps)
                val course = if (g.speed > 2f) g.bearing?.toDouble() else tracker.courseDeg
                est.onGps(g.tMs, g.speed.toDouble(), course)
            }
            if (t % 250 == 0L) {
                if (!stopped && lastGpsAt != Long.MIN_VALUE && t - lastGpsAt >= 1_500 && t - lastPointAt >= 1_000) {
                    (if (newPipeline) pipe.estimate(t) else fusion.estimate(t))?.let { e ->
                        dr++
                        record(t, (e.lon - lon0) * mLon, (e.lat - lat0) * mLat, e.sigmaM.toFloat(), e.speedMps.toFloat(), e.headingDeg?.toFloat())
                    }
                }
                if (!stopped && stop.tick(t) == StopDetector.Event.STOP) {
                    tracker.lastAccepted?.let {
                        stopped = true
                        stops++
                        holdX = (it.lon - lon0) * mLon
                        holdY = (it.lat - lat0) * mLat
                    }
                }
            }
        }
        return measure(sim, rec, dr, stops, cal.toDouble() / sim.imu.size, maxF, walkingFrom, walkingTicks.toDouble() / sim.imu.size)
    }

    private fun measure(
        sim: WalkSim, rec: List<Triple<Long, Double, Double>>, dr: Int, stops: Int, calShare: Double, maxF: Double, walkingFrom: Long?, walkingShare: Double,
    ): Result {
        val errs = rec.filter { it.first >= 20_000 }.map { (t, x, y) -> val tr = sim.truthAt(t); hypot(x - tr.x, y - tr.y) }
        val rawErrs = sim.gps.filter { it.tMs >= 20_000 }.map { g -> val tr = sim.truthAt(g.tMs); hypot(g.x - tr.x, g.y - tr.y) }
        fun len(p: List<Pair<Double, Double>>) = p.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        val trueLen = len(sim.truth.filter { it.tMs >= 20_000 }.map { it.x to it.y })
        return Result(
            rms(errs), rms(rawErrs), errs.max(), rawErrs.max(),
            len(rec.filter { it.first >= 20_000 }.map { it.second to it.third }) / trueLen,
            len(sim.gps.filter { it.tMs >= 20_000 }.map { it.x to it.y }) / trueLen,
            dr, stops, calShare, maxF, walkingFrom, walkingShare,
        )
    }

    private fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)

    private fun report(name: String, r: Result) = println(
        ("%s: recorded RMS %.1f m (max %.1f) vs raw GPS RMS %.1f m (max %.1f); path length ×%.2f vs raw ×%.2f; dead-reckoned %d; stops %d; " +
            "forward accel usable %.0f%% (max |a| %.1f m/s²); walking from %s ms (%.0f%%)").format(
            name, r.rmsRecorded, r.maxRecorded, r.rmsRaw, r.maxRaw, r.pathRatioRecorded, r.pathRatioRaw, r.deadReckoned, r.stops,
            r.calibratedShare * 100, r.maxAbsForward, r.walkingFromMs, r.walkingShare * 100,
        ),
    )

    private val gaps = listOf(100_000L..103_000L, 200_000L..204_000L, 300_000L..302_000L)

    /** 原因: 0.7.0 の流れは歩くと生の GPS より悪い。前後の加速度に重力が漏れ（最大でほぼ 1 G）、数秒の欠けで推測航法が数十〜100 m 以上飛ぶ。 */
    @Test
    fun oldFusionMakesWalkingWorseThanRawGps() {
        for (seed in listOf(11L, 12L, 13L)) {
            val plain = run(WalkSim(seed).run(360), newPipeline = false)
            val withGaps = run(WalkSim(seed).run(360, gaps), newPipeline = false)
            report("always fused walk seed $seed", plain)
            report("always fused walk+gaps seed $seed", withGaps)
            // （0.7.0 は低速で GPS に遅れる分もあって欠けが無くても RMS が 1.4〜1.7 倍だった。遅れは 0.8.0 の GpsImuFusion で直したので、ここでは欠けのある方で見る）
            assertTrue("leaked gravity: max |forward accel| ${plain.maxAbsForward}", plain.maxAbsForward > 5.0)
            assertTrue("fused RMS ${withGaps.rmsRecorded} should be worse than raw ${withGaps.rmsRaw}", withGaps.rmsRecorded > withGaps.rmsRaw * 1.5)
            assertTrue("dead reckoning flies off in short gaps: ${withGaps.maxRecorded} m", withGaps.maxRecorded > 50.0)
            assertEquals(0, plain.stops)
        }
    }

    /** 対策: 歩行を数秒で見つけて GPS のまま記録する → 生の GPS と同じ誤差、推測航法の点なし、停車にもならない。 */
    @Test
    fun walkingIsRecordedAsRawGps() {
        for (seed in listOf(11L, 12L, 13L)) {
            for (g in listOf(emptyList(), gaps)) {
                val r = run(WalkSim(seed).run(360, g), newPipeline = true)
                report("0.8.0 walk seed $seed gaps ${g.size}", r)
                assertTrue("walking detected late: ${r.walkingFromMs}", r.walkingFromMs != null && r.walkingFromMs <= 6_000)
                assertTrue("stays walking (${r.walkingShare})", r.walkingShare > 0.97)
                assertTrue("RMS ${r.rmsRecorded} vs raw ${r.rmsRaw}", r.rmsRecorded <= r.rmsRaw * 1.02)
                assertTrue("max ${r.maxRecorded} vs raw ${r.maxRaw}", r.maxRecorded <= r.maxRaw + 0.5)
                assertEquals(0, r.deadReckoned)
                assertEquals(0, r.stops)
            }
        }
    }

    /** 車の走行（ホルダー・道路の振動）は歩行にしない＝今までどおり融合（12 m/s で生の GPS より誤差が小さい）。 */
    @Test
    fun carKeepsFusion() {
        val fast = run(WalkSim(11, speed = 12.0, car = true).run(360), newPipeline = true)
        report("0.8.0 car 12 m/s", fast)
        assertEquals(null, fast.walkingFromMs)
        assertTrue("fused ${fast.rmsRecorded} < raw ${fast.rmsRaw}", fast.rmsRecorded < fast.rmsRaw)
        val slow = run(WalkSim(12, speed = 1.5, car = true).run(240), newPipeline = true)
        report("0.8.0 car 1.5 m/s", slow)
        assertEquals(null, slow.walkingFromMs)
    }

    /** 歩行の判定: 歩いてから乗り物（15 km/h 以上・揺れなし）に乗ると数秒で徒歩をやめる。信号待ちで止まっても徒歩のまま。 */
    @Test
    fun walkDetectorEntersAndLeaves() {
        val d = WalkDetector()
        var t = 0L
        fun step(seconds: Int, walking: Boolean, speed: Double, amp: Double = 2.0) {
            val end = t + seconds * 1000L
            while (t < end) {
                val a = 9.81 + if (walking) amp * kotlin.math.sin(2 * PI * 1.8 * t / 1000.0) else 0.05 * kotlin.math.sin(t * 0.37)
                d.onAccel(t, a)
                if (t % 1000 == 0L) d.onSpeed(t, speed)
                t += 20
            }
        }
        step(2, walking = true, speed = 1.3)
        assertFalse("not yet (needs 3 s of steps)", d.walking)
        step(4, walking = true, speed = 1.3)
        assertTrue(d.walking)
        // 信号待ち（揺れなし・速さ 0）が 60 秒 → 徒歩のまま
        step(60, walking = false, speed = 0.0)
        assertTrue(d.walking)
        // 速く歩いても（7 km/h）徒歩のまま
        step(10, walking = true, speed = 2.0)
        assertTrue(d.walking)
        // 乗り物: 揺れなしで 30 km/h → 3 秒あまりでやめる
        step(5, walking = false, speed = 8.3)
        assertFalse(d.walking)
        // 走っている車の中で歩く揺れのような振動（弱い 0.3）→ 徒歩にしない
        step(30, walking = true, speed = 1.0, amp = 0.3)
        assertFalse(d.walking)
        // 10 km/h 以上で揺れがあっても入らない（自転車など）
        step(10, walking = true, speed = 4.0)
        assertFalse(d.walking)
    }

    /** 融合 OFF・Google の融合測位（FLP）のときは自前の融合をしない（GPS のまま、推測航法なし）。 */
    @Test
    fun externalOrRawModeDoesNotFuse() {
        val p = FixPipeline()
        p.externalFused = true
        val s = Sample(1000, lat0, lon0, 4f, null, 10f, 90f)
        val o = p.onFix(1000, s)
        assertEquals(FixPipeline.Mode.EXTERNAL, p.mode)
        assertEquals(s, o.sample)
        assertEquals(jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_GPS, o.source)
        assertEquals(null, p.estimate(3000))
        p.externalFused = false
        p.fusionEnabled = false
        assertEquals(FixPipeline.Mode.RAW, p.mode)
        assertEquals(s, p.onFix(2000, s.copy(timeMs = 2000)).sample.copy(timeMs = 1000))
        p.fusionEnabled = true
        assertEquals(FixPipeline.Mode.FUSED, p.mode)
        assertEquals(jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_FUSED, p.onFix(3000, s.copy(timeMs = 3000)).source)
    }
}
