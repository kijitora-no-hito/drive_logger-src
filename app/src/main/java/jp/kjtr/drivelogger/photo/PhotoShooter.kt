package jp.kjtr.drivelogger.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import jp.kjtr.drivelogger.DriveApp
import jp.kjtr.drivelogger.dashcam.BurnInRenderer
import jp.kjtr.drivelogger.dashcam.BurnInValues
import jp.kjtr.drivelogger.dashcam.DashcamEngine
import jp.kjtr.drivelogger.dashcam.PhotoMode
import jp.kjtr.drivelogger.data.PhotoEntity
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.service.DriveController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 写真を撮る窓口（DESIGN.md §3.9）。
 * - 録画中: 録画を止めずに [DashcamEngine.takeStill]（ImageCapture）か、結べない端末では [DashcamEngine.grabPreviewFrame]（プレビューのフレーム）。
 *   設定「録画中の写真にも焼き込む」が ON なら、撮った画像に動画と同じ焼き込み（[BurnInRenderer]）を描いてから保存する。
 * - 記録だけ・記録していないとき: アプリ内カメラ画面（ui/CameraScreen）が撮って [register] を呼ぶ。
 * どちらもフォトライブラリ（Pictures/ドライブロガー）に保存し、EXIF に日時・タイムゾーン・（設定で）位置を書いて photo 表に登録する。
 */
class PhotoShooter(private val app: DriveApp) {
    private val sound by lazy { runCatching { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }.getOrNull() }

    /**
     * シャッター音は端末の決まりに従う: Android 13 以上は [MediaActionSound.mustPlayShutterSound]（日本などで鳴らす義務がある端末）のときだけ鳴らす。
     * 12 以下は端末の決まりを調べられないので鳴らす（CameraX は自分では鳴らさない）。
     */
    fun playShutterIfRequired() {
        val must = if (Build.VERSION.SDK_INT >= 33) MediaActionSound.mustPlayShutterSound() else true
        if (must) runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
    }

    /** 写真の位置: 記録の最後の点（10 秒以内）、無ければ端末の測位（30 秒以内・精度 50 m 以内）。無ければ null（軌跡から補間する）。 */
    fun currentFix(): LatLon? {
        val st = app.session.state.value
        if (st?.lat != null && st.lon != null && st.lastFixAt != null && System.currentTimeMillis() - st.lastFixAt < 10_000) return LatLon(st.lat, st.lon)
        val l = app.locationHub.last.value ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 30_000 || (l.hasAccuracy() && l.accuracy > 50f)) return null
        return LatLon(l.latitude, l.longitude)
    }

    /**
     * アプリで撮ってフォトライブラリに保存済みの写真を登録する（今のトリップに紐づける）。
     * 駐車中（走行の GPS を止めている）は、撮るたびにその場で 1 回だけ測位して写真の位置にする（精度が出るまで最大 8 秒。取れなければ位置なし。
     * 走行の軌跡からの補間はしない）。写真はその駐車の区間に紐づく。
     */
    suspend fun register(uri: Uri, takenAt: Long, mode: String, burnIn: Boolean = false, fix: LatLon? = null): PhotoEntity {
        val parked = app.session.isParked
        val loc = when {
            fix != null -> fix
            parked -> measureHere()
            else -> currentFix()
        }
        val parkId = if (parked) app.session.currentParkId() else null
        return app.photos.addCaptured(
            uri, takenAt, app.session.tripId, loc, app.settings.settings.value.photoExifLocation, mode, burnIn,
            parkId = parkId, interpolate = !parked,
        )
    }

    private val _hereFix = kotlinx.coroutines.flow.MutableStateFlow<Pair<LatLon, Long>?>(null)
    /** 駐車中に最後に測った現在地と時刻（車までの方角・距離の目安。常時の測位はしない）。 */
    val hereFix: kotlinx.coroutines.flow.StateFlow<Pair<LatLon, Long>?> = _hereFix

    private val _measuring = kotlinx.coroutines.flow.MutableStateFlow(false)
    val measuring: kotlinx.coroutines.flow.StateFlow<Boolean> = _measuring

    /** その場で 1 回だけ測位する（駐車中の写真・［現在地を測る］）。取れなければ null。 */
    suspend fun measureHere(): LatLon? {
        _measuring.value = true
        try {
            val l = app.locationHub.oneShot() ?: return null
            val ll = LatLon(l.latitude, l.longitude)
            _hereFix.value = ll to System.currentTimeMillis()
            return ll
        } finally {
            _measuring.value = false
        }
    }

    /** 駐車を始めた・終えたら、前の現在地は消す。 */
    fun clearHere() {
        _hereFix.value = null
    }

    /** 録画中の撮影の結果。 */
    sealed interface Result {
        data class Ok(val photo: PhotoEntity) : Result
        /** 撮れなかった（[reason] はログ用）。 */
        data class Failed(val reason: String) : Result
    }

    /** 録画を止めずに撮る（メインスレッドから）。録画していなければ [Result.Failed]。 */
    suspend fun shootWhileRecording(): Result {
        val engine = app.dashcam
        val mode = engine.photoMode
        if (mode == PhotoMode.NONE) return Result.Failed("not recording")
        val now = System.currentTimeMillis()
        val s = app.settings.settings.value
        val burn = s.photoBurnIn && s.burnIn
        // 押した瞬間の焼き込みの値・軌跡・位置
        val values = engine.feed.get()
        val track = engine.trackFeed.get()
        val fix = currentFix()
        val name = PhotoRepository.captureName(now)
        val uri: Uri? = when (mode) {
            PhotoMode.STILL -> if (!burn) {
                val opts = ImageCapture.OutputFileOptions.Builder(
                    app.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PhotoRepository.captureValues(now),
                ).build()
                playShutterIfRequired()
                takeStill(engine, opts)
            } else {
                val tmp = File(app.cacheDir, "still_$now.jpg")
                playShutterIfRequired()
                val saved = takeStill(engine, ImageCapture.OutputFileOptions.Builder(tmp).build())
                val out = if (saved == null) null else withContext(Dispatchers.Default) {
                    app.photos.loadBitmap(Uri.fromFile(tmp), MAX_PX)?.let { burnInto(it, now, values, track) }
                }
                tmp.delete()
                out?.let { app.photos.saveToLibrary(it, name, now, quality = 95) }
            }
            PhotoMode.FRAME -> {
                val frame = engine.grabPreviewFrame() ?: return Result.Failed("no preview frame")
                playShutterIfRequired()
                val out = withContext(Dispatchers.Default) { if (burn) burnInto(frame, now, values, track) else frame }
                app.photos.saveToLibrary(out, name, now, quality = 95)
            }
            PhotoMode.NONE -> null
        }
        if (uri == null) return Result.Failed("save failed ($mode)")
        val p = register(uri, now, if (mode == PhotoMode.STILL) PhotoEntity.MODE_STILL else PhotoEntity.MODE_FRAME, burn, fix)
        Log.i(TAG, "photo while recording: mode=$mode burn=$burn uri=$uri id=${p.id}")
        return Result.Ok(p)
    }

    private suspend fun takeStill(engine: DashcamEngine, opts: ImageCapture.OutputFileOptions): Uri? = suspendCancellableCoroutine<Uri?> { cont ->
        val started = engine.takeStill(
            opts,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(output.savedUri ?: Uri.EMPTY)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "still failed: ${exception.imageCaptureError} ${exception.message}")
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
        if (!started && cont.isActive) cont.resume(null)
    }.takeIf { it != Uri.EMPTY }

    /** 正立の画像 [src] に、動画と同じ焼き込み（今の設定・押した瞬間の値）を描いた新しい画像。 */
    private fun burnInto(src: Bitmap, at: Long, values: BurnInValues, track: List<List<LatLon>>): Bitmap {
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val cfg = DriveController.burnConfig(app.settings.settings.value)
        val renderer = BurnInRenderer(DashcamEngine.APP_NAME, if (cfg.map != null) app.tiles else null)
        renderer.drawUpright(Canvas(out), out.width, out.height, at, values, cfg, track)
        return out
    }

    companion object {
        private const val TAG = "DL-Photo"
        /** 焼き込みを入れるときに読み込む長辺の上限 [px]。 */
        private const val MAX_PX = 4096
    }
}
