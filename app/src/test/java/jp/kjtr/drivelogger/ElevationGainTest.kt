package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.geo.AscentCounter
import jp.kjtr.drivelogger.stats.ElevationGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 獲得標高（0.8.0）。GPS の高度の雑音（白色 σ 5 m＋ゆっくり動くずれ σ 4 m・τ 60 s、ときどき 30 m の跳び）を入れた 1 Hz の点で、
 * 平地はほぼ 0、坂は正しく増えること、ネットワーク位置・精度の悪い点・推測/停車中の点を使わないことを確かめる。
 */
class ElevationGainTest {
    /** [speed] [m/s] で北へ進む（ならしの窓は距離でも区切る）。 */
    private fun noisy(seconds: Int, seed: Long, speed: Double = 1.3, truth: (Int) -> Double): List<TripPointEntity> {
        val rnd = Random(seed)
        var wander = 0.0
        val a = exp(-1.0 / 60.0)
        val q = 4.0 * sqrt(1 - a * a)
        return (0 until seconds).map { s ->
            wander = a * wander + q * rnd.nextGaussian()
            var e = truth(s) + wander + rnd.nextGaussian() * 5.0
            if (rnd.nextInt(200) == 0) e += 30.0
            point(s.toLong() + 1, s * 1000L, e, altAcc = 8f, lat = 35.76 + s * speed / 111_320.0)
        }
    }

    private fun point(
        id: Long, time: Long, ele: Double?, altAcc: Float? = null, acc: Float = 5f,
        source: Int = TripPointEntity.SOURCE_GPS, provider: Int = TripPointEntity.PROVIDER_GPS, segment: Int = 0, lat: Double = 35.76,
    ) = TripPointEntity(
        id = id, tripId = 1, segment = segment, time = time, lat = lat, lon = 139.63, ele = ele, accuracy = acc,
        deviceSpeed = 1.3f, speed = 1.3f, bearing = null, source = source, altAccuracy = altAcc, provider = provider,
    )

    private fun oldAscent(pts: List<TripPointEntity>): Double = AscentCounter().also { c -> pts.forEach { p -> p.ele?.let(c::add) } }.ascentM

    @Test
    fun flatGroundWithGpsNoiseIsNearZero() {
        for (seed in 1L..5L) {
            val pts = noisy(3600, seed) { 40.0 }
            val gain = ElevationGain.of(pts)
            println("flat 1 h seed $seed: gain %.1f m (0.7.0 の数え方 %.1f m)".format(gain, oldAscent(pts)))
            assertTrue("flat gain $gain", gain < 25.0)
            // 車（54 km/h。窓が距離で ±20 秒に狭まる）
            val car = ElevationGain.of(noisy(3600, seed, speed = 15.0) { 40.0 })
            println("flat 1 h by car seed $seed: gain %.1f m".format(car))
            assertTrue("flat by car $car", car < 80.0)
        }
    }

    @Test
    fun hillIsCounted() {
        // 10 分平ら → 20 分で 100 m 登る → 10 分平ら → 20 分で 100 m 下る
        for (seed in 1L..3L) {
            val pts = noisy(3600, seed) { s ->
                when {
                    s < 600 -> 40.0
                    s < 1800 -> 40.0 + 100.0 * (s - 600) / 1200
                    s < 2400 -> 140.0
                    else -> 140.0 - 100.0 * (s - 2400) / 1200
                }
            }
            val gain = ElevationGain.of(pts)
            println("hill +100 m seed $seed: gain %.1f m".format(gain))
            assertEquals(100.0, gain, 15.0)
        }
        // 雑音の無い坂（DEM のような値）: 60 m 登る → 最後の 10 m 未満の残りは数えない（ヒステリシス）
        val clean = (0 until 600).map { s -> point(s + 1L, s * 1000L, 10.0 + s * 0.1, altAcc = 3f) }
        assertEquals(59.9, ElevationGain.of(clean), 10.0)
    }

    /** 車の短い坂（54 km/h）: 90 秒で 40 m 登り、1 分平ら、90 秒で下る、を 4 回（雑音あり）→ 160 m 前後（時間だけの窓だと 110 m に減っていた）。 */
    @Test
    fun shortHillsByCar() {
        for (seed in 1L..3L) {
            val pts = noisy(4 * 300, seed, speed = 15.0) { s ->
                val k = s % 300
                when {
                    k < 30 -> 20.0
                    k < 120 -> 20.0 + 40.0 * (k - 30) / 90
                    k < 180 -> 60.0
                    k < 270 -> 60.0 - 40.0 * (k - 180) / 90
                    else -> 20.0
                }
            }
            val gain = ElevationGain.of(pts)
            println("short hills 4 × 40 m seed $seed: gain %.1f m".format(gain))
            assertEquals(160.0, gain, 40.0)
        }
    }

    @Test
    fun badPointsAreNotUsed() {
        // GPS の点は平ら（40 m）、ネットワーク位置は高度の基準が違って 0 m・80 m を行き来する（0.7.0 までは全部数えていた）
        val pts = ArrayList<TripPointEntity>()
        for (s in 0 until 600) {
            val net = s % 30 in 10..14
            pts.add(
                if (net) point(s + 1L, s * 1000L, if (s % 60 < 30) 0.0 else 80.0, acc = 25f, provider = TripPointEntity.PROVIDER_NETWORK)
                else point(s + 1L, s * 1000L, 40.0, altAcc = 6f),
            )
        }
        println("network altitude: 0.7.0 の数え方 %.1f m".format(oldAscent(pts)))
        assertTrue(oldAscent(pts) > 100)
        assertEquals(0.0, ElevationGain.of(pts), 0.01)
        // 使わない点
        assertFalse(ElevationGain.usable(point(1, 0, 50.0, altAcc = 30f)))
        assertFalse(ElevationGain.usable(point(1, 0, 50.0, source = TripPointEntity.SOURCE_DEAD_RECKONING)))
        assertFalse(ElevationGain.usable(point(1, 0, 50.0, source = TripPointEntity.SOURCE_STOPPED)))
        assertFalse(ElevationGain.usable(point(1, 0, null)))
        // 0.7.0 までの点（高度の精度・出どころなし）は水平の精度で判断
        assertTrue(ElevationGain.usable(point(1, 0, 50.0, altAcc = null, acc = 8f, provider = TripPointEntity.PROVIDER_UNKNOWN)))
        assertFalse(ElevationGain.usable(point(1, 0, 50.0, altAcc = null, acc = 25f, provider = TripPointEntity.PROVIDER_UNKNOWN)))
    }

    @Test
    fun segmentsAreCountedSeparately() {
        // 区間 0 は 40 m、区間 1 は 100 m（駐車の間に登った分は数えない）
        val pts = (0 until 120).map { s -> point(s + 1L, s * 1000L, 40.0, altAcc = 5f, segment = 0) } +
            (0 until 120).map { s -> point(s + 200L, 500_000L + s * 1000L, 100.0, altAcc = 5f, segment = 1) }
        assertEquals(0.0, ElevationGain.of(pts), 0.01)
    }
}
