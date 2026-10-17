package jp.kjtr.drivelogger.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import jp.kjtr.drivelogger.dashcam.BurnInRenderer
import jp.kjtr.drivelogger.dashcam.DashcamEngine
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.map.TileSource

/**
 * あとから重ねる表示（Media3 の BitmapOverlay。0.4.0〜）。録画のときの焼き込みと同じ [BurnInRenderer] で、
 * フレームの時刻の GPS 記録の値を描く（入れない項目は描かない・速度を隠す時間帯は速度を描かない）。
 * 重いので [LaterOverlayPlan.REDRAW_MS] ごとに描き直す。フレームは正立で来る（CoverOverlay と同じ）。
 *
 * 時刻: Transformer はつないだ出力の時刻を渡すので [offsetMs] を引く（ファイルの中の時刻で来る場合にも合うよう最初のフレームで見分ける）。
 */
@OptIn(UnstableApi::class)
class LaterOverlay(
    private val plan: LaterOverlayPlan,
    private val offsetMs: Long,
    private val track: List<List<LatLon>>,
    tiles: TileSource?,
) : BitmapOverlay() {
    private val renderer = BurnInRenderer(DashcamEngine.APP_NAME, if (plan.config.map != null) tiles else null)
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var lastKey = Long.MIN_VALUE
    private var localTime: Boolean? = null
    private var w = 2
    private var h = 2

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        w = videoSize.width.coerceAtLeast(2)
        h = videoSize.height.coerceAtLeast(2)
        if (bitmap?.width != w || bitmap?.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvas = Canvas(it) }
            lastKey = Long.MIN_VALUE
        }
        Log.i(TAG, "configure ${w}x$h map=${plan.config.map != null} speed=${plan.config.speed} latLon=${plan.config.latLon}")
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val b = bitmap ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap = it; canvas = Canvas(it) }
        val ms = presentationTimeUs / 1000
        val isLocal = localTime ?: (offsetMs > 0 && ms < offsetMs - 500).also { localTime = it }
        val t = if (isLocal) ms else ms - offsetMs
        val f = plan.frame(t)
        val key = LaterOverlayPlan.redrawKey(t, f.speedDraw)
        if (key != lastKey) {
            lastKey = key
            val c = canvas ?: return b
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            runCatching { renderer.drawUpright(c, w, h, f.wallMs, f.values, f.config, track, f.speedDraw) }
                .onFailure { Log.w(TAG, "draw failed: ${it.message}") }
        }
        return b
    }

    companion object {
        private const val TAG = "DL-LaterOverlay"
    }
}
