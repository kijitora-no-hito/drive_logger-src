package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.map.GsiLayer
import jp.kjtr.drivelogger.map.MapGeometry
import jp.kjtr.drivelogger.map.TileCachePolicy
import jp.kjtr.drivelogger.map.TileKey
import jp.kjtr.drivelogger.map.TileMath
import jp.kjtr.drivelogger.map.TilePrefetch
import jp.kjtr.drivelogger.map.TrackTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class TileMathTest {
    @Test
    fun tokyoStationTile() {
        // 東京駅（35.681236, 139.767125）のズーム 15 のタイル（地理院地図で確認できる番号）
        assertEquals(29105 to 12903, TileMath.tileOf(35.681236, 139.767125, 15))
        assertEquals(0 to 0, TileMath.tileOf(35.0, 139.0, 0))
    }

    @Test
    fun worldPixelRoundTrip() {
        val z = 16
        val x = TileMath.worldX(139.767125, z)
        val y = TileMath.worldY(35.681236, z)
        assertEquals(139.767125, TileMath.lonOf(x, z), 1e-9)
        assertEquals(35.681236, TileMath.latOf(y, z), 1e-9)
        // 赤道・本初子午線は世界の真ん中
        assertEquals(TileMath.worldSize(3) / 2, TileMath.worldX(0.0, 3), 1e-9)
        assertEquals(TileMath.worldSize(3) / 2, TileMath.worldY(0.0, 3), 1e-9)
    }

    @Test
    fun urlAndPath() {
        val k = TileKey(GsiLayer.STD, 15, 29105, 12903)
        assertEquals("https://cyberjapandata.gsi.go.jp/xyz/std/15/29105/12903.png", k.url)
        assertEquals("seamlessphoto/15/29105/12903.jpg", k.copy(layer = GsiLayer.PHOTO).path)
        assertEquals("https://cyberjapandata.gsi.go.jp/xyz/pale/15/29105/12903.png", k.copy(layer = GsiLayer.PALE).url)
    }

    @Test
    fun metersPerPixelAtTokyo() {
        // ズーム 15・北緯 35.68° で約 3.88 m/px
        assertEquals(3.88, TileMath.metersPerPixel(35.68, 15), 0.01)
    }

    @Test
    fun tilesInRadiusNearestFirstAndWraps() {
        val z = 15
        val cx = TileMath.worldX(139.767125, z)
        val cy = TileMath.worldY(35.681236, z)
        val tiles = TileMath.tilesInRadius(cx, cy, 300.0, z)
        assertEquals(29105, tiles.first().x)
        assertEquals(12903, tiles.first().y)
        // 円にかかるタイルだけ（半径 300 px なら 3×3〜4×4 の範囲）
        assertTrue(tiles.size in 9..16)
        assertTrue(tiles.zipWithNext().all { (a, b) -> a.dist2 <= b.dist2 })
        // 日付変更線: x = -1 は 2^z - 1 に折り返す
        val wrap = TileMath.tilesInRadius(10.0, 1000.0, 100.0, 3)
        assertTrue(wrap.any { it.x == 7 && it.dx == -1 })
        // 北の端を越えるタイルは無い
        assertTrue(TileMath.tilesInRadius(500.0, 10.0, 200.0, 3).all { it.y >= 0 })
    }
}

class TilePrefetchTest {
    private val z = 15
    private val lat = 35.681236
    private val lon = 139.767125

    @Test
    fun stoppedCoversViewOnly() {
        val r = MapGeometry.viewRadiusPx(260)
        val plan = TilePrefetch.plan(lat, lon, 90.0, 0.0, z, r)
        assertEquals(TileMath.tileOf(lat, lon, z), plan.first())
        // 止まっていれば中心のまわりだけ（表示の円＋半タイル）
        val expected = TileMath.tilesInRadius(TileMath.worldX(lon, z), TileMath.worldY(lat, z), r + 128, z).map { it.x to it.y }
        assertEquals(expected.toSet(), plan.toSet())
    }

    @Test
    fun movingAddsTilesAhead() {
        val r = MapGeometry.viewRadiusPx(260)
        val stopped = TilePrefetch.plan(lat, lon, 90.0, 0.0, z, r).toSet()
        val east = TilePrefetch.plan(lat, lon, 90.0, 60.0, z, r)
        val (cx, cy) = TileMath.tileOf(lat, lon, z)
        val extra = east.filter { it !in stopped }
        assertTrue(extra.isNotEmpty())
        // 東へ進むなら、増えたタイルは東側
        assertTrue(extra.all { it.first > cx })
        assertTrue(extra.all { kotlin.math.abs(it.second - cy) <= 2 })
        assertTrue(east.size <= TilePrefetch.MAX_TILES)
    }
}

class TileCachePolicyTest {
    private fun e(p: String, kb: Long, t: Long) = TileCachePolicy.Entry(p, kb * 1024, t)

    @Test
    fun underLimitDeletesNothing() {
        assertEquals(emptyList<TileCachePolicy.Entry>(), TileCachePolicy.evict(listOf(e("a", 100, 1), e("b", 100, 2)), 300 * 1024))
    }

    @Test
    fun overLimitDeletesOldestUntilTarget() {
        val list = listOf(e("new", 100, 50), e("old", 100, 10), e("mid", 100, 30), e("older", 100, 20))
        // 400 KB > 300 KB → 270 KB（90%）以下まで古い順に
        val del = TileCachePolicy.evict(list, 300 * 1024)
        assertEquals(listOf("old", "older"), del.map { it.path })
    }

    @Test
    fun freshness() {
        val now = 100L * 24 * 3600 * 1000
        assertTrue(TileCachePolicy.isFresh(now - 1000, now))
        assertTrue(TileCachePolicy.isFresh(now - TileCachePolicy.MAX_AGE_MS + 1, now))
        assertFalse(TileCachePolicy.isFresh(now - TileCachePolicy.MAX_AGE_MS, now))
        // 端末の時計が戻ったら取り直す
        assertFalse(TileCachePolicy.isFresh(now + 60_000, now))
    }
}

class MapGeometryTest {
    @Test
    fun northUpDoesNotRotate() {
        assertEquals(10.0 to -20.0, MapGeometry.toWindow(10.0, -20.0, 90.0, headingUp = false))
        assertEquals(20.0 to -40.0, MapGeometry.toWindow(10.0, -20.0, null, headingUp = true, scale = 2.0))
    }

    @Test
    fun headingUpPutsHeadingOnTop() {
        // 東（90°）へ進むとき、東の点（dx > 0）は上（y < 0）に来る
        val (x, y) = MapGeometry.toWindow(100.0, 0.0, 90.0, headingUp = true)
        assertEquals(0.0, x, 1e-9)
        assertEquals(-100.0, y, 1e-9)
        // 南（180°）へ進むとき、南の点（dy > 0）が上、東の点は左
        val (sx, sy) = MapGeometry.toWindow(0.0, 50.0, 180.0, headingUp = true)
        assertEquals(0.0, sx, 1e-9)
        assertEquals(-50.0, sy, 1e-9)
        val (ex, ey) = MapGeometry.toWindow(50.0, 0.0, 180.0, headingUp = true)
        assertEquals(-50.0, ex, 1e-9)
        assertEquals(0.0, ey, 1e-9)
        // 45°: 北東の点がまっすぐ上
        val (nx, ny) = MapGeometry.toWindow(1.0, -1.0, 45.0, headingUp = true)
        assertEquals(0.0, nx, 1e-9)
        assertEquals(-hypot(1.0, 1.0), ny, 1e-9)
    }

    @Test
    fun windowAndScale() {
        assertEquals(259, MapGeometry.windowSize(0.36f, 1280, 720))
        assertEquals(1f, MapGeometry.tileScale(300), 1e-6f)
        assertEquals(2.5f, MapGeometry.tileScale(2000), 1e-6f)
        // 回しても欠けない半径 = 対角の半分 / 倍率
        assertEquals(300 / 2.0 * Math.sqrt(2.0), MapGeometry.viewRadiusPx(300), 1e-9)
    }
}

class TrackTailTest {
    /** 北へ 100 m おきの点。 */
    private fun line(n: Int, startLat: Double = 35.0) = List(n) { LatLon(startLat + it * 100.0 / Geo.METERS_PER_DEG_LAT, 139.0) }

    @Test
    fun allAndNone() {
        val segs = listOf(line(5))
        assertEquals(segs, TrackTail.recent(segs, null))
        assertEquals(emptyList<List<LatLon>>(), TrackTail.recent(segs, 0.0))
    }

    @Test
    fun recentMetersFromTheEnd() {
        val seg = line(21) // 2 km
        val tail = TrackTail.recent(listOf(seg), 1_000.0)
        assertEquals(1, tail.size)
        assertEquals(11, tail[0].size)
        assertEquals(seg.last(), tail[0].last())
        assertEquals(1_000.0, Geo.lengthM(tail[0]), 1.0)
    }

    @Test
    fun spansSegments() {
        val a = line(6) // 500 m
        val b = line(4, startLat = 35.1) // 300 m
        val tail = TrackTail.recent(listOf(a, b), 600.0)
        assertEquals(2, tail.size)
        assertEquals(b, tail[1])
        // 残り 300 m → 前の区間の最後の 4 点
        assertEquals(4, tail[0].size)
        assertEquals(a.last(), tail[0].last())
    }
}
