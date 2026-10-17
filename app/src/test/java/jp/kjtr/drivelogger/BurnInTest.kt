package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.BurnInLayout
import jp.kjtr.drivelogger.dashcam.BurnInLine
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.dashcam.BurnInSize
import jp.kjtr.drivelogger.dashcam.BurnInText
import jp.kjtr.drivelogger.dashcam.BurnInValues
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class BurnInTextTest {
    private val jst = TimeZone.getTimeZone("Asia/Tokyo")
    // 2026-10-13 05:03:27.900 UTC = 14:03:27 JST
    private val t = 1_791_867_807_900L

    @Test
    fun allItems() {
        val v = BurnInValues(speedKmh = 62.4, lat = 35.6812362, lon = 139.7671248, headingDeg = 44.6, g = 0.123)
        val lines = BurnInText.lines(t, v, BurnInConfig(g = true), "ドライブロガー", jst)
        assertEquals(
            listOf(
                BurnInLine("2026/10/13 14:03:27"),
                BurnInLine("62 km/h  NE 45°"),
                BurnInLine("N35.681236 E139.767125"),
                BurnInLine("G 0.12"),
                BurnInLine("ドライブロガー", small = true),
            ),
            lines,
        )
    }

    @Test
    fun unknownValues() {
        val lines = BurnInText.lines(t, BurnInValues(), BurnInConfig(g = true, appName = false), "x", jst).map { it.text }
        assertEquals(listOf("2026/10/13 14:03:27", "-- km/h  --", "N-- E--", "G --"), lines)
    }

    @Test
    fun itemsCanBeTurnedOff() {
        val v = BurnInValues(speedKmh = 10.0, headingDeg = 180.0)
        assertEquals(listOf("S 180°"), BurnInText.lines(t, v, BurnInConfig(date = false, speed = false, latLon = false, appName = false), "x", jst).map { it.text })
        assertEquals(listOf("10 km/h"), BurnInText.lines(t, v, BurnInConfig(date = false, heading = false, latLon = false, appName = false), "x", jst).map { it.text })
        assertEquals(emptyList<BurnInLine>(), BurnInText.lines(t, v, BurnInConfig(date = false, speed = false, heading = false, latLon = false, appName = false), "x", jst))
    }

    @Test
    fun southWestAndHeadingWrap() {
        assertEquals("S33.868800 W151.209300", BurnInText.latLon(-33.8688, -151.2093))
        assertEquals("N 0°", BurnInText.heading(359.6))
        assertEquals("NW 315°", BurnInText.heading(-45.0))
        assertEquals("N 22°", BurnInText.heading(22.4))
        assertEquals("NE 23°", BurnInText.heading(22.6))
        assertEquals("0 km/h", BurnInText.speed(-1.0))
    }

    @Test
    fun dateUsesGivenZone() {
        assertEquals("2026/10/13 05:03:27", BurnInText.dateTime(t, TimeZone.getTimeZone("UTC")))
    }
}

class BurnInLayoutTest {
    @Test
    fun uprightSize() {
        assertEquals(1920 to 1080, BurnInLayout.uprightSize(0, 1920, 1080))
        assertEquals(1080 to 1920, BurnInLayout.uprightSize(90, 1920, 1080))
        assertEquals(1920 to 1080, BurnInLayout.uprightSize(180, 1920, 1080))
        assertEquals(1080 to 1920, BurnInLayout.uprightSize(270, 1920, 1080))
    }

    /** 正立の座標の 4 隅が、バッファの正しい隅に写る（再生で rotationDegrees だけ時計回りに回すと正立になる）。 */
    @Test
    fun cornersMapToBuffer() {
        val w = 1920
        val h = 1080
        fun map(r: Int, x: Float, y: Float) = BurnInLayout.uprightToBuffer(r, w, h).let { it.mapX(x, y) to it.mapY(x, y) }
        // 0°: そのまま
        assertEquals(0f to 0f, map(0, 0f, 0f))
        // 90°: 正立の左上 → バッファの左下（バッファを 90° 時計回りに回すと左下が左上へ）
        val (uw90, uh90) = BurnInLayout.uprightSize(90, w, h)
        assertEquals(0f to h.toFloat(), map(90, 0f, 0f))
        assertEquals(0f to 0f, map(90, uw90.toFloat(), 0f))
        assertEquals(w.toFloat() to h.toFloat(), map(90, 0f, uh90.toFloat()))
        // 180°: 左上 → 右下
        assertEquals(w.toFloat() to h.toFloat(), map(180, 0f, 0f))
        // 270°: 左上 → 右上
        assertEquals(w.toFloat() to 0f, map(270, 0f, 0f))
        assertEquals(0f to 0f, map(270, 0f, BurnInLayout.uprightSize(270, w, h).second.toFloat()))
    }

    /** 変換の行列が回転（直交・行列式 1）になっている（文字が鏡にならない）。 */
    @Test
    fun noMirroring() {
        for (r in listOf(0, 90, 180, 270)) {
            val a = BurnInLayout.uprightToBuffer(r, 640, 480)
            assertEquals(1f, a.a * a.d - a.b * a.c, 1e-6f)
        }
    }

    @Test
    fun blockOriginCorners() {
        assertEquals(10f to 10f, BurnInLayout.blockOrigin(BurnInPosition.TOP_LEFT, 1000f, 500f, 200f, 100f, 10f))
        assertEquals(790f to 10f, BurnInLayout.blockOrigin(BurnInPosition.TOP_RIGHT, 1000f, 500f, 200f, 100f, 10f))
        assertEquals(10f to 390f, BurnInLayout.blockOrigin(BurnInPosition.BOTTOM_LEFT, 1000f, 500f, 200f, 100f, 10f))
        assertEquals(790f to 390f, BurnInLayout.blockOrigin(BurnInPosition.BOTTOM_RIGHT, 1000f, 500f, 200f, 100f, 10f))
        // 塊が画面より大きくても負にならない
        assertEquals(0f to 0f, BurnInLayout.blockOrigin(BurnInPosition.BOTTOM_RIGHT, 100f, 50f, 200f, 100f, 10f))
    }

    @Test
    fun lineHeightUsesShortSide() {
        assertEquals(1080 * BurnInSize.MEDIUM.lineRatio, BurnInLayout.lineHeight(BurnInSize.MEDIUM, 1920, 1080), 1e-3f)
        assertEquals(1080 * BurnInSize.LARGE.lineRatio, BurnInLayout.lineHeight(BurnInSize.LARGE, 1080, 1920), 1e-3f)
        assertEquals(12f, BurnInLayout.lineHeight(BurnInSize.SMALL, 100, 100), 1e-3f)
    }
}
