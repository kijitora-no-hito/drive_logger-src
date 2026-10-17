package jp.kjtr.drivelogger.share

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import jp.kjtr.drivelogger.dashcam.BurnLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 書き出す 1 本分（ファイル・範囲・覆い）。 */
data class ExportSource(
    val file: File,
    val part: ExportPart,
    /** 覆う配置（焼き込み無し・使えないなら null）。 */
    val layout: BurnLayout?,
    /** このファイルの中の、速度を隠す時間帯（ファイルの出力の 0 起点）。 */
    val speedSpans: List<Span>,
    val hasAudio: Boolean,
    /** あとから重ねる表示（焼き込みの無い動画。null なら重ねない）。0.4.0〜。 */
    val later: LaterSpec? = null,
)

/** あとから重ねる 1 本分（描く計画と地図の軌跡）。 */
data class LaterSpec(val plan: LaterOverlayPlan, val track: List<List<jp.kjtr.drivelogger.geo.LatLon>>)

data class ExportRequest(
    val sources: List<ExportSource>,
    val plan: ExportPlan,
    val hideSpeed: Boolean,
    val hideLatLon: Boolean,
    val hideMap: Boolean,
    val audio: Boolean,
    /** ファイル名に入れる（x / reels など）。 */
    val label: String,
    /** 書き出したらギャラリー（Movies/ドライブロガー）に保存する（動画の再生画面の［ギャラリーに書き出す］）。 */
    val toGallery: Boolean = false,
)

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val file: File, val sizeBytes: Long, val durationMs: Long, val request: ExportRequest) : ExportState
    data class Failed(val message: String) : ExportState
    data object Cancelled : ExportState
}

/**
 * SNS 用の書き出し（Media3 Transformer。DESIGN.md §5.1）。メインスレッドから使う。アプリに 1 つ（画面を離れても続く）。
 *
 * - 範囲の切り出し（ClippingConfiguration）・複数ファイルをつなぐ（EditedMediaItemSequence）。
 * - 効果: 覆い（[CoverOverlay]、切り抜き前の正立のフレームに）→ 切り抜き・縮小（Presentation）。
 * - H.264 + AAC、ビットレートは [ExportPlan.videoBps]。書き出し先はアプリ専用の領域 `Movies/sns/`（共有・ギャラリーへの保存はそこから）。
 * - **アップロードはしない**（共有の画面で各 SNS のアプリに渡すだけ）。
 */
@OptIn(UnstableApi::class)
class SnsExporter(private val context: Context, private val tiles: jp.kjtr.drivelogger.map.TileSource? = null) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var output: File? = null
    private var request: ExportRequest? = null
    private val progress = ProgressHolder()
    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                _state.value = ExportState.Running(progress.progress)
            }
            main.postDelayed(this, 300)
        }
    }

    val running: Boolean get() = transformer != null

    /** 書き出し中の依頼（ギャラリー向けかどうかを画面が見る）。 */
    val currentRequest: ExportRequest? get() = request

    fun dir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    fun start(req: ExportRequest) {
        if (transformer != null || req.sources.isEmpty()) return
        cleanOld()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(req.sources.first().part.wallStartMs + req.sources.first().part.inMs))
        val out = File(dir(), (if (req.toGallery) "DL_" else "DL_SNS_") + "${stamp}_${req.label}.mp4")
        if (out.exists()) out.delete()
        val plan = req.plan
        val allAudio = req.audio && req.sources.all { it.hasAudio }
        val items = req.sources.mapIndexed { i, s ->
            val offset = req.sources.take(i).sumOf { it.part.lengthMs }
            val media = MediaItem.Builder()
                .setUri(Uri.fromFile(s.file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s.part.inMs)
                        .setEndPositionMs(s.part.outMs)
                        .build(),
                )
                .build()
            val effects = ArrayList<androidx.media3.common.Effect>()
            val layout = s.layout
            if (layout != null && (req.hideSpeed || req.hideLatLon || req.hideMap)) {
                val overlay: TextureOverlay = CoverOverlay(layout, offset, s.speedSpans, req.hideSpeed, req.hideLatLon, req.hideMap)
                effects.add(OverlayEffect(ImmutableList.of(overlay)))
            }
            val crop = plan.crop
            effects.add(
                Presentation.createForWidthAndHeight(
                    plan.outW, plan.outH,
                    if (crop != null) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
            // あとから重ねる表示（入れない項目は描かない）は、切り抜き・縮小のあと＝出力の画面に合わせて配置する。焼き込んだ動画には覆いだけ
            s.later?.let { l -> effects.add(OverlayEffect(ImmutableList.of(LaterOverlay(l.plan, offset, l.track, tiles) as TextureOverlay))) }
            EditedMediaItem.Builder(media)
                .setRemoveAudio(!allAudio)
                .setEffects(Effects(ImmutableList.of(), effects))
                .build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBps).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "done ${out.name} ${out.length()} B dur=${exportResult.durationMs} ms vbr=${exportResult.averageVideoBitrate}")
                    finish(ExportState.Done(out, out.length(), exportResult.durationMs, req))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.w(TAG, "export failed: ${exportException.errorCodeName} ${exportException.message}", exportException)
                    out.delete()
                    finish(ExportState.Failed(exportException.errorCodeName))
                }
            })
            .build()
        transformer = t
        output = out
        request = req
        _state.value = ExportState.Running(0)
        Log.i(TAG, "start ${out.name}: ${req.sources.size} file(s) ${plan.outW}x${plan.outH} ${plan.videoBps} bps crop=${plan.crop} audio=$allAudio")
        try {
            t.start(composition, out.path)
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            finish(ExportState.Failed(e.javaClass.simpleName))
            return
        }
        main.postDelayed(poll, 300)
    }

    fun cancel() {
        val t = transformer ?: return
        runCatching { t.cancel() }
        output?.delete()
        finish(ExportState.Cancelled)
    }

    /** 終わった結果を画面が受け取ったら（閉じたら）戻す。 */
    fun reset() {
        if (transformer == null) _state.value = ExportState.Idle
    }

    private fun finish(s: ExportState) {
        main.removeCallbacks(poll)
        transformer = null
        output = null
        request = null
        _state.value = s
    }

    /** 古い書き出し（新しい [KEEP] 本より前）を消す。 */
    private fun cleanOld() {
        val files = dir().listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP - 1).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "DL-Sns"
        const val DIR_NAME = "sns"
        private const val KEEP = 5
    }
}
