package jp.kjtr.drivelogger.dashcam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import jp.kjtr.drivelogger.gauge.GaugeRenderer
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.map.MapBurnRenderer
import jp.kjtr.drivelogger.map.TileSource

/** 速度の描き方（あとから重ねる方式で、しきい値を超えた時間帯は [OMIT]＝描かない、設定で [DASHES]＝「---」）。 */
enum class SpeedDraw { NORMAL, OMIT, DASHES }

/**
 * 焼き込みの描画（android.graphics.Canvas）。録画（OverlayEffect のスレッド）と設定の見本（画面）の両方で使う。
 * 白い文字に黒い縁取り。メーターは [GaugeRenderer]、地図は [MapBurnRenderer]（[tiles] が無ければ地図は描かない）。
 * 1 つのインスタンスは 1 つのスレッドから使うこと（Paint を使い回す）。
 */
class BurnInRenderer(private val appName: String, tiles: TileSource? = null) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val matrix = Matrix()
    private val gauges = GaugeRenderer()
    private val map = tiles?.let { MapBurnRenderer(it) }

    /**
     * バッファ [bufW]×[bufH]（回転 [rotationDegrees]）の Canvas に描く。Canvas は呼ぶ側で消しておく。
     * 正立の座標で描くよう変換を掛け、描き終わったら元に戻す。[track] は地図に重ねる軌跡（区間ごと）。
     */
    fun draw(
        canvas: Canvas, bufW: Int, bufH: Int, rotationDegrees: Int, timeMs: Long, values: BurnInValues, config: BurnInConfig,
        track: List<List<LatLon>> = emptyList(),
    ) {
        val (w, h) = BurnInLayout.uprightSize(rotationDegrees, bufW, bufH)
        canvas.save()
        matrix.setValues(BurnInLayout.uprightToBuffer(rotationDegrees, bufW, bufH).values())
        canvas.concat(matrix)
        drawUpright(canvas, w, h, timeMs, values, config, track)
        canvas.restore()
    }

    private val measurer = TextMeasure { text, size ->
        fill.textSize = size
        fill.measureText(text)
    }
    private var cachedFrame: BurnGeometry.Frame? = null
    private var cachedW = -1
    private var cachedH = -1
    private var cachedConfig: BurnInConfig? = null

    /** 正立 [w]×[h] での配置（[BurnGeometry.frame]。同じ大きさ・設定なら覚えておいたもの）。 */
    fun frame(w: Int, h: Int, config: BurnInConfig): BurnGeometry.Frame {
        val c = cachedFrame
        if (c != null && w == cachedW && h == cachedH && config == cachedConfig) return c
        return BurnGeometry.frame(w, h, config, measurer).also {
            cachedFrame = it
            cachedW = w
            cachedH = h
            cachedConfig = config
        }
    }

    /**
     * 正立した [w]×[h] の画面に、文字（またはメーター＋文字）の塊を [BurnInConfig.position] の隅へ、地図をその設定の隅へ描く。
     * 位置は [BurnGeometry]（録画した動画の clip に保存する配置と同じ計算）。速度と方位は幅の決まった枠に入れて描く。
     */
    fun drawUpright(
        canvas: Canvas, w: Int, h: Int, timeMs: Long, values: BurnInValues, config: BurnInConfig,
        track: List<List<LatLon>> = emptyList(),
        speedDraw: SpeedDraw = SpeedDraw.NORMAL,
    ) {
        val f = frame(w, h, config)
        val useGauges = f.layout.style == "GAUGES"
        val textConfig = if (useGauges) config.copy(speed = false, heading = false, g = false) else config
        val lines = BurnInText.lines(timeMs, values, textConfig, appName)

        // 速度を隠す時間帯（あとから重ねる方式）: 描かない（配置はそのまま＝ほかの表示が動かない）か「---」
        when (speedDraw) {
            SpeedDraw.NORMAL -> f.speedGauge?.let { gauges.speed(canvas, it.cx, it.cy, it.r, values.speedKmh, config.gaugeMaxKmh) }
            SpeedDraw.DASHES -> f.speedGauge?.let { gauges.speed(canvas, it.cx, it.cy, it.r, null, config.gaugeMaxKmh) }
            SpeedDraw.OMIT -> Unit
        }
        f.compass?.let { gauges.compass(canvas, it.cx, it.cy, it.r, values.headingDeg) }
        f.gMeter?.let { gauges.gMeter(canvas, it.cx, it.cy, it.r, values.vehicleG) }
        val n = minOf(lines.size, f.lineTops.size)
        for (i in 0 until n) {
            val l = lines[i]
            val size = if (l.small) f.small else f.big
            fill.textSize = size
            stroke.textSize = size
            stroke.strokeWidth = size * 0.16f
            // ベースラインは行の下から少し上
            val baseline = f.lineTops[i] + f.lineHeights[i] * 0.78f
            if (i == f.speedLine) {
                // 速度・方位は枠に入れる（幅が変わらないので、SNS 用の書き出しで速度だけを覆える）
                if (config.speed) when (speedDraw) {
                    SpeedDraw.NORMAL -> outlined(canvas, BurnInText.speed(values.speedKmh), f.speedX, baseline)
                    SpeedDraw.DASHES -> outlined(canvas, "--- km/h", f.speedX, baseline)
                    SpeedDraw.OMIT -> Unit
                }
                if (config.heading) outlined(canvas, BurnInText.heading(values.headingDeg), f.headingX, baseline)
                continue
            }
            val tw = fill.measureText(l.text)
            val x = if (f.right) w - f.margin - tw else f.margin
            outlined(canvas, l.text, x, baseline)
        }

        // 地図（地理院タイル）
        val mc = config.map
        if (mc != null && map != null && f.mapS > 0) {
            map.draw(canvas, f.mapX, f.mapY, f.mapS, values.lat, values.lon, values.headingDeg, track, mc, timeMs)
        }
    }

    private fun outlined(canvas: Canvas, text: String, x: Float, baseline: Float) {
        canvas.drawText(text, x, baseline, stroke)
        canvas.drawText(text, x, baseline, fill)
    }
}
