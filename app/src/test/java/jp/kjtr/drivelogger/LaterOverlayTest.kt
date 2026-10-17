package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.SpeedDraw
import jp.kjtr.drivelogger.map.GsiLayer
import jp.kjtr.drivelogger.map.MapBurnConfig
import jp.kjtr.drivelogger.map.MapTrack
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.playback.TrackSync
import jp.kjtr.drivelogger.share.LaterOverlayPlan
import jp.kjtr.drivelogger.share.OverlayItems
import jp.kjtr.drivelogger.share.Span
import jp.kjtr.drivelogger.share.SpeedHide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** あとから重ねる表示（0.4.0）: 時刻の同期・選ばなかった項目は描かない・しきい値を超えた時間帯だけ速度を描かない。 */
class LaterOverlayTest {
    private val t0 = 1_760_000_000_000L
    private val map = MapBurnConfig(layer = GsiLayer.STD, zoom = 15, headingUp = true, sizeRatio = 0.3f, position = jp.kjtr.drivelogger.dashcam.BurnInPosition.BOTTOM_RIGHT, track = MapTrack.RECENT_5KM)
    private val base = BurnInConfig(date = true, speed = true, latLon = true, heading = true, g = true, appName = true, map = map)

    /** 1 秒ごと、40 → 100 → 40 km/h（10〜19 秒が 100 km/h）。北へ進む。 */
    private val points = (0..40).map { s ->
        val kmh = if (s in 10..19) 100.0 else 40.0
        SyncPoint(t0 + s * 1000L, 35.0 + s * 1e-4, 139.0, kmh / 3.6, 0.0)
    }

    @Test
    fun syncsVideoTimeToGps() {
        // 動画の開始 t0 + 2 秒、切り出しの頭 3 秒 → 出力 0 ms は壁時計 t0 + 5 秒
        val plan = LaterOverlayPlan(base, OverlayItems(), TrackSync(points), t0 + 2_000 + 3_000)
        val f0 = plan.frame(0)
        assertEquals(t0 + 5_000, f0.wallMs)
        assertEquals(35.0005, f0.values.lat!!, 1e-9)
        assertEquals(40.0, f0.values.speedKmh!!, 1e-6)
        // 出力 6.5 秒 = 壁時計 11.5 秒（100 km/h の間）、点の間は補間
        val f1 = plan.frame(6_500)
        assertEquals(t0 + 11_500, f1.wallMs)
        assertEquals(35.00115, f1.values.lat!!, 1e-9)
        assertEquals(100.0, f1.values.speedKmh!!, 1e-6)
        assertEquals(0.0, f1.values.headingDeg!!, 1e-9)
        // 記録の外（GPS が無い）は値なし（「--」）
        val far = LaterOverlayPlan(base, OverlayItems(), TrackSync(points), t0 + 3_600_000).frame(0)
        assertNull(far.values.lat)
        assertNull(far.values.speedKmh)
    }

    @Test
    fun unselectedItemsAreNotDrawn() {
        val none = LaterOverlayPlan.configFor(base, OverlayItems(date = false, speed = false, heading = false, latLon = false, map = false, appName = false))
        assertFalse(none.date || none.speed || none.heading || none.latLon || none.appName || none.g)
        assertNull(none.map)
        assertFalse(OverlayItems(false, false, false, false, false, false).any)
        // 地図と緯度経度だけ外す（覆うのではなく、設定ごと外す）
        val c = LaterOverlayPlan.configFor(base, OverlayItems(map = false, latLon = false))
        assertTrue(c.date && c.speed && c.heading && c.appName)
        assertFalse(c.latLon)
        assertNull(c.map)
        // 地図を入れるときは元の地図の設定のまま
        assertEquals(map, LaterOverlayPlan.configFor(base, OverlayItems(map = true)).map)
        // G は記録に残っていないので、あとから重ねる方式では常に外す
        assertFalse(LaterOverlayPlan.configFor(base, OverlayItems()).g)
        // 配置や大きさ（位置・文字の大きさ・表し方）は設定のまま
        assertEquals(base.position, c.position)
        assertEquals(base.size, c.size)
        assertEquals(base.style, c.style)
    }

    @Test
    fun speedOmittedOnlyWhileOverThreshold() {
        // しきい値 60 km/h: 壁時計の隠す時間帯 → この 1 本の出力の時刻（頭 = t0 + 5 秒）
        val wallSpans = SpeedHide.intervals(points, t0, t0 + 40_000, 60.0)
        assertEquals(1, wallSpans.size)
        val start = t0 + 5_000
        val local = wallSpans.map { Span(it.startMs - start, it.endMs - start) }
        val plan = LaterOverlayPlan(base, OverlayItems(), TrackSync(points), start, local)
        // 40 km/h の場面（出力 0〜3 秒）は描く、100 km/h の場面（壁時計 10〜19 秒＝出力 5〜14 秒）は描かない、また 40 km/h に戻れば描く
        assertEquals(SpeedDraw.NORMAL, plan.frame(1_000).speedDraw)
        assertEquals(SpeedDraw.OMIT, plan.frame(5_000).speedDraw)
        assertEquals(SpeedDraw.OMIT, plan.frame(10_000).speedDraw)
        assertEquals(SpeedDraw.NORMAL, plan.frame(20_000).speedDraw)
        // 隠している間も、速度以外（位置・方位・日時）はそのまま描く。数字を低く書き換えることはしない
        val hidden = plan.frame(10_000)
        assertNotNull(hidden.values.lat)
        assertEquals(100.0, hidden.values.speedKmh!!, 1e-6)
        assertTrue(hidden.config.speed && hidden.config.latLon)
        // 設定で「---」を出す方式
        val dashes = LaterOverlayPlan(base, OverlayItems(), TrackSync(points), start, local, dashes = true)
        assertEquals(SpeedDraw.DASHES, dashes.frame(10_000).speedDraw)
        assertEquals(SpeedDraw.NORMAL, dashes.frame(1_000).speedDraw)
    }

    @Test
    fun redrawKeyChangesWithTimeAndHiding() {
        assertEquals(LaterOverlayPlan.redrawKey(0, SpeedDraw.NORMAL), LaterOverlayPlan.redrawKey(150, SpeedDraw.NORMAL))
        assertNotEquals(LaterOverlayPlan.redrawKey(150, SpeedDraw.NORMAL), LaterOverlayPlan.redrawKey(250, SpeedDraw.NORMAL))
        // 同じ時刻の粒度でも、速度を隠す・出すが切り替われば描き直す
        assertNotEquals(LaterOverlayPlan.redrawKey(100, SpeedDraw.NORMAL), LaterOverlayPlan.redrawKey(100, SpeedDraw.OMIT))
    }
}
