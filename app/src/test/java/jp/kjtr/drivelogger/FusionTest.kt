package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.record.DriveTracker
import jp.kjtr.drivelogger.record.GpsImuFusion
import jp.kjtr.drivelogger.record.Sample
import jp.kjtr.drivelogger.record.StopDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 合成データの走行（真の軌跡＋雑音入りの GPS 1 Hz＋雑音とずれ入りの加速度・ヨーレート 50 Hz）で、
 * 融合が GPS 単独より軌跡・速度の誤差が小さいこと、GPS の欠けを補うこと、長い欠けで打ち切ることを確かめる。
 */
class FusionTest {
    private val lat0 = 35.68
    private val lon0 = 139.76
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(lat0 * PI / 180)

    /** 真の状態（東 x・北 y [m]、速さ、向き [rad]、前後の加速度、ヨーレート（右回り正））。 */
    private data class Truth(val t: Long, val x: Double, val y: Double, val v: Double, val th: Double, val a: Double, val w: Double)

    /** 0–10 s 加速（0→15 m/s）、直進、50–60 s で右に 90° 曲がる、直進、100–110 s で減速。 */
    private fun simulate(seconds: Int): List<Truth> {
        val out = ArrayList<Truth>()
        var x = 0.0
        var y = 0.0
        var v = 0.0
        var th = 0.3
        val dt = 0.02
        var i = 0L
        while (i * 20 <= seconds * 1000L) {
            val t = i * 0.02
            i++
            val a = when {
                t < 10 -> 1.5
                t in 100.0..110.0 -> -1.0
                else -> 0.0
            }
            val w = if (t in 50.0..60.0) (PI / 2) / 10.0 else 0.0
            out.add(Truth((i - 1) * 20, x, y, v, th, a, w))
            x += v * sin(th) * dt
            y += v * cos(th) * dt
            v = (v + a * dt).coerceAtLeast(0.0)
            th += w * dt
        }
        return out
    }

    private class Run(val fusedErr: List<Double>, val rawErr: List<Double>, val fusedSpeedErr: List<Double>, val rawSpeedErr: List<Double>, val drErr: List<Double>, val drNull: Int)

    private fun run(gap: LongRange? = null, seed: Long = 7): Run {
        val rnd = Random(seed)
        val truth = simulate(120)
        val f = GpsImuFusion()
        val fusedErr = ArrayList<Double>()
        val rawErr = ArrayList<Double>()
        val fsErr = ArrayList<Double>()
        val rsErr = ArrayList<Double>()
        val drErr = ArrayList<Double>()
        var drNull = 0
        val accelBias = 0.05
        val yawBias = 0.004
        for (s in truth) {
            // ジャイロは左回りが正 → ヨーレート（右回り正）は符号を反転して渡すのが呼ぶ側の仕事。ここでは右回り正のまま
            f.onImu(s.t, s.a + accelBias + rnd.nextGaussian() * 0.3, s.w + yawBias + rnd.nextGaussian() * 0.01)
            if (s.t % 1000 != 0L) continue
            val inGap = gap != null && s.t in gap
            if (inGap) {
                val e = f.estimate(s.t)
                if (e == null) drNull++ else drErr.add(err(e.lat, e.lon, s))
                continue
            }
            val gx = s.x + rnd.nextGaussian() * 5.0
            val gy = s.y + rnd.nextGaussian() * 5.0
            val gv = (s.v + rnd.nextGaussian() * 0.5).coerceAtLeast(0.0)
            val gb = ((s.th * 180 / PI) + rnd.nextGaussian() * 5.0 + 360) % 360
            val lat = lat0 + gy / mPerDegLat
            val lon = lon0 + gx / mPerDegLon
            val fix = f.onGps(s.t, lat, lon, 7f, gv.toFloat(), if (gv > 1) gb.toFloat() else null)
            if (s.t >= 15_000) {
                fusedErr.add(err(fix.lat, fix.lon, s))
                rawErr.add(hypot(gx - s.x, gy - s.y))
                fsErr.add(kotlin.math.abs(fix.speedMps - s.v))
                rsErr.add(kotlin.math.abs(gv - s.v))
            }
        }
        return Run(fusedErr, rawErr, fsErr, rsErr, drErr, drNull)
    }

    private fun err(lat: Double, lon: Double, s: Truth): Double = hypot((lon - lon0) * mPerDegLon - s.x, (lat - lat0) * mPerDegLat - s.y)

    private fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)

    @Test
    fun fusedTrackAndSpeedAreCloserThanGpsAlone() {
        val r = run()
        val fp = rms(r.fusedErr)
        val gp = rms(r.rawErr)
        val fs = rms(r.fusedSpeedErr)
        val gs = rms(r.rawSpeedErr)
        println("position RMS fused=%.2f m gps=%.2f m, speed RMS fused=%.2f gps=%.2f m/s".format(fp, gp, fs, gs))
        assertTrue("position: fused $fp should be < 0.8 × gps $gp", fp < gp * 0.8)
        assertTrue("speed: fused $fs should be < gps $gs", fs < gs)
    }

    @Test
    fun bridgesShortGpsGap() {
        // 70〜84 s（曲がった後の直進、15 秒）GPS が来ない
        val r = run(gap = 70_000L..84_000L)
        assertEquals(0, r.drNull)
        assertEquals(15, r.drErr.size)
        val worst = r.drErr.max()
        println("dead reckoning 15 s: worst error %.1f m".format(worst))
        assertTrue("dead reckoning error $worst m", worst < 25.0)
    }

    @Test
    fun stopsDeadReckoningAfterLongGap() {
        // 60 秒の欠け: 30 秒を超えたところで打ち切る（null）
        val r = run(gap = 61_000L..120_000L)
        assertTrue("should give up after 30 s (null count ${r.drNull})", r.drNull >= 25)
        assertTrue(r.drErr.size <= 31)
    }

    @Test
    fun withoutImuStillSmoothsButGivesUpSooner() {
        val f = GpsImuFusion()
        val truth = simulate(60)
        val rnd = Random(3)
        var last: Long = 0
        for (s in truth) {
            if (s.t % 1000 != 0L || s.t > 30_000) continue
            val lat = lat0 + (s.y + rnd.nextGaussian() * 3) / mPerDegLat
            val lon = lon0 + (s.x + rnd.nextGaussian() * 3) / mPerDegLon
            f.onGps(s.t, lat, lon, 4f, s.v.toFloat(), null)
            last = s.t
        }
        assertNotNull(f.estimate(last + 3_000))
        // 加速度が無いと 8 秒で打ち切る
        assertNull(f.estimate(last + 9_000))
    }

    @Test
    fun firstFixAfterHoldIsTrustedEvenIfFar() {
        val f = GpsImuFusion()
        f.onGps(0, lat0, lon0, 5f, 0f, null)
        f.hold(1_000, lat0, lon0)
        // 停車中に測位を止めていた間に 200 m 動いていた → 外れ値にせず、その点に合わせる
        val fix = f.onGps(3_000, lat0 + 200 / mPerDegLat, lon0, 5f, 10f, 0f)
        assertEquals(200.0, (fix.lat - lat0) * mPerDegLat, 1.0)
        // 停車の位置からの推定は 0 m（hold の直後）
        val g = GpsImuFusion()
        g.onGps(0, lat0, lon0, 5f, 0f, null)
        g.hold(1_000, lat0 + 50 / mPerDegLat, lon0)
        val e = g.estimate(1_500)!!
        assertEquals(50.0, (e.lat - lat0) * mPerDegLat, 0.5)
        assertEquals(0.0, e.speedMps, 1e-9)
    }

    @Test
    fun followsPositionsWhenDeviceSpeedIsUnusable() {
        // 端末の速度が常に 0（エミュレータの geo fix）・方位なしでも、位置の差から速さを求めて付いていく
        val f = GpsImuFusion()
        var last = f.onGps(0, lat0, lon0, 5f, 0f, null)
        for (i in 1..30) last = f.onGps(i * 1000L, lat0, lon0 + i * 11.0 / mPerDegLon, 5f, 0f, null)
        assertEquals(330.0, (last.lon - lon0) * mPerDegLon, 6.0)
        assertEquals(11.0, last.speedMps, 1.5)
        assertNotNull(last.headingDeg)
        assertEquals(90.0, last.headingDeg!!, 10.0)
    }

    /** エミュレータで実際に届いた測位（間隔がばらつく・速度 0・方位なし、間に推測航法の呼び出し）を流し直す。 */
    @Test
    fun replayEmulatorFixesStaysOnTrack() {
        val seq = listOf(
            7388L to 139.701328333333, 8391L to 139.701451666667, 9960L to null, 10082L to 139.701696666667, 11088L to 139.70182,
            12392L to 139.701943333333, 14160L to null, 15452L to 139.702311666667, 16965L to null, 17164L to 139.702556666667,
            18418L to 139.70268, 19420L to 139.702803333333, 21093L to 139.703048333333, 22104L to 139.703171666667,
            23109L to 139.703416666667, 24110L to 139.703663333333, 25150L to 139.703908333333, 28110L to null,
            29177L to 139.704891666667, 30431L to 139.705138333333, 32036L to null, 34419L to 139.706121666667, 35949L to null,
            36130L to 139.706613333333, 37168L to 139.706858333333, 40094L to null, 40104L to 139.707595, 41127L to 139.707841666667,
            42501L to 139.708086666667, 45115L to 139.708456666667, 46144L to 139.708578333333,
        )
        val lat = 35.6899983333333
        val mLon = 111_320.0 * cos(lat * PI / 180)
        val f = GpsImuFusion()
        var worst = 0.0
        var n = 0
        for ((t, lon) in seq) {
            // 加速度は補正前（null）、ジャイロは 0（エミュレータ）
            f.onImu(t - 20, null, 0.0)
            if (lon == null) {
                f.estimate(t)
                continue
            }
            val fix = f.onGps(t, lat, lon, 5f, 0f, null)
            if (++n > 4) worst = maxOf(worst, kotlin.math.abs(fix.lon - lon) * mLon)
        }
        println("emulator replay: worst lag %.1f m".format(worst))
        assertTrue("fused should follow the fixes (worst $worst m)", worst < 15.0)
    }

    @Test
    fun outlierIsRejected() {
        val f = GpsImuFusion()
        var t = 0L
        repeat(20) {
            f.onGps(t, lat0 + it * 10 / mPerDegLat, lon0, 5f, 10f, 0f)
            t += 1000
        }
        // 300 m 横に跳んだ点 → 捨てて今の推定を返す
        val fix = f.onGps(t, lat0 + 20 * 10 / mPerDegLat, lon0 + 300 / mPerDegLon, 5f, 10f, 0f)
        val east = (fix.lon - lon0) * mPerDegLon
        assertTrue("east $east should stay near 0", kotlin.math.abs(east) < 20)
    }
}

/** GPX の「元の GPS の値」: 融合した点は元の測位に、推測航法・停車中の点は除く、0.2.0 以前の点はそのまま。 */
class RawGpxPointsTest {
    @Test
    fun rawPointsSelection() {
        fun p(id: Long, src: Int, rawLat: Double? = null) = jp.kjtr.drivelogger.data.TripPointEntity(
            id = id, tripId = 1, segment = 0, time = id * 1000, lat = 35.0 + id * 0.001, lon = 139.0, ele = null, accuracy = 3f,
            deviceSpeed = 10f, speed = 10f, bearing = null, rawLat = rawLat, rawLon = rawLat?.let { 139.5 }, rawAccuracy = rawLat?.let { 9f }, rawSpeed = rawLat?.let { 11f },
            source = src,
        )
        val pts = listOf(
            p(1, jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_GPS),
            p(2, jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_FUSED, rawLat = 36.0),
            p(3, jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_DEAD_RECKONING),
            p(4, jp.kjtr.drivelogger.data.TripPointEntity.SOURCE_STOPPED),
        )
        val raw = jp.kjtr.drivelogger.data.TripRepository.rawPoints(pts)
        assertEquals(listOf(1L, 2L), raw.map { it.id })
        assertEquals(35.001, raw[0].lat, 1e-9)
        assertEquals(36.0, raw[1].lat, 1e-9)
        assertEquals(139.5, raw[1].lon, 1e-9)
        assertEquals(9f, raw[1].accuracy!!, 0f)
    }
}

class StopDetectorTest {
    /** 静か＋速さ 0 が 4 秒続いたら停車、加速で解除。 */
    @Test
    fun stopsWhenQuietAndSlowThenMovesOnAcceleration() {
        val d = StopDetector()
        var t = 0L
        var stopAt: Long? = null
        // 0〜10 s: 停まっている（エンジンの小さな振動 0.05 m/s²）
        while (t <= 10_000) {
            d.onMotion(t, 0.05 + (t % 3) * 0.01, 0.01)
            if (t % 1000 == 0L) d.onSpeed(t, 0.2)
            if (t % 250 == 0L && d.tick(t) == StopDetector.Event.STOP) stopAt = t
            t += 20
        }
        assertNotNull(stopAt)
        // 窓 2 秒が埋まってから 4 秒
        assertTrue("stopped at $stopAt", stopAt!! in 5_000..7_000)
        assertTrue(d.stopped)
        // 発進（1.2 m/s²）
        var ev: StopDetector.Event? = null
        repeat(30) {
            t += 20
            ev = ev ?: d.onMotion(t, 1.2, 0.02)
        }
        assertEquals(StopDetector.Event.MOVE, ev)
        assertTrue(!d.stopped)
    }

    @Test
    fun doesNotStopWhileMovingSmoothly() {
        // 一定の速さで滑らかに走る（加速度は静か）→ 速さが 0 でないので停車にしない
        val d = StopDetector()
        var t = 0L
        while (t <= 20_000) {
            d.onMotion(t, 0.05, 0.01)
            if (t % 1000 == 0L) d.onSpeed(t, 16.0)
            if (t % 250 == 0L) assertNull(d.tick(t))
            t += 20
        }
    }

    @Test
    fun doesNotStopWhileShaking() {
        // 速さはほぼ 0 でも揺れている（渋滞でじわじわ動く・乗り降り）→ 停車にしない
        val d = StopDetector()
        var t = 0L
        while (t <= 20_000) {
            d.onMotion(t, if ((t / 200) % 2 == 0L) 0.9 else 0.1, 0.03)
            if (t % 1000 == 0L) d.onSpeed(t, 0.3)
            if (t % 250 == 0L) assertNull(d.tick(t))
            t += 20
        }
    }

    @Test
    fun gpsSpeedAlsoEndsStop() {
        val d = StopDetector()
        var t = 0L
        while (!d.stopped && t < 20_000) {
            d.onMotion(t, 0.02, null)
            if (t % 1000 == 0L) d.onSpeed(t, 0.0)
            d.tick(t)
            t += 20
        }
        assertTrue(d.stopped)
        assertEquals(StopDetector.Event.MOVE, d.onSpeed(t, 3.0))
    }

    /** 停車中は同じ位置のまま時刻だけ進む: 距離は増えず、停車時間に入る。動き出しは停車の位置からの移動。 */
    @Test
    fun stoppedPointsKeepPositionAndAddStillTime() {
        val tr = DriveTracker()
        val lat = 35.0
        var lon = 139.0
        val dLon = 10.0 / (111_320.0 * cos(lat * PI / 180)) // 10 m
        var t = 0L
        repeat(30) {
            tr.feed(Sample(t, lat, lon, 5f, null, 10f, 90f))
            lon += dLon
            t += 1000
        }
        lon -= dLon
        val dist = tr.distanceM
        val still = tr.stillMs
        // 停車（測位を止めている）: 2 秒ごとに同じ位置・速さ 0 の点を 60 秒
        repeat(30) {
            t += 2000
            tr.feed(Sample(t, lat, lon, 3f, null, 0f, null))
        }
        assertEquals(dist, tr.distanceM, 1e-6)
        assertTrue("still ${tr.stillMs - still} ms", tr.stillMs - still >= 55_000)
        assertEquals(0.0, tr.currentSpeedMps, 1e-6)
        // 動き出し: 停車の位置から 10 m 先
        t += 1000
        tr.feed(Sample(t, lat, lon + dLon, 5f, null, 10f, 90f))
        assertEquals(dist + 10.0, tr.distanceM, 0.5)
    }
}
