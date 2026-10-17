package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.stats.DriveSummaries
import jp.kjtr.drivelogger.stats.GridCells
import jp.kjtr.drivelogger.stats.SummaryPeriod
import jp.kjtr.drivelogger.stats.TimeRange
import jp.kjtr.drivelogger.stats.TripFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DriveSummaryTest {
    private val jst = TimeZone.getTimeZone("Asia/Tokyo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long = Calendar.getInstance(jst).apply {
        clear(); set(y, m - 1, d, h, min)
    }.timeInMillis

    private fun trip(id: Long, start: Long, minutes: Int, km: Double, maxKmh: Double, cells: Set<Long> = emptySet(), bins: Map<Int, Long> = emptyMap()) =
        TripFacts(id, "t$id", start, start + minutes * 60_000L, km * 1000, minutes * 60_000L, maxKmh / 3.6, ascentM = 10.0, speedBinsMs = bins, cells = cells)

    @Test
    fun periodRanges() {
        val now = at(2026, 10, 13, 14)
        val year = DriveSummaries.range(SummaryPeriod.THIS_YEAR, now, jst)
        assertEquals(at(2026, 1, 1, 0), year.fromMs)
        assertEquals(at(2027, 1, 1, 0), year.toMs)
        val month = DriveSummaries.range(SummaryPeriod.THIS_MONTH, now, jst)
        assertEquals(at(2026, 10, 1, 0), month.fromMs)
        assertEquals(at(2026, 11, 1, 0), month.toMs)
        assertEquals(now - 30L * 86_400_000L, DriveSummaries.range(SummaryPeriod.LAST_30_DAYS, now, jst).fromMs)
        assertEquals(TimeRange(null, null), DriveSummaries.range(SummaryPeriod.ALL, now, jst))
        val custom = TimeRange(at(2026, 9, 1, 0), at(2026, 9, 2, 0))
        assertEquals(custom, DriveSummaries.range(SummaryPeriod.CUSTOM, now, jst, custom))
    }

    @Test
    fun filterAndTotals() {
        val trips = listOf(
            trip(1, at(2026, 9, 30, 23, 30), 60, 50.0, 80.0, cells = setOf(1L, 2L), bins = mapOf(0 to 60_000L, 5 to 120_000L)),
            trip(2, at(2026, 10, 5, 8), 30, 20.0, 120.0, cells = setOf(2L, 3L), bins = mapOf(2 to 30_000L)),
            trip(3, at(2026, 10, 5, 18), 90, 20.0, 60.0),
        )
        val oct = DriveSummaries.inRange(trips, DriveSummaries.range(SummaryPeriod.THIS_MONTH, at(2026, 10, 13, 12), jst))
        assertEquals(listOf(2L, 3L), oct.map { it.id })

        val s = DriveSummaries.summarize(trips, jst)
        assertEquals(3, s.tripCount)
        assertEquals(90_000.0, s.distanceM, 1e-6)
        assertEquals(180 * 60_000L, s.movingMs)
        assertEquals(90_000.0 / (180 * 60.0), s.avgSpeedMps, 1e-9)
        assertEquals(2L, s.maxSpeed!!.second.id)
        assertEquals(1L, s.longestDistance!!.id)
        assertEquals(3L, s.longestTime!!.id)
        assertEquals(30.0, s.ascentM, 1e-9)
        // 9/30 と（日をまたいだ）10/1、10/5 = 3 日
        assertEquals(3, s.days)
        assertEquals(listOf("2026-09" to 50_000.0, "2026-10" to 40_000.0), s.monthlyM)
        assertEquals(3, s.cells)
        assertEquals(listOf(60_000L, 0L, 30_000L, 0L, 0L, 120_000L), s.speedBinsMs)
    }

    @Test
    fun weekdayAndHourSpreadAcrossMidnight() {
        // 2026-09-30（水）23:30 から 60 分 → 水 23 時に 30 分、木 0 時に 30 分
        val s = DriveSummaries.summarize(listOf(trip(1, at(2026, 9, 30, 23, 30), 60, 10.0, 50.0)), jst)
        assertEquals(30 * 60_000L, s.hourMs[23])
        assertEquals(30 * 60_000L, s.hourMs[0])
        assertEquals(30 * 60_000L, s.weekdayMs[3]) // 水
        assertEquals(30 * 60_000L, s.weekdayMs[4]) // 木
        assertEquals(60 * 60_000L, s.weekdayMs.sum())
    }

    @Test
    fun tiesPickTheNewerTripAndEmptyIsSafe() {
        val a = trip(1, at(2026, 1, 1, 10), 10, 5.0, 50.0)
        val b = trip(2, at(2026, 2, 1, 10), 10, 5.0, 50.0)
        val s = DriveSummaries.summarize(listOf(a, b), jst)
        assertEquals(2L, s.longestDistance!!.id)
        assertEquals(2L, s.maxSpeed!!.second.id)
        val e = DriveSummaries.summarize(emptyList(), jst)
        assertNull(e.maxSpeed)
        assertNull(e.longestDistance)
        assertEquals(0, e.days)
        assertEquals(emptyList<Long>(), e.speedBinsMs)
    }
}

class GridCellsTest {
    private val mLat = Geo.METERS_PER_DEG_LAT

    @Test
    fun sameCellWithinOneKm() {
        val lat = 35.0005
        val base = GridCells.cellOf(lat, 139.0001)
        assertEquals(base, GridCells.cellOf(lat + 100 / mLat, 139.0001 + 100 / Geo.metersPerDegLon(lat)))
        assertNotEquals(base, GridCells.cellOf(lat + 1500 / mLat, 139.0001))
        assertNotEquals(base, GridCells.cellOf(-lat, 139.0001))
        assertNotEquals(base, GridCells.cellOf(lat, -139.0001))
    }

    @Test
    fun countsCellsAlongALineAndFillsGaps() {
        val lat0 = 35.0
        // 北へ 5 km（10 m おき）→ 5〜6 マス
        val dense = (0..500).map { (lat0 + it * 10 / mLat) to 139.0 }
        val n = GridCells.cellsOf(dense).size
        assert(n in 5..6) { "n=$n" }
        // 同じ 5 km を両端の 2 点だけで渡しても、間を補って同じ数
        val sparse = listOf(dense.first(), dense.last())
        assertEquals(GridCells.cellsOf(dense), GridCells.cellsOf(sparse))
        // 50 km 以上の跳び（GPS の欠け）は補わない
        assertEquals(2, GridCells.cellsOf(listOf(lat0 to 139.0, (lat0 + 1.0) to 139.0)).size)
    }
}
