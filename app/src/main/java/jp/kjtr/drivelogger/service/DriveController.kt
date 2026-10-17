package jp.kjtr.drivelogger.service

import jp.kjtr.drivelogger.DriveApp
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.DashcamConfig
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.Settings
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.map.MapBurnConfig
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.record.Restored
import jp.kjtr.drivelogger.record.StartFailure
import jp.kjtr.drivelogger.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 走行画面に一瞬出す知らせ（保護した・衝撃を検知した・GPS 記録も始めた）。 */
data class Flash(val type: String, val at: Long, val g: Double? = null, val files: Int = 0)

/** ドライブを始めるとき、何から記録するか（0.6.0〜。ドライブの開始＝記録の開始ではない）。 */
enum class StartMode { NONE, GPS, GPS_VIDEO }

/**
 * ドライブを開く・閉じる・再開する、GPS ログ・動画ログ（録画）の開始/停止、保護（画面・通知の操作の窓口。アプリに 1 つ）。
 * 点の取り込み・録画の分割・衝撃の監視はサービス（[DriveService]。何かを記録している間だけ動く）が行う。
 */
class DriveController(private val app: DriveApp) {

    private val _startError = MutableStateFlow<StartFailure?>(null)
    val startError: StateFlow<StartFailure?> = _startError

    private val _flash = MutableStateFlow<Flash?>(null)
    val flash: StateFlow<Flash?> = _flash

    /** 録画を使いたい（ユーザーが開始した）。熱・カメラの喪失で一時的に止まっても true のまま。 */
    private val _videoWanted = MutableStateFlow(false)
    val videoWanted: StateFlow<Boolean> = _videoWanted

    private val _nextVideoMode = MutableStateFlow(app.settings.settings.value.recordMode)
    /**
     * 次の［録画］の方式（0.7.0〜）。ドライブを開いたとき設定の既定に戻し、走行画面の録画ボタンの横の切替で変える（録画中は変えない）。
     */
    val nextVideoMode: StateFlow<RecordMode> = _nextVideoMode

    private val _videoMode = MutableStateFlow(RecordMode.LOOP)
    /** いま（最後に）始めた録画の方式。熱で止めて再開するときも同じ方式で。 */
    val videoMode: StateFlow<RecordMode> = _videoMode

    /** 次の［録画］の方式を変える（録画中は変えない＝止めて始め直す）。 */
    fun setNextVideoMode(m: RecordMode) {
        if (_videoWanted.value || app.dashcam.isActive) return
        _nextVideoMode.value = m
    }

    private fun resetNextVideoMode() {
        if (!_videoWanted.value && !app.dashcam.isActive) _nextVideoMode.value = app.settings.settings.value.recordMode
    }

    private val _interrupted = MutableStateFlow<Restored?>(null)
    /** 記録中のまま落ちたログがあった開いているドライブ（起動時に読み込んだもの。続けるか聞く）。 */
    val interrupted: StateFlow<Restored?> = _interrupted

    private val restoreLock = Mutex()
    private var restoredOnce = false

    fun dismissStartError() {
        _startError.value = null
    }

    fun showFlash(f: Flash) {
        _flash.value = f
    }

    /**
     * 開いているドライブを DB から読み込む（起動時に 1 回。サービスがシステムに再起動されたときも）。
     * 記録中のまま落ちたログがあれば [interrupted] に出す。
     */
    suspend fun restoreOpen(): Restored? = restoreLock.withLock {
        if (restoredOnce || app.session.isActive) return@withLock null
        restoredOnce = true
        val t = app.trips.openDrive() ?: return@withLock null
        resetNextVideoMode()
        val r = app.session.restore(t)
        if (r.interrupted) _interrupted.value = r
        r
    }

    /** 途中で終わった記録の知らせを消す（続けた・止めたままにした・終了した）。 */
    fun clearInterrupted() {
        _interrupted.value = null
    }

    /**
     * 新しいドライブを開く（前面から）。[mode] が GPS なら GPS ログも、GPS_VIDEO なら録画も始める。
     * 記録を始められなかったときも、ドライブは開いたまま（記録なし。理由は [startError]）。
     */
    suspend fun createDrive(mode: StartMode, videoMode: RecordMode? = null) {
        if (app.session.isActive) return
        val s = app.settings.settings.value
        val now = System.currentTimeMillis()
        if (mode != StartMode.NONE) app.settings.update { it.copy(lastStartWithVideo = mode == StartMode.GPS_VIDEO) }
        // ホームの［記録＋録画］も設定の既定の方式で録る（開始の確認で「ループ録画にする」を選んだときだけ [videoMode]）
        resetNextVideoMode()
        if (videoMode != null) _nextVideoMode.value = videoMode
        app.session.create(app.getString(R.string.trip_name, Format.dateForName(now)), s.autoPause)
        when (mode) {
            StartMode.NONE -> Unit
            StartMode.GPS -> startGps()
            StartMode.GPS_VIDEO -> if (startGps()) startVideo()
        }
    }

    /** GPS ログを始める（前面から。サービスも始める）。 */
    suspend fun startGps(): Boolean {
        if (!app.session.isActive) return false
        if (app.session.isGpsLogging) return ensureService()
        if (!RecorderStatus.hasLocation(app)) {
            _startError.value = StartFailure.PERMISSION
            return false
        }
        app.session.startGpsLog()
        val ok = ensureService()
        if (!ok) app.session.stopGpsLog()
        return ok
    }

    /** GPS ログを止める（録画中なら録画も止める＝動画の表示に GPS の記録が要るため）。 */
    fun stopGps() {
        if (_videoWanted.value || app.dashcam.isActive) stopVideo()
        app.session.stopGpsLog()
        stopServiceIfIdle()
    }

    /**
     * 録画を始める（前面から）。GPS ログが止まっていれば先に始める（あとから重ねる表示に GPS の記録を使うため）。
     * GPS ログを自分で始めたら走行画面に知らせる。
     */
    suspend fun startVideo(): Boolean {
        if (!app.session.isActive) return false
        if (!RecorderStatus.hasCamera(app)) {
            _startError.value = StartFailure.PERMISSION
            return false
        }
        val autoGps = !app.session.isGpsLogging
        if (autoGps && !startGps()) return false
        _videoMode.value = _nextVideoMode.value
        _videoWanted.value = true
        try {
            DriveService.send(app, DriveService.ACTION_VIDEO_ON)
        } catch (e: Exception) {
            _videoWanted.value = false
            _startError.value = RecorderStatus.classify(e)
            return false
        }
        if (autoGps) _flash.value = Flash(FLASH_GPS_AUTO, System.currentTimeMillis())
        return true
    }

    /** 録画を止める（GPS ログは続ける）。 */
    fun stopVideo() {
        _videoWanted.value = false
        if (app.recorder.serviceRunning.value) {
            runCatching { DriveService.send(app, DriveService.ACTION_VIDEO_OFF) }.onFailure { app.dashcam.stop() }
        } else {
            app.dashcam.stop()
            app.session.endVideoLog()
        }
    }

    /** 0.5.0 までの窓口（走行画面の録画 ON/OFF）。 */
    suspend fun setVideo(on: Boolean) {
        if (on) startVideo() else stopVideo()
    }

    /** 録画が止まった（容量・カメラのエラー）。録画ボタンを OFF に戻す。 */
    internal fun videoEnded() {
        _videoWanted.value = false
    }

    internal fun videoFailed(f: StartFailure) {
        _videoWanted.value = false
        _startError.value = f
    }

    /** すべての記録を止める（ドライブは開いたまま。通知の［記録を停止］）。 */
    fun stopRecording() {
        stopVideo()
        app.session.stopGpsLog()
        stopServiceIfIdle()
    }

    /** ［保護］（録画中だけ）。保護したファイルの数。 */
    fun protectNow(): Int {
        if (!app.dashcam.isActive) return 0
        val at = System.currentTimeMillis()
        val n = app.dashcam.protect(ClipEntity.REASON_MANUAL, at)
        if (n > 0) {
            app.session.addEvent(TripEventEntity.TYPE_PROTECT, at)
            _flash.value = Flash(TripEventEntity.TYPE_PROTECT, at, files = n)
        }
        return n
    }

    /**
     * ［駐車する］（停車中だけ。DESIGN.md §4.4）: 録画中なら録画を止め、GPS ログを閉じて駐車の位置を記録する（ドライブは続く）。
     * 徒歩の軌跡を記録する設定でなければ、記録が無くなるのでサービスも止まる。
     */
    fun park(): Boolean {
        if (!app.session.isActive || app.session.isParked) return false
        if (app.dashcam.isActive || _videoWanted.value) stopVideo()
        val ok = app.session.park()
        if (ok && !app.settings.settings.value.parkWalkTrack) stopServiceIfIdle() else if (ok) app.appScope.launch { ensureService() }
        return ok
    }

    /** ［走行を再開］: 駐車を閉じる。GPS ログを記録していたときの駐車なら、新しい GPS ログを始める。 */
    suspend fun resumeDrive(): Boolean {
        val restart = app.session.resumeDrive()
        if (restart) ensureService() else stopServiceIfIdle()
        return true
    }

    /** ドライブを閉じる（記録をすべて止める）。保存したドライブの id（何も記録が無ければ null）。 */
    suspend fun finish(): Long? {
        _videoWanted.value = false
        app.dashcam.stop()
        _interrupted.value = null
        val id = app.session.close()
        DriveService.stop(app)
        return id
    }

    /**
     * 閉じたドライブ [id] を開き直す（［このドライブを再開］）。ほかに開いているドライブがあれば、[closeOther] = true のときだけそれを終了してから。
     * 戻り値: 開けた / ほかが開いている（確かめる）/ 失敗。
     */
    suspend fun reopen(id: Long, closeOther: Boolean = false): ReopenResult {
        val current = app.session.tripId
        if (current == id) return ReopenResult.OPENED
        if (current != null) {
            if (!closeOther) return ReopenResult.OTHER_OPEN
            finish()
        }
        // DB にだけ開いたドライブが残っている（読み込み前）場合も閉じる
        app.trips.openDrive()?.takeIf { it.id != id }?.let { other ->
            if (!closeOther) return ReopenResult.OTHER_OPEN
            app.trips.closeDrive(other.id)
        }
        if (!app.trips.reopen(id)) return ReopenResult.FAILED
        val t = app.trips.tripNow(id) ?: return ReopenResult.FAILED
        resetNextVideoMode()
        app.session.restore(t)
        return ReopenResult.OPENED
    }

    /** 途中で終わった記録を続ける（前面から。新しい GPS ログを始める）。 */
    suspend fun continueInterrupted(): Boolean {
        val r = _interrupted.value ?: return false
        _interrupted.value = null
        app.session.addEvent(TripEventEntity.TYPE_INTERRUPTED)
        return startGps()
    }

    /** サービスが止まっている（走行画面の［再開］）。 */
    suspend fun restartService() {
        if (!app.session.isActive || app.recorder.serviceRunning.value) return
        startServiceAndWait()?.let { _startError.value = it }
    }

    /** 何かを記録しているのにサービスが動いていなければ始める。 */
    private suspend fun ensureService(): Boolean {
        if (app.recorder.serviceRunning.value) return true
        val failure = startServiceAndWait()
        if (failure != null) _startError.value = failure
        return failure == null
    }

    /** 何も記録していなければサービスを止める（サービス自身も見ているが、すぐ止めるため）。 */
    private fun stopServiceIfIdle() {
        val s = app.session.state.value
        val walk = s?.parked == true && app.settings.settings.value.parkWalkTrack
        if (s?.recording != true && !_videoWanted.value && !app.dashcam.isActive && !walk) DriveService.stop(app)
    }

    private suspend fun startServiceAndWait(): StartFailure? {
        app.recorder.setFailure(null)
        try {
            DriveService.start(app)
        } catch (e: Exception) {
            return RecorderStatus.classify(e)
        }
        val ok = withTimeoutOrNull(4_000) {
            combine(app.recorder.serviceRunning, app.recorder.startFailure) { running, f -> running to f }
                .first { it.first || it.second != null }
        }
        return when {
            ok == null -> StartFailure.TIMEOUT
            ok.first -> null
            else -> ok.second
        }
    }

    enum class ReopenResult { OPENED, OTHER_OPEN, FAILED }

    companion object {
        /** [Flash.type]: 録画中に写真を撮った（小さく「撮りました」）。 */
        const val FLASH_PHOTO = "photo"
        /** [Flash.type]: 録画を始めたので GPS 記録も自動で始めた。 */
        const val FLASH_GPS_AUTO = "gps_auto"
        /** [Flash.type]: 長時間録画を空き容量不足（1 GB 未満）で止めた（GPS 記録は続く）。 */
        const val FLASH_LONG_STORAGE = "long_storage"

        /** 録画の設定（[mode] = 録画の方式。長時間録画は長時間録画の分割の長さ）。 */
        fun dashcamConfig(s: Settings, mode: RecordMode = RecordMode.LOOP): DashcamConfig = DashcamConfig(
            mode = mode,
            quality = s.quality,
            fps = s.fps,
            segmentMs = (if (mode == RecordMode.LONG) s.longSegmentMinutes else s.segmentMinutes) * 60_000L,
            audio = s.audio,
            // あとから重ねる方式（既定）は焼き込まない（表示なしのきれいな映像で録る）
            burnIn = s.burnIn && s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.BURN,
            burn = burnConfig(s),
            limitBytes = s.storageLimitMb.toLong() * 1024 * 1024,
            protectedLimitBytes = s.protectedLimitMb.toLong() * 1024 * 1024,
            landscape = s.videoOrientation == jp.kjtr.drivelogger.data.VideoOrientation.LANDSCAPE,
            laterOverlay = s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.LATER,
        )

        fun burnConfig(s: Settings) = BurnInConfig(
            date = s.burnDate, speed = s.burnSpeed, latLon = s.burnLatLon, heading = s.burnHeading,
            g = s.burnG, appName = s.burnAppName, position = s.burnPosition, size = s.burnSize,
            style = s.burnStyle, gaugeMaxKmh = s.gaugeMaxKmh, map = mapConfig(s),
        )

        /** 焼き込む地図の設定（OFF なら null）。 */
        fun mapConfig(s: Settings): MapBurnConfig? = if (!s.burnMap) null else MapBurnConfig(
            layer = s.burnMapLayer, zoom = s.burnMapZoom, headingUp = s.burnMapHeadingUp, sizeRatio = s.burnMapSize.mapRatio,
            position = s.burnMapPosition, track = s.burnMapTrack,
        )
    }
}
