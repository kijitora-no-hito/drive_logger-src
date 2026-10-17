package jp.kjtr.drivelogger.dashcam

import jp.kjtr.drivelogger.gauge.MeterStyle
import jp.kjtr.drivelogger.map.MapGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 矩形（正立した画面の px）。 */
@Serializable
data class LRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun inflate(d: Float) = LRect(left - d, top - d, right + d, bottom + d)
    fun scale(sx: Float, sy: Float) = LRect(left * sx, top * sy, right * sx, bottom * sy)
    fun offset(dx: Float, dy: Float) = LRect(left + dx, top + dy, right + dx, bottom + dy)
    fun intersects(o: LRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** 円（正立した画面の px）。 */
@Serializable
data class LCircle(val cx: Float, val cy: Float, val r: Float) {
    val bounds: LRect get() = LRect(cx - r, cy - r, cx + r, cy + r)
}

/**
 * 焼き込みの配置（録画したときの、各項目の場所）。clip 表の `burnLayout` に JSON で保存し、SNS 用の書き出しで覆う場所に使う。
 * 座標は正立した [width]×[height] の画面の px。
 */
@Serializable
data class BurnLayout(
    val version: Int = 1,
    val width: Int,
    val height: Int,
    /** "TEXT" / "GAUGES"。 */
    val style: String,
    /** 速度の文字（文字のとき）。 */
    val speedText: LRect? = null,
    /** 速度計（メーターのとき）。 */
    val speedGauge: LCircle? = null,
    /** 緯度経度の文字。 */
    val latLonText: LRect? = null,
    /** 地図の窓（枠・出典を含む）。 */
    val map: LRect? = null,
    /** 文字の塊の左右の寄せ（覆いの「---」をそろえる）。 */
    val alignRight: Boolean = false,
    /** 録画時に保存したものでなく、今の設定から推定した（0.2.0 以前の動画）。 */
    val estimated: Boolean = false,
) {
    val hasSpeed: Boolean get() = speedText != null || speedGauge != null

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String?): BurnLayout? = s?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** 文字の幅を測る（描く側は Paint.measureText、単体テストは等幅の見積もり）。[size] は文字の大きさ [px]。 */
fun interface TextMeasure {
    fun width(text: String, size: Float): Float
}

/**
 * 焼き込みの配置の計算（純ロジック。単体テスト: BurnGeometryTest）。録画の描画（[BurnInRenderer]）もこの結果で描くので、
 * 保存した [BurnLayout] と動画の中の位置は必ず一致する。
 *
 * 速度の文字は幅が変わらないよう「枠」に入れて描く: 速度は `888 km/h` の幅の枠、方位は `NW 359°` の幅の枠（文字は等幅）。
 * 緯度経度は `N88.888888 E888.888888` の幅を覆う（実際の文字はそれ以下）。
 */
object BurnGeometry {
    const val SPEED_TEMPLATE = "888 km/h"
    const val HEADING_TEMPLATE = "NW 359°"
    const val GAP = "  "
    const val LATLON_TEMPLATE = "N88.888888 E888.888888"

    /** 1 フレームの配置（描く位置と覆う矩形）。 */
    data class Frame(
        val lineH: Float,
        val big: Float,
        val small: Float,
        val margin: Float,
        /** 文字の各行の上端と高さ。 */
        val lineTops: FloatArray,
        val lineHeights: FloatArray,
        /** 速度の行の番号（無ければ -1）。 */
        val speedLine: Int,
        val latLonLine: Int,
        /** 塊の左上と大きさ。 */
        val x0: Float,
        val y0: Float,
        val blockW: Float,
        val blockH: Float,
        val right: Boolean,
        /** 速度の行（文字のとき）: 速度の枠の左端と、方位の枠の左端。 */
        val speedX: Float,
        val headingX: Float,
        /** メーターの中心と半径（無ければ null）。 */
        val speedGauge: LCircle?,
        val compass: LCircle?,
        val gMeter: LCircle?,
        /** 地図の窓の左上と一辺（無ければ null）。 */
        val mapX: Float,
        val mapY: Float,
        val mapS: Int,
        val layout: BurnLayout,
    )

    /**
     * [lineCount] 行の文字（小さい行は [smallLines]）を持つ設定 [c] の、正立 [w]×[h] での配置。
     * 行の並びは [BurnInText.lines] と同じ（日時・速度と方位・緯度経度・G・アプリ名）。
     */
    fun frame(w: Int, h: Int, c: BurnInConfig, measure: TextMeasure): Frame {
        val useGauges = c.style == MeterStyle.GAUGES && (c.speed || c.heading || c.g)
        // 行の並び（BurnInText.lines と同じ順）
        data class L(val small: Boolean, val kind: Int)
        val lines = ArrayList<L>(5)
        if (c.date) lines.add(L(false, 0))
        val speedHeadingText = !useGauges && (c.speed || c.heading)
        if (speedHeadingText) lines.add(L(false, 1))
        if (c.latLon) lines.add(L(false, 2))
        if (!useGauges && c.g) lines.add(L(false, 3))
        if (c.appName) lines.add(L(true, 4))

        val lineH = BurnInLayout.lineHeight(c.size, w, h)
        val big = lineH * 0.82f
        val small = big * 0.68f
        val margin = lineH * 0.6f
        val heights = FloatArray(lines.size) { if (lines[it].small) small * 1.25f else lineH }
        val textH = heights.sum()

        val rBig = BurnInLayout.gaugeRadius(c.size, w, h)
        val rSmall = rBig * BurnInLayout.SMALL_GAUGE
        val gap = lineH * 0.3f
        var gaugeW = 0f
        var gaugeH = 0f
        if (useGauges) {
            if (c.speed) gaugeW += rBig * 2
            if (c.heading) gaugeW += (if (gaugeW > 0) gap else 0f) + rSmall * 2
            if (c.g) gaugeW += (if (gaugeW > 0) gap else 0f) + rSmall * 2
            gaugeH = if (c.speed) rBig * 2 else rSmall * 2
        }
        val speedSlot = measure.width(SPEED_TEMPLATE, big)
        val headSlot = measure.width(HEADING_TEMPLATE, big)
        val gapW = measure.width(GAP, big)
        val speedLineW = when {
            c.speed && c.heading -> speedSlot + gapW + headSlot
            c.speed -> speedSlot
            else -> headSlot
        }
        val latLonW = measure.width(LATLON_TEMPLATE, big)
        // 塊の幅は覆う・寄せるのに使うだけ（右寄せは右端、左寄せは左端が基準なので、位置には効かない）
        val blockW = maxOf(if (speedHeadingText) speedLineW else 0f, if (c.latLon) latLonW else 0f, gaugeW)
        val blockH = textH + gaugeH + if (gaugeH > 0 && textH > 0) gap else 0f
        val right = c.position == BurnInPosition.TOP_RIGHT || c.position == BurnInPosition.BOTTOM_RIGHT
        val (x0, y0) = if (blockH > 0f) BurnInLayout.blockOrigin(c.position, w.toFloat(), h.toFloat(), blockW, blockH, margin) else 0f to 0f
        // 基準の端（左寄せは左端、右寄せは右端）
        val leftEdge = margin
        val rightEdge = w - margin

        var y = y0
        var speedGauge: LCircle? = null
        var compass: LCircle? = null
        var gMeter: LCircle? = null
        if (gaugeH > 0f) {
            var x = if (right) rightEdge - gaugeW else leftEdge
            val cy = y + gaugeH / 2
            if (c.speed) {
                speedGauge = LCircle(x + rBig, cy, rBig)
                x += rBig * 2 + gap
            }
            if (c.heading) {
                compass = LCircle(x + rSmall, cy, rSmall)
                x += rSmall * 2 + gap
            }
            if (c.g) gMeter = LCircle(x + rSmall, cy, rSmall)
            y += gaugeH + if (textH > 0) gap else 0f
        }
        val tops = FloatArray(lines.size)
        var speedLine = -1
        var latLonLine = -1
        for (i in lines.indices) {
            tops[i] = y
            if (lines[i].kind == 1) speedLine = i
            if (lines[i].kind == 2) latLonLine = i
            y += heights[i]
        }
        val speedLineX = if (right) rightEdge - speedLineW else leftEdge
        val speedX = speedLineX
        val headingX = if (c.speed) speedLineX + speedSlot + gapW else speedLineX

        // 覆う矩形（縁取りの分だけ広げる）
        val pad = lineH * 0.12f
        val speedRect = if (speedLine >= 0 && c.speed) LRect(speedX, tops[speedLine], speedX + speedSlot, tops[speedLine] + heights[speedLine]).inflate(pad) else null
        val latLonRect = if (latLonLine >= 0) {
            val l = if (right) rightEdge - latLonW else leftEdge
            LRect(l, tops[latLonLine], l + latLonW, tops[latLonLine] + heights[latLonLine]).inflate(pad)
        } else null
        val gaugeCover = speedGauge?.let { LCircle(it.cx, it.cy, it.r * 1.03f) }

        // 地図
        var mapX = 0f
        var mapY = 0f
        var mapS = 0
        var mapRect: LRect? = null
        val mc = c.map
        if (mc != null) {
            mapS = MapGeometry.windowSize(mc.sizeRatio, w, h)
            val (mx, my) = BurnInLayout.mapOrigin(mc.position, c.position, w.toFloat(), h.toFloat(), mapS.toFloat(), margin * 1.4f, blockH)
            mapX = mx
            mapY = my
            val p = (mapS * 0.02f).coerceAtLeast(2f) + 2f
            mapRect = LRect(mx - p, my - p, mx + mapS + p, my + mapS + p)
        }
        val layout = BurnLayout(
            width = w, height = h, style = if (useGauges) "GAUGES" else "TEXT",
            speedText = speedRect, speedGauge = gaugeCover, latLonText = latLonRect, map = mapRect, alignRight = right,
        )
        return Frame(
            lineH, big, small, margin, tops, heights, speedLine, latLonLine, x0, y0, blockW, blockH, right,
            speedX, headingX, speedGauge, compass, gMeter, mapX, mapY, mapS, layout,
        )
    }

    /** 保存用の配置だけ。 */
    fun layout(w: Int, h: Int, c: BurnInConfig, measure: TextMeasure): BurnLayout = frame(w, h, c, measure).layout

    /** 等幅の見積もり（単体テスト・Paint が無いとき）: 1 文字 = 大きさ × [advance]。 */
    fun monospace(advance: Float = 0.6f) = TextMeasure { text, size -> text.length * size * advance }
}
