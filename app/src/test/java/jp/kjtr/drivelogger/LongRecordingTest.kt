package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.LongRecordingPolicy
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.dashcam.VideoQuality
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.share.ClipChain
import jp.kjtr.drivelogger.share.ExportPart
import jp.kjtr.drivelogger.share.ExportTimeline
import jp.kjtr.drivelogger.ui.longRecordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 長時間録画（0.7.0〜。DESIGN.md §3.11）: 空き容量の判定・分割したファイルのつなぎ・まとまり。 */
class LongRecordingTest {
    private val gb = 1L shl 30
    private val mb = 1L shl 20

    @Test
    fun bytesPerHourMatchesTheLoopEstimate() {
        // 1080p 30fps 12 Mbps × 3600 s × 1.1 ≒ 5.94 GB / 時間
        val bph = LongRecordingPolicy.bytesPerHour(VideoQuality.FHD, 30, audio = false)
        assertEquals(5.94e9, bph.toDouble(), 10.0)
        // 60fps は 1.5 倍、音声は 128 kbps を足す
        assertEquals(bph * 1.5, LongRecordingPolicy.bytesPerHour(VideoQuality.FHD, 60, audio = false).toDouble(), 10.0)
        assertTrue(LongRecordingPolicy.bytesPerHour(VideoQuality.FHD, 30, audio = true) > bph)
    }

    @Test
    fun hoursLeftKeepsOneGigabyte() {
        val bph = 6 * gb
        assertEquals(2.0, LongRecordingPolicy.hoursLeft(13 * gb, bph), 1e-9)
        assertEquals(0.0, LongRecordingPolicy.hoursLeft(gb, bph), 1e-9)
        assertEquals(0.0, LongRecordingPolicy.hoursLeft(500 * mb, bph), 1e-9)
    }

    @Test
    fun stopsBelowOneGigabyteAndWarnsBeforeThat() {
        val bph = 6 * gb
        // 空き 20 GB: 止めない・警告なし
        LongRecordingPolicy.check(20 * gb, bph).let { assertFalse(it.stop); assertFalse(it.low) }
        // 空き 3 GB（残り 2 GB ＝ 20 分）: 警告するがまだ止めない
        LongRecordingPolicy.check(3 * gb, bph).let { assertFalse(it.stop); assertTrue(it.low); assertEquals(1.0 / 3, it.hoursLeft, 1e-9) }
        // ちょうど 1 GB は止めない、1 GB を切ったら止める
        assertFalse(LongRecordingPolicy.check(gb, bph).stop)
        assertTrue(LongRecordingPolicy.check(gb - 1, bph).stop)
        assertTrue(LongRecordingPolicy.check(700 * mb, bph).stop)
    }

    @Test
    fun startNeedsOneGigabytePlusFiveMinutes() {
        val bph = 6 * gb // 5 分 = 0.5 GB
        assertFalse(LongRecordingPolicy.canStart(gb + 400 * mb, bph))
        assertTrue(LongRecordingPolicy.canStart(gb + 512 * mb, bph))
        assertTrue(LongRecordingPolicy.canStart(50 * gb, bph))
    }

    @Test
    fun recordModeCodes() {
        assertEquals(RecordMode.LOOP, RecordMode.of(0))
        assertEquals(RecordMode.LONG, RecordMode.of(1))
        assertEquals(RecordMode.LOOP, RecordMode.of(null))
        assertTrue(ClipEntity(fileName = "a", tripId = null, startedAt = 0, recordMode = 1).isLong)
        assertFalse(ClipEntity(fileName = "a", tripId = null, startedAt = 0).isLong)
    }

    private fun c(id: Long, start: Long, dur: Long = 600_000, long: Boolean = true, logId: Long? = 7, w: Int = 1920, h: Int = 1080) = ClipEntity(
        id = id, fileName = "f$id", tripId = 1, logId = logId, startedAt = start, durationMs = dur, width = w, height = h,
        status = ClipEntity.STATUS_DONE, recordMode = if (long) 1 else 0,
    )

    /** 10 分ごとに分けた長時間録画は、同じ録画のファイルを全部（前後 3 本の制限なしで）つなぐ。ループやほかの録画とはつながない。 */
    @Test
    fun longRecordingFilesAreJoinedIntoOneTimeline() {
        // 長時間録画（動画ログ 7）: 10 分のファイル 8 本（分割の途切れ 1 秒）
        val long = (0 until 8).map { i -> c(10L + i, 1_000_000L + i * 601_000L) }
        // 直後に続けて始めたループ録画（同じ大きさ・途切れ 1 秒）と、別の長時間録画（動画ログ 8）
        val endLong = long.last().startedAt + long.last().durationMs
        val loop = c(30, endLong + 1_000, dur = 180_000, long = false, logId = 9)
        val other = c(40, loop.startedAt + 181_000, logId = 8)
        val all = (long + loop + other).shuffled()

        val chain = ClipChain.around(all, 13)
        assertEquals((10L..17L).toList(), chain.map { it.id })
        assertEquals(long.map { it.id }, ClipChain.forPlayback(all, 10).map { it.id })
        // SNS の書き出しで前後 3 本までにしたいとき
        assertEquals((10L..16L).toList(), ClipChain.around(all, 13, longLimit = true).map { it.id })
        // ループ録画は今までどおり（再生はそのファイルだけ・長時間とはつながない）
        assertEquals(listOf(30L), ClipChain.forPlayback(all, 30).map { it.id })
        assertEquals(listOf(30L), ClipChain.around(all, 30).map { it.id })
        assertEquals(listOf(40L), ClipChain.around(all, 40).map { it.id })

        // つないだ時間軸: 出力の時刻 ↔ 壁時計（3 本目の途中 = 2 本分 + 5 分）
        val tl = ExportTimeline(chain.map { ExportPart(it.id, it.startedAt, 0, it.durationMs) })
        assertEquals(8 * 600_000L, tl.durationMs)
        assertEquals(long[2].startedAt + 300_000, tl.wallAt(2 * 600_000L + 300_000))
        assertEquals(long[7].startedAt, tl.wallAt(7 * 600_000L))
    }

    @Test
    fun longRecordingsAreGroupedPerRecording() {
        val a = (0 until 3).map { i -> c(1L + i, 1_000_000L + i * 601_000L, logId = 5) }
        val b = (0 until 2).map { i -> c(10L + i, 9_000_000L + i * 601_000L, logId = 6) }
        // ドライブを消して動画ログが外れたファイル（続けて録ったもの同士はまとめる）
        val orphan = (0 until 2).map { i -> c(20L + i, 20_000_000L + i * 601_000L, logId = null) }
        val loop = c(30, 30_000_000L, long = false, logId = 9)
        val groups = longRecordings((a + b + orphan + loop).shuffled())
        // 新しい録画が先
        assertEquals(listOf(listOf(20L, 21L), listOf(10L, 11L), listOf(1L, 2L, 3L)), groups.map { g -> g.map { it.id } })
    }
}
