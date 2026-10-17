package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.playback.RecordedSpans
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.playback.TimeSpan
import jp.kjtr.drivelogger.playback.TrackSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSyncTest {
    private val pts = listOf(
        SyncPoint(1_000, 35.0, 139.0, 10.0, 350.0),
        SyncPoint(2_000, 35.001, 139.0, 20.0, 10.0),
        SyncPoint(3_000, 35.002, 139.0, null, null),
        // 3 → 30 秒は不通（27 秒あく）
        SyncPoint(30_000, 35.01, 139.0, 15.0, 90.0),
    )
    private val sync = TrackSync(pts)

    @Test
    fun wallTimeIsStartPlusPosition() {
        assertEquals(1_000_500L, TrackSync.wallTime(1_000_000, 500))
        assertEquals(1_000_000L, TrackSync.wallTime(1_000_000, -10))
    }

    @Test
    fun interpolatesPositionSpeedAndBearing() {
        val f = sync.at(1_500)!!
        assertEquals(35.0005, f.lat, 1e-9)
        assertEquals(15.0, f.speedMps!!, 1e-9)
        // 350° と 10° の間は 0°（短い方を回る）
        assertEquals(0.0, f.bearingDeg!!, 1e-9)
    }

    @Test
    fun exactPointAndMissingSpeedFallsBackToDistance() {
        assertEquals(35.001, sync.at(2_000)!!.lat, 1e-9)
        val f = sync.at(2_500)!!
        // 2→3 秒の点の速度が無いので、区間の距離（約 111 m / 1 s）で
        assertEquals(20.0 + (111.2 - 20.0) * 0.5, f.speedMps!!, 1.0)
        assertEquals(10.0, f.bearingDeg!!, 1e-9)
    }

    @Test
    fun gapsAndOutsideRangeAreNull() {
        assertNull(sync.at(15_000)) // 不通の真ん中
        assertNotNull(sync.at(4_500)) // 不通でも前の点から 3 秒以内
        assertNotNull(sync.at(28_000)) // 次の点まで 2 秒
        assertNull(sync.at(-5_000)) // 始まりより前すぎる
        assertNotNull(sync.at(32_000)) // 終わりから 2 秒
        assertNull(sync.at(40_000))
        assertNull(TrackSync(emptyList()).at(0))
    }
}

class RecordedSpansTest {
    private data class P(val t: Long)

    @Test
    fun picksRunsInsideSpans() {
        val segs = listOf((0L..10L).map { P(it * 1000) }, (20L..30L).map { P(it * 1000) })
        val spans = listOf(TimeSpan(2_000, 5_000), TimeSpan(8_000, 22_000))
        val runs = RecordedSpans.pick(segs, spans) { it.t }
        assertEquals(
            listOf(listOf(2L, 3L, 4L, 5L), listOf(8L, 9L, 10L), listOf(20L, 21L, 22L)),
            runs.map { r -> r.map { it.t / 1000 } },
        )
    }

    @Test
    fun singlePointsAreDroppedAndOpenSpanWorks() {
        val segs = listOf((0L..5L).map { P(it * 1000) })
        assertEquals(emptyList<List<P>>(), RecordedSpans.pick(segs, listOf(TimeSpan(2_000, 2_500)), { it.t }))
        assertEquals(listOf(listOf(4L, 5L)), RecordedSpans.pick(segs, listOf(TimeSpan(4_000, Long.MAX_VALUE)), { it.t }).map { r -> r.map { it.t / 1000 } })
        assertEquals(emptyList<List<P>>(), RecordedSpans.pick(segs, emptyList(), { it.t }))
    }

    @Test
    fun totalMergesOverlaps() {
        assertEquals(9_000L, RecordedSpans.totalMs(listOf(TimeSpan(0, 5_000), TimeSpan(3_000, 7_000), TimeSpan(10_000, 12_000))))
        assertEquals(0L, RecordedSpans.totalMs(emptyList()))
    }
}
