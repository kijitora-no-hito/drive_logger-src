package jp.kjtr.drivelogger.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.media3.common.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import jp.kjtr.drivelogger.dashcam.BurnLayout

/**
 * 覆いの描画（不透明な帯・円に「---」）。書き出しのオーバーレイと、画面の見本で使う。1 つのスレッドから使うこと。
 */
class CoverPainter {
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COVER; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rf = RectF()

    fun draw(canvas: Canvas, covers: List<Cover>) {
        for (c in covers) when (c) {
            is Cover.Box -> {
                val r = c.rect
                rf.set(r.left, r.top, r.right, r.bottom)
                // 地図の窓は角の丸みが小さい（枠の角がはみ出さないように）
                val radius = minOf(r.width, r.height) * if (c.kind == Cover.Kind.MAP) 0.05f else 0.18f
                canvas.drawRoundRect(rf, radius, radius, box)
                val size = when (c.kind) {
                    Cover.Kind.MAP -> minOf(r.width, r.height) * 0.16f
                    else -> r.height * 0.62f
                }
                text.textSize = size
                text.textAlign = when {
                    c.kind == Cover.Kind.MAP -> Paint.Align.CENTER
                    c.alignRight -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                val pad = r.height * 0.18f
                val x = when (text.textAlign) {
                    Paint.Align.CENTER -> (r.left + r.right) / 2
                    Paint.Align.RIGHT -> r.right - pad
                    else -> r.left + pad
                }
                val label = when (c.kind) {
                    Cover.Kind.SPEED -> "--- km/h"
                    Cover.Kind.LAT_LON -> "N--- E---"
                    Cover.Kind.MAP -> "---"
                }
                canvas.drawText(label, x, (r.top + r.bottom) / 2 + size * 0.36f, text)
            }
            is Cover.Disc -> {
                val cc = c.circle
                canvas.drawCircle(cc.cx, cc.cy, cc.r, box)
                ring.strokeWidth = cc.r * 0.03f
                canvas.drawCircle(cc.cx, cc.cy, cc.r * 0.955f, ring)
                text.textAlign = Paint.Align.CENTER
                text.textSize = cc.r * 0.34f
                canvas.drawText("---", cc.cx, cc.cy + cc.r * 0.12f, text)
                text.textSize = cc.r * 0.13f
                canvas.drawText("km/h", cc.cx, cc.cy + cc.r * 0.40f, text)
            }
        }
    }

    companion object {
        /** 覆いの色（不透明）。 */
        const val COVER = 0xFF1C1C1E.toInt()
    }
}

/**
 * 書き出す 1 本分のオーバーレイ（Media3 の BitmapOverlay）。フレームと同じ大きさの画像に覆いを描き、
 * 速度を隠す時間帯（[speedSpans]、このファイルの出力の時刻 0 起点 [ms]）に入った・出たときだけ描き直す
 * （画像の世代が変わったときだけ GPU に送り直されるので、普段は何もしない）。
 *
 * 時刻: Transformer はつないだ出力の時刻（前のファイルの長さを足したもの）を渡すので、[offsetMs] を引く。
 * ファイルの中の時刻で来る場合にも合うよう、最初のフレームで見分ける。
 */
@OptIn(UnstableApi::class)
class CoverOverlay(
    private val layout: BurnLayout,
    private val offsetMs: Long,
    private val speedSpans: List<Span>,
    private val hideSpeed: Boolean,
    private val hideLatLon: Boolean,
    private val hideMap: Boolean,
) : BitmapOverlay() {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val painter = CoverPainter()
    private var state = -1
    private var localTime: Boolean? = null
    private var alwaysCovers: List<Cover> = emptyList()
    private var speedCovers: List<Cover> = emptyList()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val w = videoSize.width.coerceAtLeast(1)
        val h = videoSize.height.coerceAtLeast(1)
        if (bitmap?.width != w || bitmap?.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvas = Canvas(it) }
            state = -1
        }
        alwaysCovers = CoverPlan.covers(layout, w, h, hideSpeed = false, hideLatLon = hideLatLon, hideMap = hideMap) ?: emptyList()
        speedCovers = if (hideSpeed) CoverPlan.covers(layout, w, h, hideSpeed = true, hideLatLon = false, hideMap = false) ?: emptyList() else emptyList()
        Log.i(TAG, "configure ${w}x$h layout=${layout.width}x${layout.height} always=${alwaysCovers.size} speed=${speedCovers.size} spans=${speedSpans.size}")
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val b = bitmap ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap = it; canvas = Canvas(it) }
        val ms = presentationTimeUs / 1000
        val isLocal = localTime ?: (offsetMs > 0 && ms < offsetMs - 500).also {
            localTime = it
            Log.i(TAG, "first frame pts=${ms}ms offset=${offsetMs}ms local=$it")
        }
        val t = if (isLocal) ms else ms - offsetMs
        val hidden = speedCovers.isNotEmpty() && speedSpans.any { t in it }
        val s = if (hidden) 1 else 0
        if (s != state) {
            state = s
            val c = canvas ?: return b
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            painter.draw(c, alwaysCovers)
            if (hidden) painter.draw(c, speedCovers)
        }
        return b
    }

    companion object {
        private const val TAG = "DL-SnsOverlay"
    }
}
