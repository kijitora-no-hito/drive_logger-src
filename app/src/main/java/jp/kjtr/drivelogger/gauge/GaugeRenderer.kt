package jp.kjtr.drivelogger.gauge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.roundToInt

/**
 * メーターの描画（android.graphics.Canvas に描く。画面の HUD は Compose の drawIntoCanvas、録画の焼き込みは OverlayEffect の Canvas）。
 * 大きさはすべて半径 [r] に比例するので、画面でも動画でも同じ見た目になる。
 * 1 つのインスタンスは 1 つのスレッドから使うこと（Paint を使い回す。描くたびの割り当てはしない）。
 */
class GaugeRenderer {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val oval = RectF()
    private val path = Path()

    /** 速度計（針と目盛り。中に数字）。 */
    fun speed(canvas: Canvas, cx: Float, cy: Float, r: Float, kmh: Double?, maxKmh: Int) {
        disc(canvas, cx, cy, r)
        // 目盛りの帯（上限の 1/2 まで青、2/3 から先は赤み）
        val br = r * 0.86f
        band.strokeWidth = r * 0.05f
        oval.set(cx - br, cy - br, cx + br, cy + br)
        val start = GaugeMath.SPEED_START_DEG
        val sweep = GaugeMath.SPEED_SWEEP_DEG
        band.color = BAND
        canvas.drawArc(oval, start, sweep * 0.75f, false, band)
        band.color = BAND_HIGH
        canvas.drawArc(oval, start + sweep * 0.75f, sweep * 0.25f, false, band)
        // 目盛り
        val minor = GaugeMath.minorStep(maxKmh)
        val major = GaugeMath.majorStep(maxKmh)
        text.textSize = r * 0.13f
        var v = 0
        while (v <= maxKmh) {
            val a = GaugeMath.speedAngle(v.toDouble(), maxKmh)
            val isMajor = v % major == 0
            tick.strokeWidth = if (isMajor) r * 0.035f else r * 0.02f
            val r0 = if (isMajor) r * 0.70f else r * 0.76f
            canvas.drawLine(
                GaugeMath.polarX(cx, r0, a), GaugeMath.polarY(cy, r0, a),
                GaugeMath.polarX(cx, r * 0.83f, a), GaugeMath.polarY(cy, r * 0.83f, a), tick,
            )
            if (isMajor) {
                val label = LABELS.getOrNull(v / 10) ?: v.toString()
                canvas.drawText(label, GaugeMath.polarX(cx, r * 0.55f, a), GaugeMath.polarY(cy, r * 0.55f, a) + text.textSize * 0.36f, text)
            }
            v += minor
        }
        // 数字
        val digits = if (kmh == null || kmh.isNaN()) "--" else kmh.coerceAtLeast(0.0).roundToInt().toString()
        outlined(canvas, digits, cx, cy + r * 0.52f, r * 0.34f)
        outlined(canvas, "km/h", cx, cy + r * 0.74f, r * 0.12f)
        // 針
        val a = GaugeMath.speedAngle(kmh, maxKmh)
        needle.strokeWidth = r * 0.055f
        needle.color = if (kmh == null) DIM else ACCENT
        canvas.drawLine(
            GaugeMath.polarX(cx, r * 0.12f, a + 180f), GaugeMath.polarY(cy, r * 0.12f, a + 180f),
            GaugeMath.polarX(cx, r * 0.80f, a), GaugeMath.polarY(cy, r * 0.80f, a), needle,
        )
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.07f, fill)
    }

    /** コンパス（進む向きが上。文字盤が回る）。 */
    fun compass(canvas: Canvas, cx: Float, cy: Float, r: Float, headingDeg: Double?) {
        disc(canvas, cx, cy, r)
        canvas.save()
        canvas.rotate(GaugeMath.compassDialRotation(headingDeg), cx, cy)
        var d = 0
        while (d < 360) {
            val a = GaugeMath.bearingToCanvas(d.toDouble())
            val major = d % 90 == 0
            tick.strokeWidth = if (major) r * 0.05f else r * 0.03f
            val r0 = if (major) r * 0.72f else r * 0.78f
            canvas.drawLine(
                GaugeMath.polarX(cx, r0, a), GaugeMath.polarY(cy, r0, a),
                GaugeMath.polarX(cx, r * 0.88f, a), GaugeMath.polarY(cy, r * 0.88f, a), tick,
            )
            d += 30
        }
        text.textSize = r * 0.26f
        for (i in CARDINALS.indices) {
            val label = CARDINALS[i]
            val a = GaugeMath.bearingToCanvas(i * 90.0)
            val tx = GaugeMath.polarX(cx, r * 0.50f, a)
            val ty = GaugeMath.polarY(cy, r * 0.50f, a)
            text.color = if (i == 0) NORTH else Color.WHITE
            canvas.save()
            // 文字は盤といっしょに回す（文字の上が外を向く）
            canvas.rotate(i * 90f, tx, ty)
            canvas.drawText(label, tx, ty + text.textSize * 0.36f, text)
            canvas.restore()
        }
        text.color = Color.WHITE
        canvas.restore()
        // 上の印（進む向き）
        fill.color = ACCENT
        path.reset()
        path.moveTo(cx, cy - r * 0.98f)
        path.lineTo(cx - r * 0.13f, cy - r * 0.74f)
        path.lineTo(cx + r * 0.13f, cy - r * 0.74f)
        path.close()
        canvas.drawPath(path, fill)
        val deg = if (headingDeg == null || headingDeg.isNaN()) "--" else (((headingDeg.roundToInt() % 360) + 360) % 360).toString() + "°"
        outlined(canvas, deg, cx, cy + r * 0.10f, r * 0.24f)
    }

    /**
     * G メーター。向きの補正ができていれば点（体が押される向き）、できていなければ大きさの円だけ。
     * 外の円が [GaugeMath.G_FULL_SCALE]。
     */
    fun gMeter(canvas: Canvas, cx: Float, cy: Float, r: Float, g: GReading) {
        disc(canvas, cx, cy, r)
        val full = GaugeMath.G_FULL_SCALE
        ring.strokeWidth = r * 0.025f
        ring.color = GRID
        for (i in 1..3) canvas.drawCircle(cx, cy, r * 0.88f * i / 3f, ring)
        tick.strokeWidth = r * 0.02f
        tick.color = GRID
        canvas.drawLine(cx - r * 0.88f, cy, cx + r * 0.88f, cy, tick)
        canvas.drawLine(cx, cy - r * 0.88f, cx, cy + r * 0.88f, tick)
        tick.color = Color.WHITE
        ring.color = Color.WHITE
        val total = g.totalG
        if (g.calibrated && g.longG != null && g.latG != null) {
            val (dx, dy) = GaugeMath.gDot(g.longG, g.latG, r * 0.88f, full)
            fill.color = ACCENT
            canvas.drawCircle(cx + dx, cy + dy, r * 0.11f, fill)
            ring.strokeWidth = r * 0.025f
            canvas.drawCircle(cx + dx, cy + dy, r * 0.11f, ring)
        } else if (total != null) {
            ring.color = ACCENT
            ring.strokeWidth = r * 0.06f
            canvas.drawCircle(cx, cy, (total / full).toFloat().coerceIn(0.03f, 0.92f) * r * 0.88f, ring)
            ring.color = Color.WHITE
        }
        val label = if (total == null) "G --" else String.format(Locale.ROOT, "%.2fG", total)
        outlined(canvas, label, cx, cy + r * 0.70f, r * 0.20f)
    }

    private fun disc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, bg)
        ring.color = Color.WHITE
        ring.strokeWidth = r * 0.03f
        canvas.drawCircle(cx, cy, r * 0.985f, ring)
    }

    private fun outlined(canvas: Canvas, s: String, x: Float, baseline: Float, size: Float) {
        text.textSize = size
        textStroke.textSize = size
        textStroke.strokeWidth = size * 0.16f
        canvas.drawText(s, x, baseline, textStroke)
        canvas.drawText(s, x, baseline, text)
    }

    companion object {
        private val CARDINALS = arrayOf("N", "E", "S", "W")
        /** 目盛りの数字（10 km/h ごと。描くたびに文字列を作らない）。 */
        private val LABELS = Array(25) { (it * 10).toString() }
        private const val BG = 0xA6000000.toInt()
        private const val ACCENT = 0xFFFFB300.toInt()
        private const val DIM = 0xFF9E9E9E.toInt()
        private const val NORTH = 0xFFFF5252.toInt()
        private const val BAND = 0xFF42A5F5.toInt()
        private const val BAND_HIGH = 0xFFEF5350.toInt()
        private const val GRID = 0x80FFFFFF.toInt()
    }
}
