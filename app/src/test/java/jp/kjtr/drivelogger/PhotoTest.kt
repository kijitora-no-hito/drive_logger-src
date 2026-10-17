package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.photo.CardSize
import jp.kjtr.drivelogger.photo.CardStat
import jp.kjtr.drivelogger.photo.CardStats
import jp.kjtr.drivelogger.photo.CardTripStats
import jp.kjtr.drivelogger.photo.ExifGps
import jp.kjtr.drivelogger.photo.ExifTime
import jp.kjtr.drivelogger.photo.PhotoLocator
import jp.kjtr.drivelogger.photo.ShareCardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 写真（自転車ルートロガーから移植: 位置の補間・EXIF・SNS 用の画像のレイアウト）と、SNS 用の画像の統計の選び方（車向け）。 */
class PhotoTest {
    private val t0 = 1_760_000_000_000L

    private fun pt(i: Int, sec: Long, lat: Double, lon: Double) =
        TripPointEntity(id = i.toLong(), tripId = 1, segment = 0, time = t0 + sec * 1000, lat = lat, lon = lon, ele = null, accuracy = 5f, deviceSpeed = null, speed = null, bearing = null)

    private val pts = listOf(
        pt(1, 0, 35.0, 139.0),
        pt(2, 10, 35.001, 139.0),
        pt(3, 20, 35.001, 139.002),
        // 20 分の空白（電源 OFF・駐車 など）
        pt(4, 1220, 35.1, 139.1),
    )

    @Test
    fun interpolateBetweenPoints() {
        val p = PhotoLocator.interpolate(pts, t0 + 5_000)!!
        assertEquals(35.0005, p.lat, 1e-9)
        assertEquals(139.0, p.lon, 1e-9)
        val q = PhotoLocator.interpolate(pts, t0 + 15_000)!!
        assertEquals(35.001, q.lat, 1e-9)
        assertEquals(139.001, q.lon, 1e-9)
        // ちょうど点の時刻
        assertEquals(35.001, PhotoLocator.interpolate(pts, t0 + 10_000)!!.lat, 1e-12)
    }

    @Test
    fun interpolateEdgesAndGaps() {
        // 始点の 4 分前は始点、6 分前は不明
        assertEquals(35.0, PhotoLocator.interpolate(pts, t0 - 4 * 60_000)!!.lat, 1e-12)
        assertNull(PhotoLocator.interpolate(pts, t0 - 6 * 60_000))
        // 長い空白の中: 前の点から 3 分なら前の点、真ん中（10 分）は不明、後ろの点の 2 分前は後ろの点
        assertEquals(35.001, PhotoLocator.interpolate(pts, t0 + 20_000 + 3 * 60_000)!!.lat, 1e-12)
        assertNull(PhotoLocator.interpolate(pts, t0 + 620_000))
        assertEquals(35.1, PhotoLocator.interpolate(pts, t0 + 1_220_000 - 2 * 60_000)!!.lat, 1e-12)
        assertNull(PhotoLocator.interpolate(emptyList(), t0))
    }

    @Test
    fun belongsToTrip() {
        val t = TripEntity(id = 1, name = "x", startedAt = t0, endedAt = t0 + 3_600_000, status = 2, autoPause = false)
        assertTrue(PhotoLocator.belongsTo(t, t0 + 1_000))
        assertTrue(PhotoLocator.belongsTo(t, t0 - 9 * 60_000))
        assertFalse(PhotoLocator.belongsTo(t, t0 - 11 * 60_000))
        assertTrue(PhotoLocator.belongsTo(t, t0 + 3_600_000 + 9 * 60_000))
        assertFalse(PhotoLocator.belongsTo(t, t0 + 3_600_000 + 11 * 60_000))
        // 記録中（終わりが無い）
        assertTrue(PhotoLocator.belongsTo(t.copy(endedAt = null), t0 + 10 * 3_600_000))
    }

    /** 駐車中に撮った写真（Photo Picker で取り込んだもの）は、時刻で駐車の区間に紐づく。 */
    @Test
    fun parkAtTime() {
        val parks = listOf(
            ParkEntity(id = 1, tripId = 1, startedAt = t0 + 100_000, endedAt = t0 + 200_000, lat = 35.0, lon = 139.0),
            ParkEntity(id = 2, tripId = 1, startedAt = t0 + 500_000, endedAt = null, lat = 35.1, lon = 139.1),
        )
        assertNull(PhotoLocator.parkAt(parks, t0 + 50_000))
        assertEquals(1L, PhotoLocator.parkAt(parks, t0 + 150_000)!!.id)
        assertEquals(1L, PhotoLocator.parkAt(parks, t0 + 200_000)!!.id)
        assertNull(PhotoLocator.parkAt(parks, t0 + 300_000))
        // 終わっていない駐車は今まで
        assertEquals(2L, PhotoLocator.parkAt(parks, t0 + 9_000_000)!!.id)
    }

    @Test
    fun exifTimeParse() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // 2026-10-11 14:21:55 JST = 05:21:55 UTC
        val utc = ExifTime.parse("2026:10:11 14:21:55", "+09:00", "250", TimeZone.getTimeZone("UTC"))!!
        assertEquals(ExifTime.parse("2026:10:11 05:21:55", "+00:00")!! + 250, utc)
        // オフセットが無ければ端末のタイムゾーン
        assertEquals(utc - 250, ExifTime.parse("2026:10:11 14:21:55", null, null, tokyo))
        assertNull(ExifTime.parse("", null))
        assertNull(ExifTime.parse("0000:00:00 00:00:00", null))
        assertEquals("2026:10:11 14:21:55", ExifTime.format(utc - 250, tokyo))
        assertEquals("+09:00", ExifTime.formatOffset(utc, tokyo))
        assertEquals("-05:30", ExifTime.formatOffset(utc, TimeZone.getTimeZone("GMT-05:30")))
    }

    /** EXIF の GPS の文字列の書き出しと読み戻し（ExifInterface にこの文字列で書く）。 */
    @Test
    fun exifGpsWriteAndReadBack() {
        for ((lat, lon) in listOf(35.681236 to 139.767125, -33.856784 to -151.215297, 0.000123 to 0.5)) {
            val la = ExifGps.toRational(lat)
            val lo = ExifGps.toRational(lon)
            val back = ExifGps.toLatLon(la, ExifGps.latRef(lat), lo, ExifGps.lonRef(lon))!!
            assertEquals(lat, back.first, 1e-7)
            assertEquals(lon, back.second, 1e-7)
        }
        assertEquals("35/1,40/1,524496/10000", ExifGps.toRational(35.681236))
        // カメラが書く形（秒の分母が 1000 など）も読める
        assertEquals(35.6812236, ExifGps.toLatLon("35/1,40/1,52405/1000", "N", "139/1,46/1,1650/1000", "E")!!.first, 1e-6)
        assertNull(ExifGps.toLatLon("0/1,0/1,0/1", "N", "0/1,0/1,0/1", "E"))
        assertNull(ExifGps.toLatLon("garbage", "N", "1/1,2/1,3/1", "E"))
    }

    @Test
    fun cardLayoutNoOverlapAllSizes() {
        for (size in CardSize.entries) for (n in 0..ShareCardLayout.MAX_PHOTOS) for (app in listOf(true, false)) {
            val l = ShareCardLayout.compute(size, n, app)
            val boxes = l.all()
            assertEquals("$size n=$n", n, l.photos.size)
            for (b in boxes) assertTrue("$size n=$n $b", b.inside(size.width, size.height))
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("$size n=$n app=$app overlap ${boxes[i]} ${boxes[j]}", boxes[i].intersects(boxes[j]))
            }
            // 地図は十分大きい（画像の 30% 以上）、写真のマスは 120 px 以上
            assertTrue("$size n=$n map", l.map.width * l.map.height >= 0.30f * size.width * size.height)
            for (p in l.photos) assertTrue("$size n=$n cell $p", p.width >= 120 && p.height >= 120)
            // 写真の番号の順（読む順: 左上から）
            for (i in 1 until l.photos.size) {
                val a = l.photos[i - 1]
                val b = l.photos[i]
                assertTrue("$size n=$n order", b.top > a.top + 1 || (b.top == a.top && b.left > a.left))
            }
        }
    }

    /** 車向けの統計: 距離・経過時間・平均・最高・停車時間。「最高速度を出さない」・しきい値を超えた速度は項目ごと入れない（低い数字にしない）。 */
    @Test
    fun cardStatsHideSpeeds() {
        // 平均 = 60 km / 1 時間 = 60 km/h、最高 110 km/h
        val s = CardTripStats(distanceM = 60_000.0, elapsedMs = 4_000_000, movingMs = 3_600_000, stillMs = 300_000, maxSpeedMps = 110 / 3.6)
        assertEquals(60.0, s.avgSpeedMps * 3.6, 1e-9)
        assertEquals(CardStats.ALL, CardStats.pick(s, hideMax = false, hideOverThreshold = false, thresholdKmh = 60))
        assertEquals(CardStats.ALL - CardStat.MAX, CardStats.pick(s, hideMax = true, hideOverThreshold = false, thresholdKmh = 60))
        // しきい値 80: 最高（110）だけ超える
        assertEquals(CardStats.ALL - CardStat.MAX, CardStats.pick(s, hideMax = false, hideOverThreshold = true, thresholdKmh = 80))
        // しきい値 55: 平均（60）も超える
        assertEquals(listOf(CardStat.DISTANCE, CardStat.ELAPSED, CardStat.STILL), CardStats.pick(s, hideMax = false, hideOverThreshold = true, thresholdKmh = 55))
        // ちょうどしきい値（60）は超えていない
        assertTrue(CardStat.AVG in CardStats.pick(s, hideMax = false, hideOverThreshold = true, thresholdKmh = 60))
        // しきい値 120: 何も隠れない
        assertEquals(CardStats.ALL, CardStats.pick(s, hideMax = false, hideOverThreshold = true, thresholdKmh = 120))
        assertEquals(listOf(CardStat.AVG, CardStat.MAX), CardStats.hiddenByThreshold(s, 55))
        // 走行時間 0（駐車だけ）でも割り算しない
        assertEquals(0.0, CardTripStats(0.0, 1000, 0, 0, 0.0).avgSpeedMps, 0.0)
    }
}
