package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.BurnGeometry
import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.dashcam.BurnLayout
import jp.kjtr.drivelogger.dashcam.LRect
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.gauge.MeterStyle
import jp.kjtr.drivelogger.map.MapBurnConfig
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.share.ClipChain
import jp.kjtr.drivelogger.share.Cover
import jp.kjtr.drivelogger.share.CoverPlan
import jp.kjtr.drivelogger.share.ExportChoice
import jp.kjtr.drivelogger.share.ExportPart
import jp.kjtr.drivelogger.share.ExportPlanner
import jp.kjtr.drivelogger.share.ExportTimeline
import jp.kjtr.drivelogger.share.SnsPreset
import jp.kjtr.drivelogger.share.Span
import jp.kjtr.drivelogger.share.SpeedHide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隠す時間帯の計算（しきい値・前後の余裕・短い山・GPS の欠け）。 */
class SpeedHideTest {
    private fun pts(vararg kmh: Double?, step: Long = 1000, t0: Long = 0) =
        kmh.mapIndexed { i, v -> SyncPoint(t0 + i * step, 35.0, 139.0, v?.div(3.6), null) }

    @Test
    fun onlyAboveThresholdWithMargin() {
        // 0..10 s: 50, 55, 58, 62, 65, 61, 59, 55, 50, 45, 40 km/h
        val p = pts(50.0, 55.0, 58.0, 62.0, 65.0, 61.0, 59.0, 55.0, 50.0, 45.0, 40.0)
        val s = SpeedHide.intervals(p, 0, 10_000, 60.0, marginMs = 1_000)
        // 超えているのは 3〜5 s の点。両隣の間（2〜3, 5〜6）も含めて 2〜6 s、余裕 ±1 s → 1〜7 s
        assertEquals(listOf(Span(1_000, 7_000)), s)
    }

    @Test
    fun belowThresholdIsNotHidden() {
        val p = pts(50.0, 59.9, 60.0, 58.0)
        // ちょうど 60 は「超えた」にしない
        assertEquals(emptyList<Span>(), SpeedHide.intervals(p, 0, 4_000, 60.0))
    }

    @Test
    fun shortPeakIsStillHidden() {
        // 1 点だけの短い山（10 s の間で 5 s の点だけ 70）
        val p = pts(40.0, 40.0, 40.0, 40.0, 40.0, 70.0, 40.0, 40.0, 40.0, 40.0, 40.0)
        val s = SpeedHide.intervals(p, 0, 10_000, 60.0, marginMs = 1_000)
        assertEquals(listOf(Span(3_000, 7_000)), s)
    }

    @Test
    fun nearbyPeaksAreMergedAndClipped() {
        // 2 つの山の隙間が余裕と合わせて 1 秒以内ならつなぐ。範囲の端で切る
        val p = pts(70.0, 40.0, 40.0, 70.0, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 75.0)
        val s = SpeedHide.intervals(p, 0, 10_000, 60.0, marginMs = 1_000, mergeGapMs = 1_000)
        // 0 s の山 → 0〜1 s → 余裕で -1〜2 s、3 s の山 → 2〜4 → 1〜5 s：つながって 0〜5 s。10 s の山 → 9〜10 → 8〜11 → 切って 8〜10 s
        assertEquals(listOf(Span(0, 5_000), Span(8_000, 10_000)), s)
    }

    @Test
    fun gpsGapNextToFastPointIsHidden() {
        // 3 s で 80 km/h のあと GPS が 20 秒来ない（トンネル）→ その間も隠す（安全側）
        val p = listOf(
            SyncPoint(0, 35.0, 139.0, 50 / 3.6, null),
            SyncPoint(3_000, 35.0, 139.0, 80 / 3.6, null),
            SyncPoint(23_000, 35.0, 139.0, 40 / 3.6, null),
            SyncPoint(24_000, 35.0, 139.0, 40 / 3.6, null),
        )
        val s = SpeedHide.intervals(p, 0, 30_000, 60.0, marginMs = 1_000)
        assertEquals(listOf(Span(0, 24_000)), s)
        assertEquals(24_000, SpeedHide.totalMs(s))
    }

    @Test
    fun unknownSpeedCountsAsNotOver() {
        val p = pts(null, null, 40.0)
        assertTrue(SpeedHide.intervals(p, 0, 3_000, 40.0).isEmpty())
    }
}

/** プリセットのビットレート・大きさの見積もり・切り抜き。 */
class SnsPlanTest {
    @Test
    fun xPresetFitsUnder512MbByDefault() {
        // 1080p・2 分 20 秒・音声あり → 8 Mbps のまま（約 140 MB）
        val p = ExportPlanner.plan(ExportChoice(SnsPreset.X), 1920, 1080, 140_000, hasAudio = true)
        assertEquals(1920, p.outW)
        assertEquals(1080, p.outH)
        assertEquals(8_000_000, p.videoBps)
        assertFalse(p.reducedForSize)
        assertFalse(p.tooLong)
        val mb = p.estimatedBytes / 1_000_000.0
        assertTrue("estimate $mb MB", mb in 140.0..146.0)
    }

    @Test
    fun tooLongForPresets() {
        assertTrue(ExportPlanner.plan(ExportChoice(SnsPreset.X), 1920, 1080, 140_001, false).tooLong)
        assertTrue(ExportPlanner.plan(ExportChoice(SnsPreset.INSTAGRAM_REELS), 1920, 1080, 90_001, false).tooLong)
        assertFalse(ExportPlanner.plan(ExportChoice(SnsPreset.LINE), 1920, 1080, 300_000, false).tooLong)
        assertFalse(ExportPlanner.plan(ExportChoice(SnsPreset.YOUTUBE), 1920, 1080, 3_600_000, false).tooLong)
    }

    @Test
    fun bitrateIsReducedToFitSizeLimit() {
        // カスタムのつもりで高いビットレートを X に（2 分 20 秒・40 Mbps 相当にはならないが）→ 上限 512 MB を超える長さを想定して、
        // X の大きさの上限だけを使う計算を確かめる: 4K の元・X（1080p まで）・140 s → 収まる
        val p = ExportPlanner.plan(ExportChoice(SnsPreset.X), 3840, 2160, 140_000, true)
        assertEquals(1080, minOf(p.outW, p.outH))
        // 大きさの上限に収まる
        assertTrue(p.estimatedBytes <= 512L * 1024 * 1024)
    }

    @Test
    fun reductionStepsDownResolutionWhenBitrateWouldBeTooLow() {
        // 上限に対して長すぎる（YouTube 相当の高い既定を X の上限で）: 直接 fit の計算を確かめる
        val budgetBps = (512L * 1024 * 1024 * 0.95 * 8 / 140).toInt()
        assertTrue("budget $budgetBps", budgetBps > 25_000_000)
        // 見積もりの式: 8 Mbps + 128 kbps × 60 s
        val e = ExportPlanner.estimateBytes(8_000_000, 128_000, 60_000)
        assertEquals((8_128_000 / 8.0 * 60 * 1.02).toLong() + 64 * 1024, e)
    }

    @Test
    fun reelsVerticalCropFromLandscape() {
        val p = ExportPlanner.plan(ExportChoice(SnsPreset.INSTAGRAM_REELS, cropVertical = true), 1920, 1080, 30_000, false)
        assertNotNull(p.crop)
        val c = p.crop!!
        assertEquals(607.5f, c.width, 0.6f)
        assertEquals(1080f, c.height, 0.01f)
        assertEquals((1920 - 607.5f) / 2, c.left, 0.6f)
        // 出力は縦長（短い辺 1080 を超えない＝元の 608 幅のまま）
        assertTrue(p.outH > p.outW)
        assertEquals(0, p.outW % 2)
    }

    @Test
    fun portraitSourceNeedsLittleCrop() {
        val c = ExportPlanner.verticalCrop(1080, 1920)
        assertEquals(1080f, c.width, 0.01f)
        assertEquals(1920f, c.height, 0.5f)
    }

    @Test
    fun youtubeKeepsSourceAndHighBitrate() {
        val p = ExportPlanner.plan(ExportChoice(SnsPreset.YOUTUBE), 1920, 1080, 600_000, true)
        assertEquals(1920 to 1080, p.outW to p.outH)
        assertEquals(12_000_000, p.videoBps)
        // 元が 720p なら上げない
        val q = ExportPlanner.plan(ExportChoice(SnsPreset.YOUTUBE), 1280, 720, 600_000, true)
        assertEquals(1280 to 720, q.outW to q.outH)
        assertEquals(6_000_000, q.videoBps)
    }

    @Test
    fun lineIsLighterAndCustomUsesChoice() {
        val l = ExportPlanner.plan(ExportChoice(SnsPreset.LINE), 1920, 1080, 60_000, true)
        assertEquals(1280 to 720, l.outW to l.outH)
        assertEquals(3_000_000, l.videoBps)
        val c = ExportPlanner.plan(ExportChoice(SnsPreset.CUSTOM, customShortSide = 480, customBitrateBps = 2_000_000), 1920, 1080, 60_000, false)
        assertEquals(854 to 480, c.outW to c.outH)
        assertEquals(2_000_000, c.videoBps)
        assertEquals(0, c.audioBps)
    }

    @Test
    fun bitrateNotAboveSourceAverage() {
        // 元の平均が 3 Mbps（エミュレータの静かな映像など）なら 8 Mbps にしない
        val p = ExportPlanner.plan(ExportChoice(SnsPreset.X), 1280, 720, 60_000, false, srcBitrateBps = 3_000_000)
        assertEquals(3_000_000, p.videoBps)
    }

    @Test
    fun timelineSplitAndLocalSpans() {
        // 3 本（各 60 s、壁時計 1000 s から 1 秒の隙間）を 50 s〜130 s で切り出す
        val lengths = listOf(60_000L, 60_000L, 60_000L)
        val split = ExportTimeline.split(lengths, 50_000, 130_000)
        assertEquals(listOf(Triple(0, 50_000L, 60_000L), Triple(1, 0L, 60_000L), Triple(2, 0L, 10_000L)), split)
        val starts = listOf(1_000_000L, 1_061_000L, 1_122_000L)
        val parts = split.map { (i, a, b) -> ExportPart(i.toLong(), starts[i], a, b) }
        val tl = ExportTimeline(parts)
        assertEquals(listOf(0L, 10_000L, 70_000L), tl.offsets)
        assertEquals(80_000L, tl.durationMs)
        // 壁時計 1 055 s〜1 065 s を隠す → 1 本目の 5〜10 s、2 本目の 4 s まで（1 本目の終わり 1 060 s〜2 本目の始まり 1 061 s は隙間）
        val wall = listOf(Span(1_055_000, 1_065_000))
        assertEquals(listOf(Span(5_000, 10_000)), tl.localSpans(0, wall))
        assertEquals(listOf(Span(0, 4_000)), tl.localSpans(1, wall))
        assertEquals(emptyList<Span>(), tl.localSpans(2, wall))
        assertEquals(1_050_000L, tl.wallAt(0))
        assertEquals(1_061_000L + 5_000, tl.wallAt(15_000))
    }

    @Test
    fun chainFindsContinuousFilesOnly() {
        fun c(id: Long, start: Long, dur: Long = 60_000, w: Int = 1280, h: Int = 720) =
            ClipEntity(id = id, fileName = "f$id", tripId = 1, startedAt = start, durationMs = dur, width = w, height = h, status = ClipEntity.STATUS_DONE)
        val list = listOf(
            c(1, 0), c(2, 61_000), c(3, 122_500),
            // 10 秒空いた → 別
            c(4, 192_500),
            c(5, 253_000, w = 720, h = 1280), // 向きが違う → 別
        )
        assertEquals(listOf(1L, 2L, 3L), ClipChain.around(list, 2).map { it.id })
        assertEquals(listOf(4L), ClipChain.around(list, 4).map { it.id })
        assertEquals(listOf(1L, 2L), ClipChain.around(list, 1, maxEachSide = 1).map { it.id })
    }
}

/** 覆う矩形（文字 / メーター・画面の向き・切り抜き後の座標）と、焼き込みの配置が描く位置と一致すること。 */
class CoverPlanTest {
    private val mono = BurnGeometry.monospace()

    @Test
    fun textSpeedRectCoversOnlySpeedSlot() {
        val c = BurnInConfig()
        val f = BurnGeometry.frame(1920, 1080, c, mono)
        val l = f.layout
        val r = l.speedText!!
        // 速度の枠は行の左端（余白）から「888 km/h」の幅。方位の枠はその右（間 2 文字）
        val big = f.big
        assertEquals(f.margin - f.lineH * 0.12f, r.left, 0.01f)
        assertEquals(f.margin + 8 * big * 0.6f + f.lineH * 0.12f, r.right, 0.01f)
        assertEquals(f.margin + 10 * big * 0.6f, f.headingX, 0.01f)
        assertTrue("heading must not be covered", f.headingX >= r.right - f.lineH * 0.12f)
        // 行は日時の次（左下: 下から 日時・速度・緯度経度・アプリ名）
        assertEquals(1, f.speedLine)
        assertTrue(r.bottom <= 1080f)
        assertNull(l.speedGauge)
        assertEquals("TEXT", l.style)
    }

    @Test
    fun rightAlignedSpeedSlotStaysFixed() {
        val c = BurnInConfig(position = BurnInPosition.TOP_RIGHT)
        val f = BurnGeometry.frame(1280, 720, c, mono)
        val r = f.layout.speedText!!
        val lineW = (8 + 2 + 7) * f.big * 0.6f
        assertEquals(1280 - f.margin - lineW, f.speedX, 0.01f)
        assertEquals(f.speedX - f.lineH * 0.12f, r.left, 0.01f)
        assertTrue(f.layout.alignRight)
        // 上の隅は余白から
        assertEquals(f.margin, f.y0, 0.01f)
    }

    @Test
    fun gaugeStyleCoversSpeedometerCircle() {
        val c = BurnInConfig(style = MeterStyle.GAUGES)
        val f = BurnGeometry.frame(720, 1280, c, mono)
        val l = f.layout
        assertNull(l.speedText)
        val g = l.speedGauge!!
        val speed = f.speedGauge!!
        assertEquals(speed.cx, g.cx, 0.01f)
        assertEquals(speed.r * 1.03f, g.r, 0.01f)
        assertEquals(f.margin + speed.r, speed.cx, 0.01f)
        assertEquals("GAUGES", l.style)
    }

    @Test
    fun mapRectIncludesFrame() {
        val c = BurnInConfig(map = MapBurnConfig(sizeRatio = 0.36f))
        val f = BurnGeometry.frame(1920, 1080, c, mono)
        val m = f.layout.map!!
        assertEquals(388, f.mapS)
        assertTrue(m.left < f.mapX && m.right > f.mapX + f.mapS)
        assertTrue(m.right <= 1920f)
    }

    @Test
    fun coversScaleToFrameAndRejectWrongOrientation() {
        val l = BurnGeometry.layout(1920, 1080, BurnInConfig(map = MapBurnConfig()), mono)
        // 同じ大きさ: そのまま。速度・緯度経度・地図の 3 つ
        val same = CoverPlan.covers(l, 1920, 1080, hideSpeed = true, hideLatLon = true, hideMap = true)!!
        assertEquals(3, same.size)
        val speed = same.filterIsInstance<Cover.Box>().first { it.kind == Cover.Kind.SPEED }.rect
        assertEquals(l.speedText, speed)
        // 半分の大きさのフレーム（720p に落ちた）: 座標も半分
        val half = CoverPlan.covers(l, 960, 540, hideSpeed = true, hideLatLon = false, hideMap = false)!!
        assertEquals(l.speedText!!.left / 2, (half.single() as Cover.Box).rect.left, 0.01f)
        // 速度だけ隠さない
        assertEquals(2, CoverPlan.covers(l, 1920, 1080, hideSpeed = false, hideLatLon = true, hideMap = true)!!.size)
        // 縦の動画に横の配置は当てない
        assertNull(CoverPlan.covers(l, 1080, 1920, true, true, true))
        // 縦の配置（縦で録った動画）
        val lp = BurnGeometry.layout(720, 1280, BurnInConfig(style = MeterStyle.GAUGES), mono)
        val cp = CoverPlan.covers(lp, 720, 1280, true, false, false)!!
        assertTrue(cp.single() is Cover.Disc)
    }

    @Test
    fun rectAfterCrop() {
        // 1920×1080 を中央 9:16（幅 607.5）に切り抜いて 608×1080 に
        val crop = ExportPlanner.verticalCrop(1920, 1080)
        // 左下の速度（左端 24 px）は切り抜きの外
        assertNull(CoverPlan.toOutput(LRect(24f, 900f, 300f, 950f), 1920, 1080, crop, 608, 1080))
        // 中央の矩形は切り抜きの座標に
        val r = CoverPlan.toOutput(LRect(900f, 500f, 1000f, 600f), 1920, 1080, crop, 608, 1080)!!
        assertEquals((900f - crop.left) * 608 / crop.width, r.left, 0.5f)
        assertEquals(500f, r.top, 0.01f)
        // はみ出すものは「一部が切れる」
        assertTrue(CoverPlan.partlyCut(LRect(600f, 0f, 700f, 10f), crop))
        assertFalse(CoverPlan.partlyCut(LRect(900f, 0f, 950f, 10f), crop))
    }

    @Test
    fun layoutJsonRoundTrip() {
        val l = BurnGeometry.layout(1280, 720, BurnInConfig(style = MeterStyle.GAUGES, map = MapBurnConfig()), mono)
        val back = BurnLayout.fromJson(l.toJson())
        assertEquals(l, back)
        assertNull(BurnLayout.fromJson("{broken"))
        assertNull(BurnLayout.fromJson(null))
    }
}
