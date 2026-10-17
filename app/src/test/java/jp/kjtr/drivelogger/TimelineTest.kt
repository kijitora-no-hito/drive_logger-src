package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.PhotoEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.playback.StopSpan
import jp.kjtr.drivelogger.playback.TimelineKind
import jp.kjtr.drivelogger.playback.TripTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ドライブのタイムライン（並べ方・駐車の入れ子）・停車の区間・出来事から開く動画・駐車の前後の距離。 */
class TimelineTest {
    private val t0 = 1_760_000_000_000L
    private fun at(sec: Long) = t0 + sec * 1000

    private fun photo(id: Long, sec: Long, parkId: Long? = null) = PhotoEntity(
        id = id, tripId = 1, uri = "content://p/$id", takenAt = at(sec), lat = 35.0, lon = 139.0, locationSource = PhotoEntity.LOC_GPS,
        thumbPath = null, ownCapture = true, createdAt = at(sec), captureMode = PhotoEntity.MODE_CAMERA, parkId = parkId,
    )

    private fun ev(id: Long, type: String, sec: Long, endSec: Long? = sec, value: Double? = null) =
        TripEventEntity(id = id, tripId = 1, type = type, startedAt = at(sec), endedAt = endSec?.let { at(it) }, value = value)

    @Test
    fun timelineIsChronologicalWithStartAndEnd() {
        val events = listOf(
            ev(1, TripEventEntity.TYPE_VIDEO, 100, 400),
            ev(2, TripEventEntity.TYPE_IMPACT, 200, value = 3.8),
            ev(3, TripEventEntity.TYPE_PROTECT, 300),
            ev(4, TripEventEntity.TYPE_VIDEO, 900, null), // 終わっていない録画はドライブの終わりまで
        )
        // 出発前の写真（前後 10 分の内）と、録画中の写真
        val photos = listOf(photo(10, -60), photo(11, 250), photo(12, 250))
        val stops = listOf(StopSpan(at(500), at(620), 35.0, 139.0))
        val items = TripTimeline.build(at(0), at(1000), events, photos, stops)
        val kinds = items.map { it.kind }
        assertEquals(
            listOf(
                TimelineKind.START, TimelineKind.PHOTO, TimelineKind.VIDEO, TimelineKind.IMPACT, TimelineKind.PHOTO, TimelineKind.PHOTO,
                TimelineKind.PROTECT, TimelineKind.STOP, TimelineKind.VIDEO, TimelineKind.END,
            ),
            kinds,
        )
        // 始まりは出発前の写真より前、終わりは最後
        assertEquals(at(0), items.first().startMs)
        assertEquals(at(1000), items.last().startMs)
        // 録画した区間・停車は始まりと終わり
        assertEquals(at(400), items[2].endMs)
        assertEquals(at(1000), items[8].endMs)
        assertEquals(120_000L, items[7].durationMs)
        // 衝撃の G、同じ時刻の写真は id の順
        assertEquals(3.8, items[3].value!!, 0.0)
        assertEquals(listOf(11L, 12L), items.filter { it.startMs == at(250) }.map { it.photoId })
        // 中の行（START・END 以外）は時刻の順
        val mid = items.drop(1).dropLast(1)
        for (i in 1 until mid.size) assertTrue(mid[i].startMs >= mid[i - 1].startMs)
        // 記録中（終わりが無い）: END なし
        assertFalse(TripTimeline.build(at(0), null, events, photos, stops).any { it.kind == TimelineKind.END })
    }

    /** 駐車の区間の中の写真・出来事は、その駐車の下に入れ子で並ぶ（駐車の行のすぐ後）。 */
    @Test
    fun parkNestsItsPhotos() {
        val parks = listOf(
            ParkEntity(id = 1, tripId = 1, startedAt = at(300), endedAt = at(900), lat = 35.0, lon = 139.0, walkDistanceM = 850.0),
            ParkEntity(id = 2, tripId = 1, startedAt = at(1200), endedAt = null, lat = 35.1, lon = 139.1),
        )
        val photos = listOf(
            photo(1, 100),
            photo(2, 400, parkId = 1),
            photo(3, 600, parkId = 1),
            photo(4, 1000),
            photo(5, 1300, parkId = 2),
        )
        val items = TripTimeline.build(at(0), at(1500), listOf(ev(9, TripEventEntity.TYPE_PROTECT, 950)), photos, emptyList(), parks)
        val seq = items.map { it.kind to (it.photoId ?: it.parkId) }
        assertEquals(
            listOf(
                TimelineKind.START to null, TimelineKind.PHOTO to 1L, TimelineKind.PARK to 1L, TimelineKind.PHOTO to 2L, TimelineKind.PHOTO to 3L,
                TimelineKind.PROTECT to null, TimelineKind.PHOTO to 4L, TimelineKind.PARK to 2L, TimelineKind.PHOTO to 5L, TimelineKind.END to null,
            ),
            seq,
        )
        val byPhoto = items.filter { it.kind == TimelineKind.PHOTO }.associateBy { it.photoId }
        assertFalse(byPhoto[1]!!.inPark)
        assertTrue(byPhoto[2]!!.inPark && byPhoto[2]!!.parkId == 1L)
        assertTrue(byPhoto[3]!!.inPark)
        assertFalse(byPhoto[4]!!.inPark)
        // 終わっていない駐車（記録中のドライブで駐車中）の中も入れ子
        assertTrue(byPhoto[5]!!.inPark && byPhoto[5]!!.parkId == 2L)
        // 駐車の行: 区間・徒歩の距離。終わっていない駐車はドライブの終わりまで
        val p1 = items.first { it.kind == TimelineKind.PARK && it.parkId == 1L }
        assertEquals(at(900), p1.endMs)
        assertEquals(850.0, p1.value!!, 0.0)
        assertEquals(at(1500), items.first { it.kind == TimelineKind.PARK && it.parkId == 2L }.endMs)
        // 時刻だけで（紐づけが無くても）区間の中なら入れ子
        val byTime = TripTimeline.build(at(0), at(1500), emptyList(), listOf(photo(7, 500)), emptyList(), parks)
        assertTrue(byTime.first { it.photoId == 7L }.inPark)
    }

    private fun p(id: Long, sec: Long, lat: Double, speedKmh: Float?, seg: Int = 0, source: Int = TripPointEntity.SOURCE_GPS) = TripPointEntity(
        id = id, tripId = 1, segment = seg, time = at(sec), lat = lat, lon = 139.0, ele = null, accuracy = 5f,
        deviceSpeed = null, speed = speedKmh?.let { it / 3.6f }, bearing = null, source = source,
    )

    @Test
    fun stopsFromPoints() {
        val pts = ArrayList<TripPointEntity>()
        var id = 0L
        // 0〜59 秒 走行、60〜189 秒 停車（130 秒）、190〜219 秒 走行、220〜259 秒 停車（40 秒 = 短いので出さない）、260〜 走行
        for (s in 0L until 60) pts.add(p(++id, s, 35.0 + s * 1e-4, 40f))
        for (s in 60L until 190) pts.add(p(++id, s, 35.006, if (s % 2 == 0L) 0f else 2f, source = if (s > 100) TripPointEntity.SOURCE_STOPPED else TripPointEntity.SOURCE_FUSED))
        for (s in 190L until 220) pts.add(p(++id, s, 35.006 + (s - 190) * 1e-4, 30f))
        for (s in 220L until 260) pts.add(p(++id, s, 35.01, 0f))
        for (s in 260L until 270) pts.add(p(++id, s, 35.01 + (s - 260) * 1e-4, 30f))
        // 順番が乱れていてもよい
        val stops = TripTimeline.stops(pts.shuffled(java.util.Random(1)))
        assertEquals(1, stops.size)
        assertEquals(at(60), stops[0].startMs)
        assertEquals(at(190), stops[0].endMs)
        assertEquals(35.006, stops[0].lat, 1e-9)
        // 区間の切れ目（駐車・再開）では停車を切る: 停車のまま区間が変わったら、その前の最後の点まで
        val seg = (0L until 100).map { s -> p(s + 1, s, 35.0, 0f, seg = if (s < 70) 0 else 1) }
        val st2 = TripTimeline.stops(seg)
        assertEquals(listOf(at(0) to at(69)), st2.map { it.startMs to it.endMs })
        // 速度が無い点は距離 / 時間で（1 秒で 0.5 m = 1.8 km/h → 停車）
        val noSpeed = (0L until 90).map { s ->
            TripPointEntity(id = s + 1, tripId = 1, segment = 0, time = at(s), lat = 35.0 + s * 0.5 / Geo.METERS_PER_DEG_LAT, lon = 139.0, ele = null, accuracy = 5f, deviceSpeed = null, speed = null, bearing = null)
        }
        assertEquals(1, TripTimeline.stops(noSpeed).size)
    }

    private fun clip(id: Long, startSec: Long, durSec: Long) = ClipEntity(
        id = id, fileName = "DL_$id.mp4", tripId = 1, startedAt = at(startSec), endedAt = at(startSec + durSec), durationMs = durSec * 1000, status = ClipEntity.STATUS_DONE,
    )

    @Test
    fun clipAtFindsTheScene() {
        val clips = listOf(clip(1, 100, 60), clip(2, 161, 60), clip(3, 400, 60))
        // 動画の中: その位置（5 秒前から）
        assertEquals(2L to 14_000L, TripTimeline.clipAt(clips, at(180), leadMs = 5_000).let { it!!.first.id to it.second })
        // 頭より前にはしない
        assertEquals(1L to 0L, TripTimeline.clipAt(clips, at(102), leadMs = 5_000).let { it!!.first.id to it.second })
        // 分割の切れ目（160〜161 秒）は次のファイルの頭
        assertEquals(2L to 0L, TripTimeline.clipAt(clips, at(160) + 500).let { it!!.first.id to it.second })
        // 録画 ON の時刻（カメラが開く前）: 長めに見れば最初のファイル
        assertNull(TripTimeline.clipAt(clips, at(390)))
        assertEquals(3L, TripTimeline.clipAt(clips, at(390), gapToleranceMs = 15_000)!!.first.id)
        // 消えた動画の時刻
        assertNull(TripTimeline.clipAt(clips, at(300), gapToleranceMs = 15_000))
        assertNull(TripTimeline.clipAt(emptyList(), at(100)))
    }

    /** GPX: 写真（位置のあるもの）・駐車の位置が wpt（時刻順）、駐車の前後で trkseg が分かれ、徒歩の軌跡は 2 本目の trk。 */
    @Test
    fun gpxHasPhotosParksAndWalk() {
        val trip = jp.kjtr.drivelogger.data.TripEntity(id = 1, name = "d", startedAt = at(0), endedAt = at(3000), status = 2, autoPause = false)
        val pts = (0L until 10).map { s -> p(s + 1, s, 35.0 + s * 1e-4, 30f, seg = 0) } + (0L until 10).map { s -> p(20 + s, 2000 + s, 35.1 + s * 1e-4, 30f, seg = 1) }
        val parks = listOf(ParkEntity(id = 5, tripId = 1, startedAt = at(10), endedAt = at(1990), lat = 35.0009, lon = 139.0, walkDistanceM = 300.0))
        val walk = (0L until 4).map { s -> jp.kjtr.drivelogger.data.WalkPointEntity(id = s + 1, tripId = 1, parkId = 5, time = at(100 + s * 10), lat = 35.001 + s * 1e-4, lon = 139.0, accuracy = 5f) }
        val photos = listOf(photo(1, 500, parkId = 5), photo(2, 2005).copy(lat = null, lon = null))
        val g = TripRepository.tripToGpx(trip, pts, listOf(ev(3, TripEventEntity.TYPE_IMPACT, 2008, value = 2.5).copy(lat = 35.1, lon = 139.0)), photos, { "photo" }, parks, walk, "walk")
        assertEquals(2, g.tracks.size)
        assertEquals(2, g.tracks[0].segments.size)
        assertEquals("walk", g.tracks[1].name)
        assertEquals(4, g.tracks[1].pointCount)
        // 位置の無い写真は入れない。駐車・写真・衝撃の時刻順
        assertEquals(listOf("parking", "photo", "impact"), g.waypoints.map { it.type })
        assertEquals("Parking Area", g.waypoints[0].sym)
        // 写真を入れない設定（photoNames = null）・バックアップ（徒歩なし）
        val g2 = TripRepository.tripToGpx(trip, pts, emptyList(), photos, null, parks)
        assertEquals(listOf("parking"), g2.waypoints.map { it.type })
        assertEquals(1, g2.tracks.size)
    }

    /**
     * 駐車 → 再開: 駐車の前後で区間が分かれ（再開した点は新しい区間）、駐車の間の移動（車を置いて歩いた・駐車場の中を動かした）は
     * 走行距離・走行時間に入らない（[TripRepository.replay] = 記録中と同じ数え方で流し直す）。
     */
    @Test
    fun parkThenResumeDoesNotAddDistance() {
        val before = (0L until 60).map { s -> p(s + 1, s, 35.0 + s * 1e-4, 36f, seg = 0) }
        // 30 分駐車して、2 km 離れた所から再開（新しい区間）
        val resumeLat = 35.0 + 60 * 1e-4 + 2000 / Geo.METERS_PER_DEG_LAT
        val after = (0L until 60).map { s -> p(100 + s, 60 + 1800 + s, resumeLat + s * 1e-4, 36f, seg = 1) }
        val drive = TripRepository.replay(before + after, autoPause = false)
        val onlyBefore = TripRepository.replay(before, autoPause = false)
        val onlyAfter = TripRepository.replay(after.map { it.copy(segment = 0) }, autoPause = false)
        assertEquals(onlyBefore.distanceM + onlyAfter.distanceM, drive.distanceM, 1.0)
        assertTrue(drive.distanceM < 1500.0)
        // 走行時間にも駐車の 30 分は入らない
        assertEquals(onlyBefore.movingMs + onlyAfter.movingMs, drive.movingMs)
        assertTrue(drive.movingMs < 3 * 60_000)
        // 区間が分かれる（地図の軌跡は駐車の前後で切れる・GPX の trkseg も分かれる）
        assertEquals(2, TripRepository.segmentsOf(before + after).size)
    }
}
