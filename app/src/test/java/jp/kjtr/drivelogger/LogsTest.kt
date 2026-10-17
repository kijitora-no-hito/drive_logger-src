package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.DriveLogEntity
import jp.kjtr.drivelogger.data.LogMigration
import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.playback.TimelineKind
import jp.kjtr.drivelogger.playback.TripTimeline
import jp.kjtr.drivelogger.stats.DriveSummaries
import jp.kjtr.drivelogger.stats.TripFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * ドライブ＝GPS ログ・動画ログの入れ物（0.6.0〜）: 集計はログをまたいでつながない・ログの合計＝ドライブの統計・
 * 再開して足したログも合計に入る・移行でログに分ける計算。
 */
class LogsTest {
    private val t0 = 1_760_000_000_000L
    private val dLon = 20.0 / (111_320.0 * Math.cos(Math.toRadians(35.0)))

    /** [logId] の点を [n] 個（[startSec] から 1 秒ごとに東へ 20 m、[lon0] から）。区間の番号は [segment]。 */
    private fun run(logId: Long, segment: Int, startSec: Long, n: Int, lon0: Double, idBase: Long): List<TripPointEntity> = (0 until n).map { i ->
        TripPointEntity(
            id = idBase + i, tripId = 1, segment = segment, time = t0 + (startSec + i) * 1000, lat = 35.0, lon = lon0 + i * dLon, ele = null,
            accuracy = 5f, deviceSpeed = 20f, speed = 20f, bearing = 90f, logId = logId,
        )
    }

    private fun log(id: Long, kind: Int, startSec: Long, endSec: Long?, distanceM: Double = 0.0, movingMs: Long = 0, max: Double = 0.0) =
        DriveLogEntity(id = id, tripId = 1, kind = kind, startedAt = t0 + startSec * 1000, endedAt = endSec?.let { t0 + it * 1000 }, distanceM = distanceM, movingMs = movingMs, maxSpeedMps = max)

    @Test
    fun totalsDoNotConnectAcrossLogs() {
        // GPS 1: 0〜59 秒（1.18 km）。GPS 2: 10 分後、5 km 離れた所から 30 秒（0.58 km）。区間の番号がたまたま同じでもログが違えばつながない
        val a = run(logId = 10, segment = 0, startSec = 0, n = 60, lon0 = 139.0, idBase = 1)
        val b = run(logId = 11, segment = 0, startSec = 660, n = 30, lon0 = 139.06, idBase = 100)
        val ta = TripRepository.replay(a, autoPause = false)
        val tb = TripRepository.replay(b, autoPause = false)
        val all = TripRepository.replay(a + b, autoPause = false)
        assertEquals(59 * 20.0, ta.distanceM, 5.0)
        assertEquals(ta.distanceM + tb.distanceM, all.distanceM, 1e-6)
        // ログとログの間（10 分・5 km）は走行時間・距離に入らない
        assertEquals(ta.movingMs + tb.movingMs, all.movingMs)
        assertEquals((59 + 29) * 1000L, all.movingMs)
        assertEquals(maxOf(ta.maxSpeedMps, tb.maxSpeedMps), all.maxSpeedMps, 1e-9)
        // 地図の線・GPX の trkseg も 2 本
        val segs = TripRepository.segmentsOf(a + b)
        assertEquals(2, segs.size)
        assertEquals(listOf(60, 30), segs.map { it.size })
        val trip = TripEntity(id = 1, name = "d", startedAt = t0, endedAt = t0 + 700_000, status = TripEntity.STATUS_FINISHED, autoPause = false)
        val gpx = TripRepository.tripToGpx(trip, a + b, emptyList())
        assertEquals(2, gpx.tracks.first().segments.size)
        // ログの統計の合計＝ドライブの統計
        val logs = listOf(
            log(10, DriveLogEntity.KIND_GPS, 0, 59, ta.distanceM, ta.movingMs, ta.maxSpeedMps),
            log(11, DriveLogEntity.KIND_GPS, 660, 689, tb.distanceM, tb.movingMs, tb.maxSpeedMps),
            log(12, DriveLogEntity.KIND_VIDEO, 10, 40),
        )
        val sum = TripRepository.sumOfLogs(logs)
        assertEquals(all.distanceM, sum.distanceM, 1e-6)
        assertEquals(all.movingMs, sum.movingMs)
        assertEquals(2, sum.gpsLogs)
        assertEquals(1, sum.videoLogs)
    }

    @Test
    fun reopenedDriveAddsLogsAndSummaryCountsOnlyLogTime() {
        // 1 日目に 2 本、閉じたあと 3 日後に再開して 1 本足した
        val a = run(10, 0, 0, 31, 139.0, 1)
        val b = run(11, 1, 600, 31, 139.02, 100)
        val c = run(12, 2, 3 * 86_400L, 31, 139.04, 200)
        val before = TripRepository.replay(a + b, autoPause = false)
        val after = TripRepository.replay(a + b + c, autoPause = false)
        assertEquals(before.distanceM + TripRepository.replay(c, false).distanceM, after.distanceM, 1e-6)
        assertEquals(90_000L, after.movingMs)
        // タイムラインに GPS ログが 3 本（開始〜終了と距離・番号）
        val logs = listOf(
            log(10, DriveLogEntity.KIND_GPS, 0, 30, 600.0), log(11, DriveLogEntity.KIND_GPS, 600, 630, 600.0),
            log(12, DriveLogEntity.KIND_GPS, 3 * 86_400L, 3 * 86_400L + 30, 600.0),
        )
        val items = TripTimeline.build(t0, t0 + (3 * 86_400L + 30) * 1000, TripRepository.videoEvents(emptyList()), emptyList(), emptyList(), gpsLogs = logs)
        val gps = items.filter { it.kind == TimelineKind.GPS }
        assertEquals(listOf(1, 2, 3), gps.map { it.logNumber })
        assertEquals(t0 + 630_000, gps[1].endMs)
        assertEquals(TimelineKind.START, items.first().kind)
        assertEquals(TimelineKind.END, items.last().kind)
        // まとめの時間帯は GPS ログの時間だけ（間の 3 日を数えない）
        val facts = TripFacts(
            id = 1, name = "d", startedAt = t0, endedAt = t0 + (3 * 86_400L + 30) * 1000, distanceM = after.distanceM, movingMs = after.movingMs,
            maxSpeedMps = after.maxSpeedMps, logSpans = logs.map { it.startedAt to it.endedAt!! },
        )
        val s = DriveSummaries.summarize(listOf(facts), TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(90_000L, s.hourMs.sum())
        assertEquals(90_000L, s.weekdayMs.sum())
    }

    @Test
    fun videoLogsBecomeRecordedSpans() {
        val logs = listOf(log(1, DriveLogEntity.KIND_VIDEO, 10, 20), log(2, DriveLogEntity.KIND_VIDEO, 30, null), log(3, DriveLogEntity.KIND_GPS, 0, 40))
        val ev = TripRepository.videoEvents(logs, openEnd = t0 + 99_000)
        assertEquals(2, ev.size)
        assertEquals(t0 + 20_000, ev[0].endedAt)
        // 終わっていない動画ログは openEnd まで
        assertEquals(t0 + 99_000, ev[1].endedAt)
        val items = TripTimeline.build(t0, t0 + 100_000, ev, emptyList(), emptyList())
        assertEquals(listOf(1, 2), items.filter { it.kind == TimelineKind.VIDEO }.map { it.logNumber })
    }

    @Test
    fun migrationSplitsPointsAtParks() {
        val pts = listOf(1L to 100L, 2L to 200L, 3L to 300L, 4L to 900L, 5L to 950L, 6L to 2000L).map { LogMigration.Pt(it.first, it.second) }
        // 駐車 2 回（400 と 1000）→ 3 本
        val g = LogMigration.splitByParks(pts, listOf(1000L, 400L))
        assertEquals(listOf(0..2, 3..4, 5..5), g.map { it.first })
        assertEquals(listOf(0, 1, 2), g.map { it.second })
        // 駐車が無ければ 1 本、点が無ければ 0 本
        assertEquals(1, LogMigration.splitByParks(pts, emptyList()).size)
        assertTrue(LogMigration.splitByParks(emptyList(), listOf(5L)).isEmpty())
        // 時刻が戻った点は前のログに戻さない
        val back = listOf(1L to 100L, 2L to 500L, 3L to 300L).map { LogMigration.Pt(it.first, it.second) }
        assertEquals(listOf(0..0, 1..2), LogMigration.splitByParks(back, listOf(400L)).map { it.first })
    }

    @Test
    fun clipsGoToTheirVideoLog() {
        val logs = listOf(10_000L to 20_000L, 50_000L to null)
        // 録画 ON からカメラが開くまでの余裕（15 秒前まで）・終わりの 5 秒後まで
        assertEquals(0, LogMigration.logForClip(12_000, logs))
        assertEquals(0, LogMigration.logForClip(24_000, logs))
        assertEquals(1, LogMigration.logForClip(40_000, logs))
        assertEquals(1, LogMigration.logForClip(900_000, logs))
        // どれにも入らなければいちばん近いログ
        assertEquals(0, LogMigration.logForClip(30_000, listOf(10_000L to 20_000L, 60_000L to 70_000L)))
        assertNull(LogMigration.logForClip(1, emptyList()))
    }

    @Test
    fun lastActivityIsTheLatestRecord() {
        val t = TripEntity(id = 1, name = "d", startedAt = t0, status = TripEntity.STATUS_OPEN, autoPause = false)
        val logs = listOf(log(1, DriveLogEntity.KIND_GPS, 0, 100), log(2, DriveLogEntity.KIND_VIDEO, 50, 300))
        val parks = listOf(ParkEntity(id = 1, tripId = 1, startedAt = t0 + 120_000, endedAt = t0 + 200_000, lat = null, lon = null))
        assertEquals(t0 + 300_000, TripRepository.lastActivity(t, logs, parks, emptyList(), t0 + 100_000))
        // 何も無ければドライブを始めた時刻
        assertEquals(t0, TripRepository.lastActivity(t, emptyList(), emptyList(), emptyList(), null))
    }
}
