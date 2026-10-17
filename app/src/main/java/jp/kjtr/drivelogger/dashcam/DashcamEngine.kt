package jp.kjtr.drivelogger.dashcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.map.TileSource
import jp.kjtr.drivelogger.record.RecorderStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** 録画の設定（走行サービスが設定から作って渡す）。 */
data class DashcamConfig(
    val quality: VideoQuality,
    val fps: Int,
    val segmentMs: Long,
    val audio: Boolean,
    val burnIn: Boolean,
    val burn: BurnInConfig,
    val limitBytes: Long,
    val protectedLimitBytes: Long,
    /** 横長で録る（画面の向きによらず。0.4.0〜 既定）。 */
    val landscape: Boolean = false,
    /** 表示をあとから重ねる方式（焼き込まない。clip に印を付ける）。 */
    val laterOverlay: Boolean = false,
    /**
     * 録画の方式（0.7.0〜）。[RecordMode.LONG] は [segmentMs]（長時間録画の分割の長さ）ごとにファイルを分け、自動では消さない・保護しない。
     * 端末の空きが 1 GB を切ったら止める（[LongRecordingPolicy]）。
     */
    val mode: RecordMode = RecordMode.LOOP,
)

/** 録画の様子（画面・通知が読む）。 */
data class DashcamState(
    /** 録画 ON（カメラを使っている）。 */
    val active: Boolean = false,
    /** いまファイルに書いている。 */
    val recording: Boolean = false,
    val segmentStartedAt: Long? = null,
    val currentFile: String? = null,
    /** 焼き込みが効いている。 */
    val burnIn: Boolean = false,
    /** 焼き込みを使えない端末（焼き込み無しで録画している）。 */
    val burnInUnavailable: Boolean = false,
    /** 通常＋保護の合計 [byte]。 */
    val usedBytes: Long = 0,
    val protectedBytes: Long = 0,
    val freeBytes: Long = 0,
    val limitBytes: Long = 0,
    val lowSpace: Boolean = false,
    val protectedOverLimit: Boolean = false,
    /** カメラを他のアプリに取られた・開けない。 */
    val cameraError: Boolean = false,
    val lastProtectAt: Long? = null,
    /** 録画中の写真の撮り方（DESIGN.md §3.9）。 */
    val photoMode: PhotoMode = PhotoMode.NONE,
    // ---- 長時間録画（0.7.0〜。DESIGN.md §3.11） ----
    /** いまの録画の方式。 */
    val mode: RecordMode = RecordMode.LOOP,
    /** この録画（［録画］〜［録画 停止］）を始めた時刻。 */
    val startedAt: Long? = null,
    /** 長時間録画: この録画のファイルの合計 [byte]。 */
    val longBytes: Long = 0,
    /** 長時間録画: 空き容量で録れるおおよその残り時間 [時間]。 */
    val hoursLeft: Double? = null,
)

/**
 * 録画中に写真を撮る方法。[STILL] = Preview＋VideoCapture＋ImageCapture を同時に結べた（静止画を撮る。録画は止めない）。
 * [FRAME] = 同時に結べない端末・組み合わせ（ImageCapture 無しで結び直した）。画面のプレビュー（焼き込みの手前の映像）のフレームを写真にする。
 */
enum class PhotoMode { NONE, STILL, FRAME }

/** 録画の出来事（サービスがトリップの出来事・通知にする）。 */
sealed interface DashcamEvent {
    data class Stopped(val reason: StopReason) : DashcamEvent
    data class CameraLost(val code: Int) : DashcamEvent
    data object CameraBack : DashcamEvent
}

enum class StopReason { USER, STORAGE_FULL, ERROR }

/**
 * ドラレコの録画（DESIGN.md §3）。メインスレッドから使う。
 *
 * - 録画 ON の間だけ自前の LifecycleOwner を STARTED にし、Preview + VideoCapture（+ OverlayEffect）を 1 回で結ぶ。
 * - ループ録画: [DashcamConfig.segmentMs] ごとに今の Recording を stop() してすぐ次を start()（Recorder が仕上げを待って続けて始める）。
 * - 容量の管理（[ClipStore.enforce]）はファイルを始めるたびと 30 秒ごと。
 * - 保護: [protect]（[ProtectionPlanner] の範囲）。
 */
class DashcamEngine(
    private val context: Context,
    private val store: ClipStore,
    private val scope: CoroutineScope,
    private val tiles: TileSource? = null,
) {

    /** 焼き込む値（メインスレッドが書き、効果のスレッドが読む）。 */
    val feed = AtomicReference(BurnInValues())

    /** 焼き込む地図に重ねる軌跡（区間ごと。メインスレッドが書き、効果のスレッドが読む）。 */
    val trackFeed = AtomicReference<List<List<LatLon>>>(emptyList())

    private val _state = MutableStateFlow(DashcamState())
    val state: StateFlow<DashcamState> = _state

    private val _events = MutableSharedFlow<DashcamEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DashcamEvent> = _events

    private val main = Handler(Looper.getMainLooper())
    /** 焼き込みの配置を計算するだけ（メインスレッド。描く方は効果のスレッドの別のインスタンス）。 */
    private val layoutRenderer by lazy { BurnInRenderer(APP_NAME) }
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private class EngineLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.STARTED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    /** ファイル 1 本分。 */
    private class Seg(val token: Int, val file: File) {
        var recording: Recording? = null
        var startedAt: Long? = null
        var protectReason: String? = null
        var finalized = false
        /** このファイルを録った向き（Surface.ROTATION_*）。 */
        var rotation = Surface.ROTATION_0
        /** このファイルに焼き込んだ設定（焼き込み無しなら null）。 */
        var burn: BurnInConfig? = null
        /** clip 表の id（行ができたら。できなければ -1）。 */
        val id = CompletableDeferred<Long>()
    }

    private var owner: EngineLifecycle? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    /** ImageCapture を足すと結べなかった（この録画の間は FRAME で撮る）。 */
    private var imageCaptureFailed = false
    /** debug だけ: ImageCapture を結ばない（FRAME の撮り方を試す）。 */
    var debugNoImageCapture = false
    /** 画面のプレビューのフレームを取る（走行画面が見えている間だけ。メインスレッドで呼ぶ）。 */
    private var previewFrame: (() -> Bitmap?)? = null
    private var effect: OverlayEffect? = null
    private var effectThread: HandlerThread? = null
    private val framesDrawn = AtomicLong()
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var config: DashcamConfig? = null
    private var tripId: Long? = null
    /** 動画ログ（0.6.0〜。ファイルの行に入れる）。 */
    private var logId: Long? = null
    private var displayRotation = Surface.ROTATION_90
    private var current: Seg? = null
    private var previous: Seg? = null
    private var protectNext: String? = null
    private var stopping = false
    private var stopReason = StopReason.USER
    private var token = 0
    private var burnFailed = false

    private val rotateTask = Runnable { rotate() }
    private val retryTask = Runnable { if (owner != null && !stopping && current == null) startSegment() }
    private val storageTask = object : Runnable {
        override fun run() {
            checkStorage()
            main.postDelayed(this, STORAGE_CHECK_MS)
        }
    }
    private val stopTimeout = Runnable { finishStop() }

    val isActive: Boolean get() = owner != null

    /** 画面の回転（Surface.ROTATION_*）。次のファイルから反映する。 */
    fun setDisplayRotation(rotation: Int) {
        if (displayRotation == rotation) return
        displayRotation = rotation
        // 横で録る設定で、録り始めた直後に画面の向きが決まった（左右逆の横向き）なら、上下逆の動画にならないよう今のファイルを切り替える
        val cur = current
        val started = cur?.startedAt
        if (config?.landscape == true && cur != null && cur.rotation != targetRotation && started != null &&
            System.currentTimeMillis() - started < ROTATION_FIX_WINDOW_MS && !stopping
        ) {
            Log.i(TAG, "rotation settled ${cur.rotation} → $targetRotation: restart the segment")
            rotate()
        }
    }

    /**
     * 録画・静止画の向き。横で録る設定なら、画面がまだ縦（横向きへの切り替えの途中など）でも横長にする
     * （画面が横なら、その向き＝左右どちらに倒したか）。
     */
    private val targetRotation: Int get() {
        val r = displayRotation
        return if (config?.landscape == true && r != Surface.ROTATION_90 && r != Surface.ROTATION_270) Surface.ROTATION_90 else r
    }

    /**
     * 走行画面が見えている間だけプレビューをつなぐ（null で外す。録画は続く）。[frame] はプレビューの今のフレーム
     * （ImageCapture を結べない端末で、録画中の写真に使う）。
     */
    fun setPreviewSurface(sp: Preview.SurfaceProvider?, frame: (() -> Bitmap?)? = null) {
        previewFrame = if (sp == null) null else frame
        if (surfaceProvider === sp) return
        surfaceProvider = sp
        preview?.setSurfaceProvider(sp)
    }

    /** 録画中に写真を撮れるか（撮り方）。 */
    val photoMode: PhotoMode get() = when {
        owner == null || stopping || camera == null -> PhotoMode.NONE
        imageCapture != null -> PhotoMode.STILL
        else -> PhotoMode.FRAME
    }

    /**
     * 録画を止めずに静止画を撮る（[PhotoMode.STILL] のとき）。出力先は [options]。撮れなければ false（[cb] は呼ばない）。
     * メインスレッドから。
     */
    fun takeStill(options: ImageCapture.OutputFileOptions, cb: ImageCapture.OnImageSavedCallback): Boolean {
        val ic = imageCapture ?: return false
        if (owner == null || stopping) return false
        ic.targetRotation = targetRotation
        return runCatching { ic.takePicture(options, mainExecutor, cb) }.onFailure { Log.w(TAG, "takePicture failed: ${it.message}") }.isSuccess
    }

    /** 画面のプレビューの今のフレーム（[PhotoMode.FRAME] のとき。焼き込みの手前の映像・画面に見えている範囲）。メインスレッドから。 */
    fun grabPreviewFrame(): Bitmap? = runCatching { previewFrame?.invoke() }.getOrNull()

    /** 録画を始める（カメラを開いてループ録画）。走行サービスから。 */
    fun start(tripId: Long?, logId: Long?, cfg: DashcamConfig) {
        if (owner != null) return
        this.tripId = tripId
        this.logId = logId
        config = cfg
        stopping = false
        stopReason = StopReason.USER
        burnFailed = false
        imageCaptureFailed = false
        current = null
        previous = null
        protectNext = null
        val o = EngineLifecycle()
        owner = o
        o.start()
        _state.value = DashcamState(active = true, limitBytes = cfg.limitBytes, mode = cfg.mode, startedAt = System.currentTimeMillis())
        Log.i(TAG, "start mode=${cfg.mode} segment=${cfg.segmentMs} ms")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (owner !== o) return@addListener
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                Log.w(TAG, "camera provider unavailable")
                stopInternal(StopReason.ERROR)
                return@addListener
            }
            provider = p
            bind(withEffect = cfg.burnIn)
        }, mainExecutor)
        main.postDelayed(storageTask, STORAGE_CHECK_MS)
    }

    /** 録画を止める（今のファイルを仕上げてからカメラを閉じる）。 */
    fun stop() = stopInternal(StopReason.USER)

    private fun stopInternal(reason: StopReason) {
        if (owner == null || stopping) return
        stopping = true
        stopReason = reason
        main.removeCallbacks(rotateTask)
        main.removeCallbacks(retryTask)
        main.removeCallbacks(storageTask)
        val cur = current
        val rec = cur?.recording
        if (rec != null && !cur.finalized) {
            rec.stop()
            // 仕上がり（Finalize）を待ってから閉じる。来なければ 4 秒で閉じる。
            main.postDelayed(stopTimeout, 4_000)
        } else {
            finishStop()
        }
    }

    private fun finishStop() {
        main.removeCallbacks(stopTimeout)
        val o = owner ?: return
        runCatching { provider?.unbindAll() }
        o.destroy()
        owner = null
        camera = null
        preview = null
        videoCapture = null
        imageCapture = null
        runCatching { effect?.close() }
        effect = null
        effectThread?.quitSafely()
        effectThread = null
        current = null
        previous = null
        protectNext = null
        val reason = stopReason
        stopping = false
        _state.value = _state.value.copy(
            active = false, recording = false, segmentStartedAt = null, currentFile = null, cameraError = false, photoMode = PhotoMode.NONE,
            startedAt = null, hoursLeft = null,
        )
        _events.tryEmit(DashcamEvent.Stopped(reason))
        // 止めた後の容量も表示に反映（設定などが読むのはループの使用量）
        config = config?.copy(mode = RecordMode.LOOP)
        checkStorage()
    }

    private fun bind(withEffect: Boolean) {
        val o = owner ?: return
        val p = provider ?: return
        val cfg = config ?: return
        runCatching { p.unbindAll() }
        runCatching { effect?.close() }
        effect = null
        effectThread?.quitSafely()
        effectThread = null

        val pv = Preview.Builder().build()
        pv.setSurfaceProvider(surfaceProvider)
        val q = when (cfg.quality) {
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
            VideoQuality.UHD -> Quality.UHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .setTargetVideoEncodingBitRate(cfg.quality.bitrateFor(cfg.fps))
            .build()
        val vcb = VideoCapture.Builder(recorder).setTargetRotation(targetRotation)
        if (cfg.fps >= 60) vcb.setTargetFrameRate(Range(60, 60))
        val vc = vcb.build()

        // 録画中の写真: ImageCapture も一緒に結ぶ（録画を止めずに静止画を撮る）。焼き込みは VIDEO_CAPTURE だけに掛かるので素の写真になる
        val ic = if (!imageCaptureFailed && !debugNoImageCapture) {
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetRotation(targetRotation).build()
        } else null
        val eff = if (withEffect && !burnFailed) createEffect(cfg) else null
        val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).apply {
            if (ic != null) addUseCase(ic)
            if (eff != null) addEffect(eff)
        }.build()
        val cam = try {
            p.bindToLifecycle(o, CameraSelector.DEFAULT_BACK_CAMERA, group)
        } catch (e: Exception) {
            Log.w(TAG, "bind failed (effect=${eff != null}, still=${ic != null}): ${e.javaClass.simpleName} ${e.message}")
            runCatching { eff?.close() }
            effectThread?.quitSafely()
            effectThread = null
            if (ic != null) {
                // 3 つ同時に結べない端末・組み合わせ → ImageCapture を外して結び直す（写真はプレビューのフレームから）
                imageCaptureFailed = true
                bind(withEffect = withEffect)
            } else if (eff != null) {
                burnFailed = true
                _state.value = _state.value.copy(burnInUnavailable = true)
                bind(withEffect = false)
            } else if (cfg.fps >= 60) {
                // 60fps を受け付けない端末は 30fps で
                config = cfg.copy(fps = 30)
                bind(withEffect = withEffect)
            } else {
                stopInternal(StopReason.ERROR)
            }
            return
        }
        effect = eff
        camera = cam
        preview = pv
        videoCapture = vc
        imageCapture = ic
        Log.i(TAG, "bound: effect=${eff != null} still=${ic != null}")
        _state.value = _state.value.copy(burnIn = eff != null, photoMode = if (ic != null) PhotoMode.STILL else PhotoMode.FRAME)
        cam.cameraInfo.cameraState.observe(o) { onCameraState(it) }
        startSegment()
    }

    private fun createEffect(cfg: DashcamConfig): OverlayEffect? = try {
        val thread = HandlerThread("dl-burn-in").apply { start() }
        effectThread = thread
        val renderer = BurnInRenderer(APP_NAME, if (cfg.burn.map != null) tiles else null)
        framesDrawn.set(0)
        OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(thread.looper)) { t ->
            Log.w(TAG, "overlay effect error: ${t.javaClass.simpleName} ${t.message}")
            // 効果が動かない（GL を使えないなど）→ 焼き込み無しで結び直す
            main.post { onEffectError() }
        }.also { eff ->
            eff.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                renderer.draw(canvas, frame.size.width, frame.size.height, frame.rotationDegrees, System.currentTimeMillis(), feed.get(), cfg.burn, trackFeed.get())
                framesDrawn.incrementAndGet()
                true
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "overlay effect unavailable: ${e.message}")
        effectThread?.quitSafely()
        effectThread = null
        null
    }

    private fun onEffectError() {
        if (owner == null || stopping || burnFailed) return
        burnFailed = true
        _state.value = _state.value.copy(burnInUnavailable = true, burnIn = false)
        val cur = current
        if (cur?.recording != null && !cur.finalized) {
            // 今のファイルを仕上げてから焼き込み無しで結び直す
            pendingRebind = true
            stopReasonForRebind()
            cur.recording?.stop()
        } else {
            bind(withEffect = false)
        }
    }

    private var pendingRebind = false
    private fun stopReasonForRebind() {
        main.removeCallbacks(rotateTask)
    }

    private fun onCameraState(s: CameraState) {
        val err = s.error
        val st = _state.value
        if (err != null && (err.type == CameraState.ErrorType.CRITICAL || s.type == CameraState.Type.CLOSED || s.type == CameraState.Type.PENDING_OPEN)) {
            if (!st.cameraError) {
                Log.w(TAG, "camera error ${err.code} state=${s.type}")
                _state.value = st.copy(cameraError = true)
                _events.tryEmit(DashcamEvent.CameraLost(err.code))
            }
        } else if (s.type == CameraState.Type.OPEN && st.cameraError) {
            _state.value = st.copy(cameraError = false)
            _events.tryEmit(DashcamEvent.CameraBack)
            if (current == null && !stopping) startSegment()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        val vc = videoCapture ?: return
        val cfg = config ?: return
        if (stopping || owner == null) return
        vc.targetRotation = targetRotation
        val now = System.currentTimeMillis()
        val file = store.newFile(now)
        val seg = Seg(++token, file).also { it.rotation = targetRotation }
        val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val audio = cfg.audio && RecorderStatus.hasAudio(context)
        if (audio) pending.withAudioEnabled()
        // 前のファイルの仕上げ中でも、Recorder が保留して仕上がり次第すぐ始める
        val rec = try {
            pending.start(mainExecutor) { ev -> onRecordEvent(seg, ev, audio) }
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.javaClass.simpleName} ${e.message}")
            seg.id.complete(-1)
            current = null
            main.postDelayed(retryTask, RETRY_MS)
            return
        }
        seg.recording = rec
        protectNext?.let { seg.protectReason = it; protectNext = null }
        current = seg
    }

    private fun onRecordEvent(seg: Seg, ev: VideoRecordEvent, audio: Boolean) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                val now = System.currentTimeMillis()
                seg.startedAt = now
                val cfg = config
                seg.burn = if (effect != null) cfg?.burn else null
                val row = ClipEntity(
                    fileName = seg.file.name, tripId = tripId, logId = logId, startedAt = now, fps = cfg?.fps ?: 30,
                    hasAudio = audio, burnIn = effect != null, status = ClipEntity.STATUS_RECORDING, laterOverlay = effect == null && cfg?.laterOverlay == true,
                    protected = seg.protectReason != null, protectReason = seg.protectReason, protectedAt = seg.protectReason?.let { now },
                    recordMode = (cfg?.mode ?: RecordMode.LOOP).code,
                )
                scope.launch {
                    val id = runCatching { store.insert(row) }.getOrDefault(-1L)
                    seg.id.complete(id)
                    // 行ができる前に保護された分
                    val r = seg.protectReason
                    if (id > 0 && r != null && !row.protected) store.setProtected(id, true, r)
                }
                if (seg === current) {
                    _state.value = _state.value.copy(recording = true, segmentStartedAt = now, currentFile = seg.file.name)
                    main.removeCallbacks(rotateTask)
                    main.postDelayed(rotateTask, cfg?.segmentMs ?: DEFAULT_SEGMENT_MS)
                }
                checkStorage()
            }
            is VideoRecordEvent.Finalize -> {
                seg.finalized = true
                val err = ev.error
                val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                val size = seg.file.length()
                Log.i(TAG, "finalize ${seg.file.name} err=$err dur=${durMs}ms size=$size")
                scope.launch {
                    val id = if (seg.startedAt == null) { seg.id.complete(-1); -1L } else seg.id.await()
                    if (err == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA || size == 0L || durMs <= 0) {
                        runCatching { seg.file.delete() }
                        if (id > 0) store.clipNow(id)?.let { store.delete(it) }
                    } else if (id > 0) {
                        val (w, h) = ClipStore.uprightSizeOf(seg.file)
                        store.clipNow(id)?.let { c ->
                            // 焼き込みの配置（SNS 用の書き出しで速度・位置・地図を覆う場所）
                            val burn = seg.burn
                            val layout = if (c.burnIn && burn != null && w > 0 && h > 0) {
                                runCatching { layoutRenderer.frame(w, h, burn).layout.toJson() }.getOrNull()
                            } else null
                            store.update(
                                c.copy(
                                    endedAt = (seg.startedAt ?: c.startedAt) + durMs, durationMs = durMs, sizeBytes = size, width = w, height = h,
                                    status = if (err == VideoRecordEvent.Finalize.ERROR_NONE) ClipEntity.STATUS_DONE else ClipEntity.STATUS_FAILED,
                                    burnLayout = layout,
                                ),
                            )
                        }
                    }
                    // 仕上がったファイルも容量の判定に入れる（次のファイルの Start の時点ではまだ録画中の扱いのことがある）
                    if (owner != null) checkStorage()
                }
                if (seg === current) {
                    // 切り替え以外で終わった（エラー・止めた）
                    current = null
                    main.removeCallbacks(rotateTask)
                    _state.value = _state.value.copy(recording = false, segmentStartedAt = null, currentFile = null)
                    when {
                        stopping -> finishStop()
                        pendingRebind -> {
                            pendingRebind = false
                            previous = seg
                            bind(withEffect = false)
                        }
                        err == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> stopInternal(StopReason.STORAGE_FULL)
                        else -> {
                            previous = seg
                            main.postDelayed(retryTask, RETRY_MS)
                        }
                    }
                } else if (stopping && current == null) {
                    finishStop()
                }
            }
            else -> Unit
        }
    }

    /** 分割: 今のファイルを止めて、すぐ次を始める。 */
    private fun rotate() {
        val old = current ?: return
        if (stopping) return
        previous = old
        current = null
        old.recording?.stop()
        startSegment()
    }

    /**
     * 保護する（今のファイル・1 つ前・必要なら次）。録画していなければ 0。保護したファイルの数を返す（次のファイルを含む）。
     */
    fun protect(reason: String, at: Long = System.currentTimeMillis()): Int {
        if (owner == null || stopping) return 0
        val cfg = config ?: return 0
        // 長時間録画は自動では消さないので、保護は要らない（衝撃は出来事に残すだけ）
        if (cfg.mode == RecordMode.LONG) return 0
        val cur = current
        val started = cur?.startedAt
        val t = ProtectionPlanner.targets(at, started, cfg.segmentMs)
        var n = 0
        if (cur != null && (t.current || started == null)) { mark(cur, reason); n++ }
        if (t.previous) previous?.let { mark(it, reason); n++ }
        if (t.next && (cur == null || started != null)) { protectNext = reason; n++ }
        _state.value = _state.value.copy(lastProtectAt = at)
        checkStorage()
        return n
    }

    private fun mark(seg: Seg, reason: String) {
        if (seg.protectReason != null) return
        seg.protectReason = reason
        scope.launch {
            val id = seg.id.await()
            if (id > 0) store.setProtected(id, true, reason)
        }
    }

    private fun checkStorage() {
        val cfg = config ?: return
        if (cfg.mode == RecordMode.LONG && owner != null) {
            checkLongStorage(cfg)
            return
        }
        val active = current?.file?.name
        val next = estimateSegmentBytes(cfg.quality, cfg.fps, cfg.segmentMs, cfg.audio)
        scope.launch {
            val plan = runCatching { store.enforce(cfg.limitBytes, cfg.protectedLimitBytes, next, if (owner != null) active else null) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(
                usedBytes = plan.normalBytesAfter + plan.protectedBytes,
                protectedBytes = plan.protectedBytes,
                freeBytes = store.freeBytes(),
                lowSpace = plan.lowDeviceSpace,
                protectedOverLimit = plan.protectedOverLimit,
            )
            if (plan.cannotRecord && owner != null && !stopping) {
                Log.w(TAG, "no space for the next file")
                stopInternal(StopReason.STORAGE_FULL)
            }
        }
    }

    /**
     * 長時間録画の空き容量の判定（0.7.0〜。DESIGN.md §3.11）: 何も消さない。端末の空きが 1 GB を切ったら止める（GPS 記録は続ける）。
     * debug だけ [debugFreeBytes] で空きを小さく見せられる（空き不足で止まるのをエミュレータで確かめるため）。
     */
    private fun checkLongStorage(cfg: DashcamConfig) {
        val bph = LongRecordingPolicy.bytesPerHour(cfg.quality, cfg.fps, cfg.audio)
        scope.launch {
            val free = debugFreeBytes ?: store.freeBytes()
            val st = LongRecordingPolicy.check(free, bph)
            val bytes = runCatching { store.longBytes(logId) }.getOrDefault(0L)
            _state.value = _state.value.copy(freeBytes = free, lowSpace = st.low, hoursLeft = st.hoursLeft, longBytes = bytes, protectedOverLimit = false)
            if (st.stop && owner != null && !stopping) {
                Log.w(TAG, "long recording: free ${free / 1_048_576} MB < 1 GB → stop")
                stopInternal(StopReason.STORAGE_FULL)
            }
        }
    }

    /** debug だけ: 長時間録画の空き容量の判定に使う空き（null = 端末の空き）。 */
    var debugFreeBytes: Long? = null

    /** 設定画面などで今の使用量を読むとき（録画していなくても）。 */
    fun refreshUsage(cfg: DashcamConfig) {
        if (owner == null) config = cfg
        checkStorage()
    }

    companion object {
        private const val TAG = "DL-Dashcam"
        const val APP_NAME = "ドライブロガー"
        private const val RETRY_MS = 3_000L
        /** 録り始めてからこの間に画面の向きが決まったら、ファイルを切り替えて向きを合わせる。 */
        private const val ROTATION_FIX_WINDOW_MS = 8_000L
        private const val STORAGE_CHECK_MS = 30_000L
        private const val DEFAULT_SEGMENT_MS = 180_000L

        /** 端末の背面カメラで選べる画質と 60fps の可否。 */
        data class Capabilities(val qualities: Set<VideoQuality>, val fps60: Boolean)

        suspend fun capabilities(context: Context): Capabilities = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val result = runCatching {
                    val p = future.get()
                    val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
                        ?: return@runCatching Capabilities(emptySet(), false)
                    val qs = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
                    val set = HashSet<VideoQuality>()
                    if (Quality.HD in qs) set.add(VideoQuality.HD)
                    if (Quality.FHD in qs) set.add(VideoQuality.FHD)
                    if (Quality.UHD in qs) set.add(VideoQuality.UHD)
                    Capabilities(set, info.supportedFrameRateRanges.any { it.upper >= 60 })
                }.getOrDefault(Capabilities(emptySet(), false))
                if (cont.isActive) cont.resume(result)
            }, ContextCompat.getMainExecutor(context))
        }
    }
}
