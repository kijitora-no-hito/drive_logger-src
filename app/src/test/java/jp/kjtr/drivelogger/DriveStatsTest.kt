package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.record.DriveTracker
import jp.kjtr.drivelogger.record.Sample
import jp.kjtr.drivelogger.record.TrackerConfig
import jp.kjtr.drivelogger.stats.DriveAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 車向けの記録の統計（DriveTracker / DriveAnalysis）。 */
class DriveStatsTest {
    private val lat0 = 35.0
    private val mPerDegLat = Geo.METERS_PER_DEG_LAT

    /** 北へ [kmh] で [sec] 秒（1 秒ごと）。端末の速度なし（geo fix と同じ）。 */
    private fun north(startT: Long, startM: Double, kmh: Double, sec: Int): List<Sample> =
        (1..sec).map { i -> Sample(startT + i * 1000L, lat0 + (startM + kmh / 3.6 * i) / mPerDegLat, 139.0, accuracyM = 5f) }

    @Test
    fun highwaySpeedIsKeptButJumpsAreDropped() {
        val tr = DriveTracker()
        tr.feed(Sample(0, lat0, 139.0, 5f))
        // 120 km/h（自転車の 100 km/h の上限なら捨てていた）
        north(0, 0.0, 120.0, 30).forEach { tr.feed(it) }
        assertEquals(1000.0, tr.distanceM, 15.0)
        assertTrue(tr.maxSpeedMps * 3.6 in 110.0..130.0)
        // 1 秒で 1 km 跳ぶ（3600 km/h）点は捨てる
        val r = tr.feed(Sample(31_000, lat0 + 2000.0 / mPerDegLat, 139.0, 5f))
        assertTrue(r is DriveTracker.Result.Rejected)
    }

    @Test
    fun signalStopsCountAsStillTimeWithAutoPauseOff() {
        val tr = DriveTracker() // 自動一時停止 OFF が既定
        tr.feed(Sample(0, lat0, 139.0, 5f))
        north(0, 0.0, 40.0, 60).forEach { tr.feed(it) }
        val d = 40.0 / 3.6 * 60
        // 信号待ち 90 秒（同じ位置）
        (61..150).forEach { tr.feed(Sample(it * 1000L, lat0 + d / mPerDegLat, 139.0, 5f)) }
        north(150_000, d, 40.0, 60).forEach { tr.feed(it) }
        assertEquals(210_000L, tr.movingMs) // 停車も走行時間に入る
        assertTrue("still=${tr.stillMs}", tr.stillMs in 85_000L..96_000L)
        assertTrue(tr.averageRunningSpeedMps * 3.6 in 37.0..42.0)
        assertTrue(tr.averageSpeedMps < tr.averageRunningSpeedMps)
    }

    @Test
    fun autoPauseOnSkipsLongStops() {
        val tr = DriveTracker(TrackerConfig(autoPause = true))
        tr.feed(Sample(0, lat0, 139.0, 5f))
        north(0, 0.0, 40.0, 60).forEach { tr.feed(it) }
        val d = 40.0 / 3.6 * 60
        (61..200).forEach { tr.feed(Sample(it * 1000L, lat0 + d / mPerDegLat, 139.0, 5f)) }
        // 60 秒で一時停止 → 止まっていた 140 秒のうち、ほとんどは数えない
        assertTrue("moving=${tr.movingMs}", tr.movingMs < 75_000L)
    }

    @Test
    fun courseFromPositionsWhenNoDeviceBearing() {
        val tr = DriveTracker()
        tr.feed(Sample(0, lat0, 139.0, 5f))
        north(0, 0.0, 36.0, 5).forEach { tr.feed(it) }
        assertNotNull(tr.courseDeg)
        assertEquals(0.0, tr.courseDeg!!, 1.0)
        // 東へ
        val last = lat0 + 50.0 / mPerDegLat
        (6..10).forEach { i -> tr.feed(Sample(i * 1000L, last, 139.0 + (i - 5) * 10.0 / Geo.metersPerDegLon(lat0), 5f)) }
        assertEquals(90.0, tr.courseDeg!!, 2.0)
    }

    @Test
    fun deviceBearingIsUsedWhenMoving() {
        val tr = DriveTracker()
        tr.feed(Sample(0, lat0, 139.0, 5f, speedMps = 15f, bearingDeg = 200f))
        tr.feed(Sample(1000, lat0 + 15.0 / mPerDegLat, 139.0, 5f, speedMps = 15f, bearingDeg = 200f))
        assertEquals(200.0, tr.courseDeg!!, 1e-6)
    }

    private fun points(samples: List<Sample>, segment: Int = 0) = samples.mapIndexed { i, s ->
        TripPointEntity(id = i.toLong(), tripId = 1, segment = segment, time = s.timeMs, lat = s.lat, lon = s.lon, ele = null, accuracy = s.accuracyM, deviceSpeed = s.speedMps, speed = null, bearing = null)
    }

    @Test
    fun analysisSumsMatchTripAnd10kmhBins() {
        val samples = listOf(Sample(0, lat0, 139.0, 5f)) + north(0, 0.0, 30.0, 120) + north(120_000, 30.0 / 3.6 * 120, 70.0, 300)
        val pts = points(samples)
        val tr = TripRepository.replay(pts, autoPause = false)
        val a = TripRepository.analyze(pts, autoPause = false)
        assertEquals(tr.movingMs, a.movingMs)
        assertEquals(tr.distanceM, a.distanceM, 1e-6)
        val splits = a.splits(5_000.0)
        assertEquals(tr.movingMs, splits.sumOf { it.movingMs })
        assertEquals(tr.distanceM, splits.sumOf { it.distanceM }, 1e-6)
        val h = a.histogram(10.0)
        assertEquals(10.0, h.binKmh, 0.0)
        assertEquals(tr.movingMs, h.totalMs)
        // 30 km/h の区間は 30–40、70 km/h は 70–80 の bin が一番多い
        val top2 = h.bins.sortedByDescending { it.timeMs }.take(2).map { it.fromKmh }.toSet()
        assertEquals(setOf(30.0, 70.0), top2)
    }

    @Test
    fun binBoundaries() {
        assertEquals(4, DriveAnalysis.binIndex(40.0, 10.0))
        assertEquals(3, DriveAnalysis.binIndex(39.999, 10.0))
        assertEquals(0, DriveAnalysis.binIndex(-1.0, 10.0))
    }
}
