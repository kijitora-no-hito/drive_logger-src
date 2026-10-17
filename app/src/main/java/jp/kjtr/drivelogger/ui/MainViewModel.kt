package jp.kjtr.drivelogger.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.kjtr.drivelogger.DriveApp
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInRenderer
import jp.kjtr.drivelogger.dashcam.BurnLayout
import jp.kjtr.drivelogger.dashcam.DashcamEngine
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.Settings
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.gpx.GpxWriter
import jp.kjtr.drivelogger.playback.SyncPoint
import jp.kjtr.drivelogger.service.DriveController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as DriveApp
    val settings: StateFlow<Settings> = app.settings.settings
    val session = app.session
    val trips = app.trips
    val clips = app.clips
    val dashcam = app.dashcam
    val controller = app.controller
    val recorder = app.recorder
    val locationHub = app.locationHub
    val updates = app.updates

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _nav = Channel<String>(Channel.BUFFERED)
    val navRequests = _nav.receiveAsFlow()

    /** 記録中のまま落ちたログがあった開いているドライブ（起動時に読み込んだもの）。 */
    val interrupted = controller.interrupted

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)
    private fun say(id: Int, vararg args: Any) {
        _messages.trySend(str(id, *args))
    }

    fun updateSettings(f: (Settings) -> Settings) = app.settings.update(f)

    // ---- ドライブ（0.6.0〜 ドライブは GPS ログ・動画ログの入れ物。開始＝記録の開始ではない） ----

    /** ホームから新しいドライブを開く（[mode] で記録も始める。[videoMode] = この回だけの録画の方式、null なら設定の既定）。開いたら走行画面へ。 */
    fun startDrive(mode: jp.kjtr.drivelogger.service.StartMode, videoMode: jp.kjtr.drivelogger.dashcam.RecordMode? = null) = viewModelScope.launch {
        controller.createDrive(mode, videoMode)
        if (session.isActive) _nav.trySend(ROUTE_DRIVE)
    }

    /**
     * 走行中（10 km/h 超）で操作を制限するか（設定「走行中は操作を制限」）。走行画面のメニュー・戻る、ホームの一覧・設定などへの入口で使う。
     * 記録していないときは、画面が取っている端末の測位の速さで見る。
     */
    fun restrictedNow(): Boolean {
        if (!settings.value.restrictWhileMoving) return false
        val d = session.state.value ?: return false
        if (d.parked) return false
        val now = System.currentTimeMillis()
        val fixAt = d.lastFixAt
        if (fixAt != null && now - fixAt < 5_000) return d.currentSpeedMps * 3.6 > MOVING_KMH
        val l = locationHub.last.value ?: return false
        val fresh = android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos < 5_000_000_000L
        return fresh && l.hasSpeed() && l.speed * 3.6 > MOVING_KMH
    }

    /** ［GPS 記録 開始］。 */
    fun startGps() = viewModelScope.launch { controller.startGps() }

    /** ［GPS 記録 停止］（録画中なら録画も止まる）。 */
    fun stopGps() = controller.stopGps()

    /** ［録画 開始］（GPS 記録が止まっていれば一緒に始める）/［録画 停止］（GPS 記録は続ける）。 */
    fun setVideo(on: Boolean) = viewModelScope.launch { controller.setVideo(on) }

    fun protect() {
        if (controller.protectNow() == 0) say(R.string.msg_protect_nothing)
    }

    /** ［駐車する］（停車中だけ）。GPS ログを閉じて駐車の位置を記録する（ドライブは続く）。 */
    fun park() {
        if (controller.park()) shooter.clearHere()
    }

    /** ［走行を再開］。 */
    fun resumeDrive() = viewModelScope.launch {
        if (controller.resumeDrive()) shooter.clearHere()
    }

    /** 駐車中の［現在地を測る］（その場で 1 回だけ。常時の測位はしない）。 */
    fun measureHere() = viewModelScope.launch {
        if (shooter.measureHere() == null) say(R.string.parked_measure_failed)
    }

    /** ［ドライブを終了］。保存したら詳細へ（何も記録が無ければ保存せずホームへ）。 */
    fun finishDrive() = viewModelScope.launch {
        val id = controller.finish()
        if (id == null) {
            say(R.string.msg_no_records)
            _nav.trySend(ROUTE_HOME)
        } else _nav.trySend("trip/$id?finished=true")
    }

    /** 途中で終わった記録を続ける（新しい GPS ログ）。 */
    fun continueInterrupted() = viewModelScope.launch {
        if (controller.continueInterrupted()) _nav.trySend(ROUTE_DRIVE)
    }

    /** 途中で終わった記録は止めたまま、ドライブは開いておく。 */
    fun keepInterruptedStopped() = controller.clearInterrupted()

    /** 途中で終わった記録のドライブを終了して保存する。 */
    fun finishInterrupted() = viewModelScope.launch {
        val name = controller.interrupted.value?.trip?.name
        controller.clearInterrupted()
        val id = controller.finish()
        if (id == null) say(R.string.msg_no_records) else say(R.string.msg_saved_trip, name ?: "")
    }

    /**
     * ［このドライブを再開］。ほかのドライブが開いていて [closeOther] でなければ [onOtherOpen]（確かめる）。開けたら走行画面へ。
     */
    fun reopenTrip(id: Long, closeOther: Boolean, onOtherOpen: () -> Unit) = viewModelScope.launch {
        when (controller.reopen(id, closeOther)) {
            DriveController.ReopenResult.OPENED -> {
                say(R.string.msg_reopened)
                _nav.trySend(ROUTE_DRIVE)
            }
            DriveController.ReopenResult.OTHER_OPEN -> onOtherOpen()
            DriveController.ReopenResult.FAILED -> say(R.string.msg_reopen_failed)
        }
    }

    fun restartService() = viewModelScope.launch { controller.restartService() }

    // ---- トリップ ----

    fun renameTrip(id: Long, name: String) = viewModelScope.launch { trips.rename(id, name) }
    fun deleteTrip(id: Long) = viewModelScope.launch {
        // 写真の紐づけは CASCADE で消える（写真の本体はフォトライブラリに残る）。縮小版だけ先に消す
        photos.deleteThumbsOfTrip(id)
        trips.delete(id)
    }

    /** GPS ログ・動画ログを消す（記録中のものは消せない）。動画ログは動画のファイルも消す。 */
    fun deleteLog(log: jp.kjtr.drivelogger.data.DriveLogEntity) = viewModelScope.launch {
        // 開いているドライブで記録している間は消さない（走行中の統計が合わなくなる）
        if (session.tripId == log.tripId && session.state.value?.recording == true) {
            say(R.string.msg_log_delete_recording)
            return@launch
        }
        val ok = if (log.isGps) {
            trips.deleteGpsLog(log.id)
        } else {
            if (log.endedAt == null) false else {
                for (c in trips.clipsOfLog(log.id)) if (c.status != ClipEntity.STATUS_RECORDING) clips.delete(c)
                dashcam.refreshUsage(DriveController.dashcamConfig(settings.value))
                trips.deleteVideoLogRow(log.id)
            }
        }
        // 開いているドライブなら走行中の統計も読み直す
        if (ok && session.tripId == log.tripId) {
            trips.tripNow(log.tripId)?.let { session.restore(it) }
        }
        say(if (ok) R.string.msg_log_deleted else R.string.msg_log_delete_failed)
    }

    fun tripFileBase(t: TripEntity): String = Format.safeFileName("drive_" + Format.fileStamp(t.startedAt))

    /** GPX の写真の wpt の名前（設定で写真を入れないなら null）。 */
    private fun gpxPhotoNames(): ((Long) -> String)? = if (settings.value.gpxPhotos) { t -> str(R.string.photo_wpt_name, Format.time(t)) } else null

    /** GPS ログ 1 本の GPX の名前（「GPS 2」など）。 */
    fun logGpxName(index: Int): String = str(R.string.log_gpx_name, index)

    /** GPX を書き出す（[logId] があれば GPS ログ 1 本、無ければドライブ全体＝ログごとに trkseg）。 */
    fun exportGpx(tripId: Long, uri: Uri, logId: Long? = null, logIndex: Int = 0) = viewModelScope.launch {
        val t = trips.tripNow(tripId) ?: return@launch
        val data = trips.toGpx(
            tripId, source = settings.value.gpxSource, photoNames = gpxPhotoNames(), walkName = str(R.string.walk_track_name),
            logId = logId, logName = if (logId != null) logGpxName(logIndex) else null,
        ) ?: return@launch
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri, "wt")?.use { os -> OutputStreamWriter(os, Charsets.UTF_8).use { w -> GpxWriter.write(w, data, t.startedAt) } } ?: error("no stream")
            }.isSuccess
        }
        say(if (ok) R.string.msg_exported else R.string.msg_export_failed)
    }

    /** 共有用の GPX を cache/share/ に書いて共有の画面を出す。 */
    fun shareGpx(context: Context, tripId: Long, logId: Long? = null, logIndex: Int = 0) = viewModelScope.launch {
        val t = trips.tripNow(tripId) ?: return@launch
        val data = trips.toGpx(
            tripId, source = settings.value.gpxSource, photoNames = gpxPhotoNames(), walkName = str(R.string.walk_track_name),
            logId = logId, logName = if (logId != null) logGpxName(logIndex) else null,
        ) ?: return@launch
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(app.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, tripFileBase(t) + (if (logId != null) "_gps$logIndex" else "") + ".gpx")
                f.outputStream().use { os -> OutputStreamWriter(os, Charsets.UTF_8).use { w -> GpxWriter.write(w, data, t.startedAt) } }
                FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, str(R.string.share))) }
    }

    // ---- 写真（0.4.0〜。DESIGN.md §3.9） ----

    val photos = app.photos
    val shooter = app.shooter

    private val _lastCapturedThumb = MutableStateFlow<String?>(null)
    /** カメラ画面の左下に出す、最後に撮った写真の縮小版。 */
    val lastCapturedThumb: StateFlow<String?> = _lastCapturedThumb

    private val _photoBusy = MutableStateFlow(false)
    /** 録画中の撮影を処理中（連打しない）。 */
    val photoBusy: StateFlow<Boolean> = _photoBusy

    fun toast(text: String) {
        _messages.trySend(text)
    }

    /** アプリ内カメラで撮れた（フォトライブラリに保存済み）。今のトリップ（記録していなければ null）に紐づける。 */
    fun onPhotoCaptured(uri: Uri, takenAt: Long) = viewModelScope.launch {
        val p = shooter.register(uri, takenAt, jp.kjtr.drivelogger.data.PhotoEntity.MODE_CAMERA)
        _lastCapturedThumb.value = p.thumbPath
    }

    /** 走行画面の［写真］（録画中）。録画を止めずに撮って、小さく「撮りました」を出すだけ。 */
    fun takePhotoWhileRecording() {
        if (_photoBusy.value) return
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                when (val r = shooter.shootWhileRecording()) {
                    is jp.kjtr.drivelogger.photo.PhotoShooter.Result.Ok -> {
                        _lastCapturedThumb.value = r.photo.thumbPath
                        controller.showFlash(jp.kjtr.drivelogger.service.Flash(DriveController.FLASH_PHOTO, System.currentTimeMillis()))
                    }
                    is jp.kjtr.drivelogger.photo.PhotoShooter.Result.Failed -> {
                        Log.w(TAG, "photo failed: ${r.reason}")
                        say(R.string.camera_save_failed)
                    }
                }
            } finally {
                _photoBusy.value = false
            }
        }
    }

    /** トリップの詳細の［この時間に撮った写真を追加］（Photo Picker）。 */
    fun importPhotos(tripId: Long, uris: List<Uri>) = viewModelScope.launch {
        val t = trips.tripNow(tripId) ?: return@launch
        val r = photos.importPicked(uris, t)
        say(R.string.msg_photos_imported, r.added, r.outsideTime + r.noTime, r.duplicate)
        if (r.noLocation > 0) say(R.string.msg_photos_no_location, r.noLocation)
    }

    fun unlinkPhoto(p: jp.kjtr.drivelogger.data.PhotoEntity) = viewModelScope.launch { photos.unlink(p) }

    // ---- 動画 ----

    fun setClipProtected(c: ClipEntity, on: Boolean) = viewModelScope.launch {
        clips.setProtected(c.id, on, ClipEntity.REASON_MANUAL)
        say(if (on) R.string.msg_clip_protected else R.string.msg_clip_unprotected)
        dashcam.refreshUsage(DriveController.dashcamConfig(settings.value))
    }

    fun deleteClip(c: ClipEntity) = viewModelScope.launch {
        if (c.status == ClipEntity.STATUS_RECORDING) return@launch
        clips.delete(c)
        say(R.string.msg_clip_deleted)
        dashcam.refreshUsage(DriveController.dashcamConfig(settings.value))
    }

    fun shareClip(context: Context, c: ClipEntity) {
        val f = clips.fileOf(c)
        val uri = runCatching { FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, str(R.string.share))) }
    }

    /** SNS 用に書き出した動画をギャラリー（Movies/ドライブロガー/SNS）に保存する。 */
    fun saveSnsToGallery(f: File, takenAt: Long) = viewModelScope.launch {
        val ok = copyToGallery(f, f.name, takenAt, GALLERY_FOLDER + "/" + SNS_FOLDER)
        say(if (ok) R.string.msg_sns_gallery_done else R.string.msg_gallery_failed)
    }

    /** 書き出した動画を共有の画面で渡す（各 SNS のアプリへ。アップロードはこのアプリではしない）。 */
    fun shareFile(context: Context, f: File) {
        val uri = runCatching { FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, str(R.string.sns_share_chooser))) }
    }

    /**
     * 動画 [c] の焼き込みの配置。保存したもの（0.3.0〜）、無ければ今の設定から推定（[BurnLayout.estimated]）。焼き込み無しなら null。
     */
    fun burnLayoutOf(c: ClipEntity): BurnLayout? {
        if (!c.burnIn) return null
        BurnLayout.fromJson(c.burnLayout)?.let { return it }
        if (c.width <= 0 || c.height <= 0) return null
        return estimateRenderer.frame(c.width, c.height, DriveController.burnConfig(settings.value)).layout.copy(estimated = true)
    }

    private val estimateRenderer by lazy { BurnInRenderer(DashcamEngine.APP_NAME) }

    // ---- あとから重ねる表示（0.4.0〜。DESIGN.md §3.10） ----

    /** 表示の項目の既定（設定の「表示」の項目。表示を入れない設定なら何も入れない）。地図は地図を入れる設定（地理院タイルの通信の同意）が ON のときだけ。 */
    fun defaultOverlayItems(s: Settings = settings.value): jp.kjtr.drivelogger.share.OverlayItems =
        if (!s.burnIn) jp.kjtr.drivelogger.share.OverlayItems(false, false, false, false, false, false)
        else jp.kjtr.drivelogger.share.OverlayItems(
            date = s.burnDate, speed = s.burnSpeed, heading = s.burnHeading, latLon = s.burnLatLon, map = s.burnMap, appName = s.burnAppName,
        )

    /** あとから重ねられる動画か（焼き込んでいない動画はどれも）。 */
    fun canOverlayLater(c: ClipEntity): Boolean = !c.burnIn

    /** 書き出す 1 本分の、あとから重ねる表示の計画。 */
    fun laterSpec(
        part: jp.kjtr.drivelogger.share.ExportPart, items: jp.kjtr.drivelogger.share.OverlayItems, points: List<SyncPoint>,
        hideSpans: List<jp.kjtr.drivelogger.share.Span>, dashes: Boolean,
    ): jp.kjtr.drivelogger.share.LaterSpec? {
        if (!items.any) return null
        val plan = jp.kjtr.drivelogger.share.LaterOverlayPlan(
            DriveController.burnConfig(settings.value), items, jp.kjtr.drivelogger.playback.TrackSync(points),
            part.wallStartMs + part.inMs, hideSpans, dashes,
        )
        return jp.kjtr.drivelogger.share.LaterSpec(plan, jp.kjtr.drivelogger.share.LaterOverlayPlan.track(points))
    }

    /** 地図を重ねて書き出す前に、走った範囲の地理院タイルをそろえる（最大 [timeoutMs]）。 */
    suspend fun warmMapTiles(points: List<SyncPoint>, fromWall: Long, toWall: Long, videoW: Int, videoH: Int, timeoutMs: Long = 20_000) {
        val map = DriveController.mapConfig(settings.value) ?: return
        val samples = points.filter { it.timeMs in (fromWall - 5_000)..(toWall + 5_000) }
            .let { jp.kjtr.drivelogger.geo.Simplify.stride(it, 120) }
            .map { Triple(it.lat, it.lon, it.bearingDeg) }
        if (samples.isEmpty()) return
        val win = jp.kjtr.drivelogger.map.MapGeometry.windowSize(map.sizeRatio, videoW.coerceAtLeast(2), videoH.coerceAtLeast(2))
        val (ok, n) = app.tiles.warmAlong(map.layer, map.zoom, samples, jp.kjtr.drivelogger.map.MapGeometry.viewRadiusPx(win), timeoutMs)
        Log.i(TAG, "map tiles for export: $ok / $n")
    }

    /**
     * 動画をギャラリーに書き出す（再生画面）。焼き込みの無い動画で「表示を重ねる」なら、表示を描いて重ねた動画を作ってから保存する
     * （元の画質・ビットレートのまま）。重ねないならファイルをそのまま写す。
     */
    fun exportClipToGallery(c: ClipEntity, overlay: Boolean, items: jp.kjtr.drivelogger.share.OverlayItems = defaultOverlayItems()) =
        exportClipsToGallery(listOf(c), overlay, items)

    /**
     * 動画 [list]（長時間録画の続きのファイルなど。0.7.0〜）をギャラリーに書き出す。
     * - 表示を重ねない: ファイルごとにそのまま写す（n 本。速い・画質そのまま）。
     * - 表示を重ねる: つないで（EditedMediaItemSequence）表示を描いた 1 本を作ってから保存する（元のビットレートのまま）。
     * どちらも端末の空きを先に確かめる（重ねるときは作る分と写す分の 2 倍）。
     */
    fun exportClipsToGallery(list: List<ClipEntity>, overlay: Boolean, items: jp.kjtr.drivelogger.share.OverlayItems = defaultOverlayItems()) = viewModelScope.launch {
        val files = list.filter { it.status != ClipEntity.STATUS_RECORDING }.sortedBy { it.startedAt }
        val c = files.firstOrNull() ?: return@launch
        val total = files.sumOf { it.sizeBytes }
        val free = clips.freeBytes()
        if (!overlay || files.any { !canOverlayLater(it) } || !items.any) {
            if (free < total + GALLERY_MARGIN_BYTES) {
                say(R.string.msg_gallery_no_space, Format.bytes(total + GALLERY_MARGIN_BYTES))
                return@launch
            }
            var n = 0
            for (f in files) if (copyToGallery(clips.fileOf(f), f.fileName, f.startedAt, GALLERY_FOLDER)) n++
            when {
                n == 0 -> say(R.string.msg_gallery_failed)
                files.size == 1 -> say(R.string.msg_gallery_done)
                else -> {
                    say(R.string.msg_long_gallery_done, n)
                    if (n < files.size) say(R.string.msg_gallery_failed)
                }
            }
            return@launch
        }
        if (app.sns.running) {
            say(R.string.msg_export_busy)
            return@launch
        }
        if (free < total * 2 + GALLERY_MARGIN_BYTES) {
            say(R.string.msg_gallery_no_space, Format.bytes(total * 2 + GALLERY_MARGIN_BYTES))
            return@launch
        }
        val from = c.startedAt
        val to = files.last().let { it.startedAt + it.durationMs }
        val points = syncPointsBetween(from - 15_000, to + 15_000)
        if (items.map) warmMapTiles(points, from, to, c.width, c.height)
        val durationMs = files.sumOf { it.durationMs.coerceAtLeast(1) }
        // 元のビットレートのまま（表示の文字がつぶれないよう 4 Mbps は下回らない）
        val srcBps = (if (durationMs > 0 && total > 0) (total * 8_000 / durationMs).toInt() else null)?.coerceAtLeast(4_000_000)
        val audio = files.all { it.hasAudio }
        val plan = jp.kjtr.drivelogger.share.ExportPlanner.plan(
            jp.kjtr.drivelogger.share.ExportChoice(jp.kjtr.drivelogger.share.SnsPreset.CUSTOM, false, null, srcBps ?: 8_000_000, true),
            c.width.coerceAtLeast(2), c.height.coerceAtLeast(2), durationMs, audio, srcBps,
        )
        val sources = files.map { f ->
            val part = jp.kjtr.drivelogger.share.ExportPart(f.id, f.startedAt, 0L, f.durationMs.coerceAtLeast(1))
            jp.kjtr.drivelogger.share.ExportSource(
                file = clips.fileOf(f), part = part, layout = null, speedSpans = emptyList(), hasAudio = f.hasAudio,
                later = laterSpec(part, items, points, emptyList(), false),
            )
        }
        app.sns.start(jp.kjtr.drivelogger.share.ExportRequest(sources, plan, false, false, false, true, "gallery", toGallery = true))
        say(R.string.msg_gallery_overlay_started)
    }

    /** 長時間録画のファイルをまとめて消す（0.7.0〜。録画中のファイルは消さない）。 */
    fun deleteClips(list: List<ClipEntity>) = viewModelScope.launch {
        var n = 0
        for (c in list) if (c.status != ClipEntity.STATUS_RECORDING) {
            clips.delete(c)
            n++
        }
        say(R.string.msg_long_deleted, n)
        dashcam.refreshUsage(DriveController.dashcamConfig(settings.value))
    }

    init {
        // ギャラリー向けの書き出しが終わったら保存する（画面を離れていても）
        viewModelScope.launch {
            app.sns.state.collect { st ->
                if (st is jp.kjtr.drivelogger.share.ExportState.Done && st.request.toGallery) {
                    val ok = copyToGallery(st.file, st.file.name, st.request.sources.first().part.wallStartMs, GALLERY_FOLDER)
                    runCatching { st.file.delete() }
                    app.sns.reset()
                    say(if (ok) R.string.msg_gallery_done else R.string.msg_gallery_failed)
                } else if (st is jp.kjtr.drivelogger.share.ExportState.Failed && galleryPending) {
                    say(R.string.msg_gallery_failed)
                }
                galleryPending = st is jp.kjtr.drivelogger.share.ExportState.Running && galleryRequested()
            }
        }
    }

    private var galleryPending = false
    private fun galleryRequested(): Boolean = app.sns.currentRequest?.toGallery == true

    /** 壁時計 [from]〜[to] の GPS の点（速度を隠す時間帯の計算）。 */
    suspend fun syncPointsBetween(from: Long, to: Long): List<SyncPoint> =
        trips.displayPointsBetween(from, to).map {
            SyncPoint(it.time, it.lat, it.lon, it.speed?.toDouble() ?: it.deviceSpeed?.toDouble(), it.bearing?.toDouble())
        }

    private suspend fun copyToGallery(src: File, displayName: String, takenAt: Long, folder: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + folder)
                        put(MediaStore.Video.Media.DATE_TAKEN, takenAt)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                    try {
                        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error("no stream")
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder).apply { mkdirs() }
                    val dst = File(dir, displayName)
                    src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
                    MediaScannerConnection.scanFile(app, arrayOf(dst.path), arrayOf("video/mp4"), null)
                }
            }.onFailure { Log.w(TAG, "gallery export failed: ${it.message}") }.isSuccess
        }

    /** サムネイル（cache/thumbs/ に覚える）。 */
    suspend fun thumbnail(c: ClipEntity): Bitmap? = withContext(Dispatchers.IO) {
        val cache = clips.thumbFile(c)
        if (cache.isFile) return@withContext BitmapFactory.decodeFile(cache.path)
        if (c.status == ClipEntity.STATUS_RECORDING) return@withContext null
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(clips.fileOf(c).path)
                val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
                val w = 320
                val h = (frame.height * w / frame.width.coerceAtLeast(1)).coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(frame, w, h, true)
                FileOutputStream(cache).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                small
            } finally {
                r.release()
            }
        }.getOrNull()
    }

    /** 動画と同期する点（前後 15 秒も含める）。 */
    suspend fun syncPoints(c: ClipEntity): List<SyncPoint> {
        val end = c.endedAt ?: (c.startedAt + c.durationMs.coerceAtLeast(10 * 60_000L))
        // 位置は軌跡をなめらかにした点（0.8.0〜。重ねる地図・緯度経度）
        return trips.displayPointsBetween(c.startedAt - 15_000, end + 15_000).map {
            SyncPoint(it.time, it.lat, it.lon, it.speed?.toDouble() ?: it.deviceSpeed?.toDouble(), it.bearing?.toDouble())
        }
    }

    /** debug だけ: 動画 [fileName] の [atMs] のフレームを files/frames/ に PNG で書く（焼き込みの確認）。 */
    fun debugNavigate(route: String) {
        _nav.trySend(route)
    }

    /** [dir] = "sns" なら SNS 用に書き出した動画（files/Movies/sns/）から。 */
    fun debugExtractFrame(fileName: String, atMs: Long, dir: String? = null) = viewModelScope.launch {
        val f = File(if (dir == "sns") app.sns.dir() else clips.dir(), fileName)
        val out = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.path)
                    val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("no frame")
                    val dir = File(app.getExternalFilesDir(null), "frames").apply { mkdirs() }
                    val dst = File(dir, fileName.removeSuffix(".mp4") + "_" + atMs + ".png")
                    FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    dst.path + " " + bmp.width + "x" + bmp.height + " rot=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                } finally {
                    r.release()
                }
            }.getOrElse { "failed: ${it.message}" }
        }
        Log.i(TAG, "extractFrame $out")
        _messages.trySend("frame: $out")
    }

    // ---- バックアップ ----

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        val n = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { app.backup.export(it) } }.getOrNull()
        }
        if (n != null) say(R.string.msg_backup_done, n) else say(R.string.msg_export_failed)
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        val n = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(app.cacheDir, "restore.zip")
                app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                try {
                    app.backup.import(tmp)
                } finally {
                    tmp.delete()
                }
            }.getOrNull()
        }
        if (n != null) say(R.string.msg_restore_done, n) else say(R.string.msg_restore_failed)
    }

    companion object {
        private const val TAG = "DL-VM"
        const val ROUTE_HOME = "home"
        const val ROUTE_DRIVE = "drive"
        /** これより速い間は設定・一覧へ移れない [km/h]。 */
        const val MOVING_KMH = 10.0
        const val GALLERY_FOLDER = "ドライブロガー"
        const val SNS_FOLDER = "SNS"
        /** ギャラリーに書き出すとき、書き出す分のほかに残しておく空き。 */
        const val GALLERY_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
