package jp.kjtr.drivelogger.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.geo.LatLon
import kotlin.math.abs

/** 焼き込む地図の設定。 */
data class MapBurnConfig(
    val layer: GsiLayer = GsiLayer.STD,
    val zoom: Int = 15,
    val headingUp: Boolean = true,
    /** 窓の一辺（短い辺に対する割合）。 */
    val sizeRatio: Float = 0.36f,
    val position: BurnInPosition = BurnInPosition.BOTTOM_RIGHT,
    val track: MapTrack = MapTrack.RECENT_5KM,
)

/** タイルの出どころ（[GsiTileStore]。単体テストでは差し替えない）。 */
interface TileSource {
    fun tile(key: TileKey): Bitmap?
    val tileVersion: Int
}

/**
 * 地図の小窓の焼き込み（地理院タイル＋軌跡＋現在地の印＋出典）。録画（OverlayEffect のスレッド）と設定の見本で使う。
 *
 * - タイルと軌跡は窓と同じ大きさの合成画像に描いておき、毎フレームはそれを 1 枚描くだけ。
 *   合成画像は、位置が 1 px 以上動いた・向きが 1° 以上変わった（進行方向が上のとき）・タイルが届いた・軌跡が変わった・設定が変わったときだけ、
 *   100 ms（タイル・軌跡だけの変化は 500 ms）以上あけて作り直す。
 * - 取れていないタイルは灰色。位置が分からないうちは灰色の窓と出典だけ。
 * 1 つのインスタンスは 1 つのスレッドから使うこと。
 */
class MapBurnRenderer(private val source: TileSource) {
    private var comp: Bitmap? = null
    private var compCanvas: Canvas? = null
    private var shader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private val compPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileRect = RectF()
    private val trackPath = Path()
    private val trackLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = TRACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val frameBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FRAME_BG; style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ARROW; style = Paint.Style.FILL }
    private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val arrow = Path()
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STRIP_BG; style = Paint.Style.FILL }
    private val stripPath = Path()
    /** 下の 2 隅だけ丸める。 */
    private val stripRadii = FloatArray(8)
    private val attrText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val northText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NORTH; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val northBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.FILL }
    private val outer = RectF()
    private val inner = RectF()
    private val textBounds = Rect()

    // 合成画像を作ったときの値
    private var builtS = -1
    private var builtCfg: MapBurnConfig? = null
    private var builtCx = Double.NaN
    private var builtCy = Double.NaN
    private var builtHeading = Double.NaN
    private var builtVersion = -1
    private var builtTrack: Any? = null
    private var builtAt = 0L
    private var builtStripS = -1
    private var attrSize = 0f

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** 窓の左上 ([left], [top])・一辺 [s] px に描く。値が分からなければ null。 */
    fun draw(
        canvas: Canvas, left: Float, top: Float, s: Int, lat: Double?, lon: Double?, headingDeg: Double?,
        track: List<List<LatLon>>, cfg: MapBurnConfig, nowMs: Long,
    ) {
        if (s < 32) return
        if (lat != null && lon != null) {
            lastLat = lat
            lastLon = lon
        }
        val cLat = lastLat
        val cLon = lastLon
        val heading = if (cfg.headingUp) (headingDeg ?: 0.0) else 0.0
        ensureComp(s)
        val z = cfg.zoom
        val cx = if (cLon != null) TileMath.worldX(cLon, z) else Double.NaN
        val cy = if (cLat != null) TileMath.worldY(cLat, z) else Double.NaN
        val k = MapGeometry.tileScale(s)
        val version = source.tileVersion
        val since = nowMs - builtAt
        val moved = builtCx.isNaN() != cx.isNaN() || (!cx.isNaN() && (abs(cx - builtCx) * k >= 1.0 || abs(cy - builtCy) * k >= 1.0))
        val turned = cfg.headingUp && angleDiff(heading, builtHeading) >= 1.0
        val must = builtS != s || builtCfg != cfg
        val soft = version != builtVersion || track !== builtTrack
        if (must || ((moved || turned) && since >= 100) || (soft && since >= 500)) {
            build(s, cfg, cx, cy, heading, track, k)
            builtS = s; builtCfg = cfg; builtCx = cx; builtCy = cy; builtHeading = heading
            builtVersion = version; builtTrack = track; builtAt = nowMs
        }

        val radius = s * 0.07f
        val pad = (s * 0.02f).coerceAtLeast(2f)
        // 枠と半透明の背景
        outer.set(left - pad, top - pad, left + s + pad, top + s + pad)
        canvas.drawRoundRect(outer, radius + pad, radius + pad, frameBg)
        // 地図（角を丸めて）
        inner.set(left, top, left + s, top + s)
        shaderMatrix.setTranslate(left, top)
        shader?.setLocalMatrix(shaderMatrix)
        compPaint.shader = shader
        canvas.drawRoundRect(inner, radius, radius, compPaint)
        // 現在地の印（進行方向が上なら上向き、北が上なら進行方向へ）
        if (cLat != null) {
            val acx = left + s / 2f
            val acy = top + s / 2f
            val a = s * 0.065f
            canvas.save()
            canvas.translate(acx, acy)
            if (!cfg.headingUp && headingDeg != null) canvas.rotate(headingDeg.toFloat())
            arrow.reset()
            arrow.moveTo(0f, -a)
            arrow.lineTo(a * 0.72f, a * 0.8f)
            arrow.lineTo(0f, a * 0.38f)
            arrow.lineTo(-a * 0.72f, a * 0.8f)
            arrow.close()
            arrowEdge.strokeWidth = a * 0.22f
            canvas.drawPath(arrow, arrowEdge)
            canvas.drawPath(arrow, arrowFill)
            canvas.restore()
        }
        // 北の印（進行方向が上のとき）
        if (cfg.headingUp) {
            val nr = s * 0.055f
            val ring = s / 2f - nr * 1.5f
            val a = Math.toRadians(-heading)
            val nx = left + s / 2f + (ring * kotlin.math.sin(a)).toFloat()
            // 下の出典の帯にかからないように
            val ny = (top + s / 2f - (ring * kotlin.math.cos(a)).toFloat()).coerceAtMost(top + s - attrSize * 1.5f - nr * 1.1f)
            canvas.drawCircle(nx, ny, nr, northBg)
            northText.textSize = nr * 1.4f
            canvas.drawText("N", nx, ny + northText.textSize * 0.36f, northText)
        }
        // 出典（地理院タイルの利用規約による表示）
        if (builtStripS != s) {
            fitAttribution(s)
            builtStripS = s
        }
        val stripH = attrSize * 1.5f
        stripPath.reset()
        tileRect.set(left, top + s - stripH, left + s, top + s)
        for (i in 4..7) stripRadii[i] = radius
        stripPath.addRoundRect(tileRect, stripRadii, Path.Direction.CW)
        canvas.drawPath(stripPath, stripPaint)
        attrText.textSize = attrSize
        canvas.drawText(ATTRIBUTION, left + s / 2f, top + s - stripH * 0.30f, attrText)
        // 枠の線
        border.strokeWidth = (s * 0.008f).coerceAtLeast(1.5f)
        canvas.drawRoundRect(inner, radius, radius, border)
    }

    private fun fitAttribution(s: Int) {
        var size = (s * 0.052f).coerceAtLeast(10f)
        attrText.textSize = size
        val w = attrText.measureText(ATTRIBUTION)
        val max = s * 0.94f
        if (w > max) size *= max / w
        attrSize = size
    }

    private fun ensureComp(s: Int) {
        val c = comp
        if (c != null && c.width == s) return
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        comp = b
        compCanvas = Canvas(b)
        shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        builtS = -1
    }

    private fun build(s: Int, cfg: MapBurnConfig, cx: Double, cy: Double, heading: Double, track: List<List<LatLon>>, k: Float) {
        val c = compCanvas ?: return
        c.drawColor(MISSING)
        if (cx.isNaN()) return
        val z = cfg.zoom
        c.save()
        c.translate(s / 2f, s / 2f)
        if (cfg.headingUp) c.rotate(-heading.toFloat())
        c.scale(k, k)
        val radius = MapGeometry.viewRadiusPx(s)
        for (t in TileMath.tilesInRadius(cx, cy, radius, z)) {
            val bmp = source.tile(TileKey(cfg.layer, z, t.x, t.y)) ?: continue
            val l = (t.dx * TileMath.TILE - cx).toFloat()
            val tp = (t.y * TileMath.TILE - cy).toFloat()
            // 継ぎ目が出ないよう 0.5 px 重ねる
            tileRect.set(l - 0.25f, tp - 0.25f, l + TileMath.TILE + 0.25f, tp + TileMath.TILE + 0.25f)
            c.drawBitmap(bmp, null, tileRect, tilePaint)
        }
        // 軌跡
        val segs = TrackTail.recent(track, cfg.track.meters)
        if (segs.isNotEmpty()) {
            trackPath.reset()
            val minStep = 1.5 / k
            val lim = radius * 1.6
            for (seg in segs) {
                var started = false
                var px = 0.0
                var py = 0.0
                for (p in seg) {
                    val x = TileMath.worldX(p.lon, z) - cx
                    val y = TileMath.worldY(p.lat, z) - cy
                    val inside = abs(x) < lim && abs(y) < lim
                    if (!started) {
                        if (!inside) continue
                        trackPath.moveTo(x.toFloat(), y.toFloat())
                        started = true
                        px = x; py = y
                    } else {
                        if (!inside) {
                            trackPath.lineTo(x.toFloat(), y.toFloat())
                            started = false
                            continue
                        }
                        if (abs(x - px) < minStep && abs(y - py) < minStep) continue
                        trackPath.lineTo(x.toFloat(), y.toFloat())
                        px = x; py = y
                    }
                }
            }
            val w = (s * 0.02f).coerceAtLeast(3f) / k
            trackEdge.strokeWidth = w * 1.8f
            trackLine.strokeWidth = w
            c.drawPath(trackPath, trackEdge)
            c.drawPath(trackPath, trackLine)
        }
        c.restore()
    }

    private fun angleDiff(a: Double, b: Double): Double {
        if (b.isNaN()) return 360.0
        var d = abs(a - b) % 360.0
        if (d > 180) d = 360 - d
        return d
    }

    companion object {
        const val ATTRIBUTION = "出典：国土地理院（地理院タイル）"
        private const val MISSING = 0xFFBDBDBD.toInt()
        private const val FRAME_BG = 0x99000000.toInt()
        private const val STRIP_BG = 0xCCFFFFFF.toInt()
        private const val TRACK = 0xFFE53935.toInt()
        private const val ARROW = 0xFF1E88E5.toInt()
        private const val NORTH = 0xFFD32F2F.toInt()
    }
}
