package jp.kjtr.drivelogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import jp.kjtr.drivelogger.DriveApp
import jp.kjtr.drivelogger.MainActivity
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInValues
import jp.kjtr.drivelogger.dashcam.DashcamEvent
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.dashcam.StopReason
import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.map.MapGeometry
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.record.StartFailure
import jp.kjtr.drivelogger.record.StopDetector
import jp.kjtr.drivelogger.ui.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * 記録中のフォアグラウンドサービス（DESIGN.md §3.6）。**位置の点を取り込むのはこのサービスだけ。**
 * 0.6.0〜 ドライブが開いていても、**何かを記録している間（GPS ログ・録画・駐車中の徒歩の軌跡）だけ**動く。何も記録しなくなったら止まる。
 *
 * - 型: GPS ログだけなら location。録画 ON（前面から [ACTION_VIDEO_ON]）で camera（| microphone）を足し、OFF で location に戻す。
 * - 画面を消しても・ホームに戻っても続く（PARTIAL_WAKE_LOCK）。通知に［保護］（録画中だけ）［記録を停止］［ドライブを終了］。
 * - 衝撃の監視（加速度）は走行中ずっと。録画中なら保護、記録だけなら出来事に残すだけ。
 * - 熱（SEVERE 以上）・カメラの喪失は録画だけ止め、型は camera のまま残して、戻ったら裏でも録画を再開する。
 * - プロセスごと落ちてシステムが再起動したとき: 「常に許可」があれば GPS 記録だけ続ける（カメラは裏から開けない）。
 */
class DriveService : LifecycleService() {

    private val app get() = application as DriveApp
    private var started = false
    /** 今の FGS の型に camera を含めている。 */
    private var cameraType = false
    private var micType = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null
    private var lastLocation: Location? = null
    /** 熱で録画を止めている。 */
    private var thermalPaused = false
    private var coolSince = 0L
    private var thermalListener: Any? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_VIDEO_ON -> if (started) videoOn()
            ACTION_VIDEO_OFF -> if (started) videoOff()
            ACTION_PROTECT -> if (started) app.controller.protectNow()
            ACTION_STOP -> if (started) lifecycleScope.launch { app.controller.finish() }
            ACTION_STOP_RECORDING -> if (started) app.controller.stopRecording()
            else -> if (!started) {
                val restartedBySystem = intent == null
                if (restartedBySystem && !app.session.isGpsLogging && !RecorderStatus.hasBackgroundLocation(this)) {
                    notifyStopped()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val failure = startForegroundSafely(camera = false, mic = false)
                if (failure != null) {
                    if (restartedBySystem) notifyStopped() else app.recorder.setFailure(failure)
                    stopSelf()
                    return START_NOT_STICKY
                }
                begin(restartedBySystem)
            }
        }
        return START_STICKY
    }

    private fun begin(restartedBySystem: Boolean) {
        started = true
        app.recorder.setFailure(null)
        app.recorder.setRunning(true)
        cancel(STOPPED_ID)
        app.locationHub.acquire(OWNER)
        acquireWakeLock()
        lifecycleScope.launch {
            if (restartedBySystem && !app.session.isGpsLogging) {
                // プロセスごと落ちてシステムが再起動した: 記録中のまま落ちた GPS ログがあれば、新しい GPS ログで続ける（カメラは裏から開けない）
                app.controller.restoreOpen()
                val r = app.controller.interrupted.value
                if (r == null || !r.interruptedGps || !app.session.isActive) {
                    stopSelf()
                    return@launch
                }
                app.controller.clearInterrupted()
                app.session.addEvent(TripEventEntity.TYPE_RESTARTED)
                app.session.startGpsLog()
            }
            app.locationHub.updates.collect {
                lastLocation = it
                if (app.session.isParked) {
                    // 駐車中: 走行の記録には使わない（徒歩の軌跡を記録する設定のときだけ徒歩の点に）
                    if (app.settings.settings.value.parkWalkTrack) app.session.onWalkLocation(it)
                    return@collect
                }
                if (!app.session.isGpsLogging) {
                    // GPS ログを止めている（録画の止め際など）: 記録しない
                    app.session.onIdleLocation(it)
                    return@collect
                }
                if (app.session.isStopped) {
                    // 停車中の確かめの測位: 停車の位置から動いていれば再開、動いていなければ使わない
                    if (!app.session.movedFromHold(it)) return@collect
                    Log.i(TAG, "probe fix moved → resume")
                    resumeFromStop(SystemClock.elapsedRealtime())
                }
                app.session.onLocation(it)
                app.session.state.value?.let { s -> stopDetector.onSpeed(SystemClock.elapsedRealtime(), s.currentSpeedMps) }
                // G メーターの前後・左右の補正（速度の変化・進行方向の変化）
                val st = app.session.state.value
                // 端末の速度（ドップラー）を優先。0 しか返さないもの（エミュレータなど）は記録の速度で
                val speed = if (it.hasSpeed() && (it.speed > 0f || (st?.currentSpeedMps ?: 0.0) < 2.0)) it.speed.toDouble() else st?.currentSpeedMps
                val course = if (it.hasBearing() && it.hasSpeed() && it.speed > 2f) it.bearing.toDouble() else st?.courseDeg
                app.impact.onGps(System.currentTimeMillis(), speed, course)
            }
        }
        // 何も記録しなくなったら（GPS ログ・録画・駐車中の徒歩の軌跡のどれも無い）止まる（0.6.0〜。ドライブは開いたまま）
        lifecycleScope.launch {
            var seen = false
            combine(
                app.session.state, app.controller.videoWanted, app.dashcam.state.map { it.active }.distinctUntilChanged(),
                app.settings.settings.map { it.parkWalkTrack }.distinctUntilChanged(),
            ) { s, wanted, camActive, walk ->
                s != null && (s.recording || wanted || camActive || (s.parked && walk))
            }.distinctUntilChanged().collect { needed ->
                if (!needed) {
                    if (seen) {
                        Log.i(TAG, "nothing is recorded → stop the service")
                        stopSelf()
                    }
                } else {
                    seen = true
                    updateNotification()
                }
            }
        }
        lifecycleScope.launch {
            app.locationHub.locationEnabled.collect { on ->
                app.recorder.setLocationOff(!on)
                if (app.session.isGpsLogging) app.session.setCondition(TripEventEntity.TYPE_LOCATION_OFF, !on)
                updateNotification()
            }
        }
        // 録画の出来事
        lifecycleScope.launch {
            app.dashcam.events.collect { ev ->
                when (ev) {
                    is DashcamEvent.Stopped -> onVideoStopped(ev.reason)
                    is DashcamEvent.CameraLost -> {
                        app.session.addEvent(TripEventEntity.TYPE_CAMERA_LOST, value = ev.code.toDouble())
                        alert(getString(R.string.notif_camera_lost))
                    }
                    DashcamEvent.CameraBack -> cancel(ALERT_ID)
                }
                updateNotification()
            }
        }
        lifecycleScope.launch {
            app.dashcam.state.map { it.recording to it.active }.distinctUntilChanged().collect { updateNotification() }
        }
        // 衝撃（走行中ずっと。感度の変更もすぐ反映）
        lifecycleScope.launch {
            // 0.8.0〜 乗り物に合わせる（自転車はしきい値 1.6 倍・60 ms 続く、徒歩は検知しない）
            app.settings.settings.map { it.impactSensitivity to it.vehicle }.distinctUntilChanged().collect { (sens, v) ->
                val th = if (v.impactEnabled) sens.thresholdG?.let { it * v.impactScale } else null
                app.impact.start(th, v.impactMinMs, v.impactVibrationK) { hit -> onImpact(hit.timeMs, hit.peakG) }
            }
        }
        registerThermal()
        // 駐車・再開: 走行の測位を止める / 戻す（徒歩の軌跡を記録する設定なら低頻度で測る）
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                app.session.state.map { it?.parked == true }.distinctUntilChanged(),
                app.settings.settings.map { it.parkWalkTrack }.distinctUntilChanged(),
            ) { parked, walk -> parked to walk }.collect { (parked, walk) -> onParkedChanged(parked, walk) }
        }
        // 車の動き（加速度・ジャイロ）→ 融合の予測と停車の判定
        stopDetector.reset()
        app.impact.onMotion = { m ->
            app.session.onMotion(m.elapsedMs, m.forwardAccel, m.yawRate, m.accelNorm)
            // 駐車中は歩く揺れで再開しない
            if (!app.session.isParked && stopDetector.onMotion(m.elapsedMs, m.linearAccel, m.gyroMagnitude) == StopDetector.Event.MOVE && app.session.isStopped) {
                Log.i(TAG, "motion → resume (acc=${"%.2f".format(m.linearAccel)} gyro=${m.gyroMagnitude})")
                resumeFromStop(m.elapsedMs)
            }
        }
        tickJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var n = 0
            while (true) {
                delay(TICK_MS)
                updateBurnIn()
                positioningTick()
                if (++n % (NOTIFY_INTERVAL_MS / TICK_MS).toInt() != 0) continue
                if (!app.session.isActive) continue
                app.locationHub.refreshEnabled()
                // 停車中・駐車中（測位を止めている）・GPS ログを止めている間は GPS 不通にしない
                if (app.session.isStopped || app.session.isParked || !app.session.isGpsLogging) lastStopSeen = SystemClock.elapsedRealtime()
                val since = maxOf(app.locationHub.lastGoodFixElapsedMs, startedAt, lastStopSeen)
                val noFix = !app.recorder.locationOff.value && SystemClock.elapsedRealtime() - since > NO_FIX_MS
                if (noFix != app.recorder.noFix.value) {
                    app.recorder.setNoFix(noFix)
                    app.session.setCondition(TripEventEntity.TYPE_NO_FIX, noFix)
                }
                checkThermalResume()
                updateNotification()
            }
        }
    }

    // ---- 停車中は測位を止める（DESIGN.md §4.3） ----

    private val stopDetector = StopDetector()
    private var lastStopSeen = 0L
    /** 停車中の確かめ: 次に GPS を一時的に点ける時刻・点けている終わり（elapsedRealtime）。 */
    private var nextProbeAt = 0L
    private var probeUntil = 0L
    private var resumedAt = 0L

    /** 4 回/秒: 停車の判定・停車中の点・推測航法・停車中の確かめ。 */
    private fun positioningTick() {
        if (!app.session.isActive || app.session.isParked || !app.session.isGpsLogging) {
            // GPS ログを止めた: 停車で止めていた測位を戻す
            if (app.session.isActive && !app.session.isParked && !app.session.isGpsLogging && app.locationHub.pausedState.value) {
                app.locationHub.setPaused(false)
                app.impact.stationary = false
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val s = app.settings.settings.value
        if (app.session.isStopped) {
            if (!s.stopPause) {
                resumeFromStop(now)
                return
            }
            app.session.holdTick(now)
            // 動き出しを加速度で拾えなかったときのため、ときどき GPS を点けて確かめる
            if (probeUntil == 0L && now >= nextProbeAt) {
                probeUntil = now + PROBE_WINDOW_MS
                app.locationHub.setPaused(false)
                Log.i(TAG, "stopped: probe GPS for ${PROBE_WINDOW_MS / 1000} s")
            } else if (probeUntil != 0L && now >= probeUntil) {
                probeUntil = 0L
                nextProbeAt = now + PROBE_INTERVAL_MS
                app.locationHub.setPaused(true)
            }
            return
        }
        app.session.deadReckonTick(now)
        // 動き出した直後（発進の直後の信号待ちなど）はしばらく停車にしない
        if (s.stopPause && now - resumedAt >= RESUME_GRACE_MS && stopDetector.tick(now) == StopDetector.Event.STOP) {
            if (app.session.enterStop(now)) {
                Log.i(TAG, "stopped → pause GPS")
                app.impact.stationary = true
                probeUntil = 0L
                nextProbeAt = now + PROBE_INTERVAL_MS
                app.locationHub.setPaused(true)
                updateNotification()
            } else {
                stopDetector.reset()
            }
        }
    }

    private var wasParked = false

    private fun onParkedChanged(parked: Boolean, walk: Boolean) {
        if (parked) {
            Log.i(TAG, "parked → stop drive GPS (walk track=$walk)")
            stopDetector.reset()
            probeUntil = 0L
            app.impact.stationary = true
            if (walk) {
                app.locationHub.setInterval(WALK_INTERVAL_MS)
                app.locationHub.setPaused(false)
            } else {
                app.locationHub.setPaused(true)
            }
        } else if (wasParked) {
            Log.i(TAG, "resume drive → GPS")
            app.impact.stationary = false
            app.locationHub.setInterval(DRIVE_INTERVAL_MS)
            app.locationHub.setPaused(false)
            resumedAt = SystemClock.elapsedRealtime()
            stopDetector.reset()
        }
        wasParked = parked
        updateNotification()
    }

    private fun resumeFromStop(now: Long) {
        if (!app.session.isStopped) return
        app.session.exitStop(now)
        resumedAt = now
        Log.i(TAG, "moving → resume GPS")
        stopDetector.reset()
        app.impact.stationary = false
        probeUntil = 0L
        app.locationHub.setPaused(false)
        updateNotification()
    }

    /** 録画 ON（前面から）。型に camera（| microphone）を足してから録画を始める。 */
    private fun videoOn() {
        if (app.dashcam.isActive) return
        if (!RecorderStatus.hasCamera(this)) {
            app.controller.videoFailed(StartFailure.PERMISSION)
            return
        }
        val s = app.settings.settings.value
        val mic = s.audio && RecorderStatus.hasAudio(this)
        val failure = startForegroundSafely(camera = true, mic = mic)
        if (failure != null) {
            app.controller.videoFailed(failure)
            return
        }
        thermalPaused = false
        startEngine()
    }

    /** 動画ログを始めて録画を始める（動画のファイルはその動画ログに紐づく）。 */
    private fun startEngine() {
        lifecycleScope.launch {
            val mode = app.controller.videoMode.value
            val logId = app.session.startVideoLog(mode)
            if (app.dashcam.isActive) return@launch
            app.dashcam.start(app.session.tripId, logId, DriveController.dashcamConfig(app.settings.settings.value, mode))
            updateBurnIn()
        }
    }

    /** 録画 OFF。止まったら（[onVideoStopped]）型を location に戻す。 */
    private fun videoOff() {
        thermalPaused = false
        if (app.dashcam.isActive) app.dashcam.stop() else downgrade()
    }

    private fun onVideoStopped(reason: StopReason) {
        val long = app.dashcam.state.value.mode == RecordMode.LONG
        app.session.endVideoLog()
        when (reason) {
            StopReason.STORAGE_FULL -> {
                app.session.addEvent(TripEventEntity.TYPE_STORAGE_FULL)
                // 長時間録画は空きが 1 GB を切ったら止める（何も消さない）。ループは消せる物が無かった
                alert(getString(if (long) R.string.notif_long_storage_stop else R.string.notif_storage_full))
                if (long) app.controller.showFlash(Flash(DriveController.FLASH_LONG_STORAGE, System.currentTimeMillis()))
                app.controller.videoEnded()
            }
            StopReason.ERROR -> {
                alert(getString(R.string.notif_camera_failed))
                app.controller.videoEnded()
            }
            StopReason.USER -> Unit
        }
        // 熱で止めたときは camera の型を残す（冷めたら裏でも再開できるように）
        if (!thermalPaused && app.session.isActive) downgrade()
    }

    private fun downgrade() {
        if (!cameraType && !micType) return
        startForegroundSafely(camera = false, mic = false)
    }

    private fun onImpact(at: Long, g: Double) {
        // 駐車中は端末を持って歩くので衝撃は見ない
        if (!app.session.isActive || app.session.isParked) return
        val files = if (app.dashcam.isActive) app.dashcam.protect(ClipEntity.REASON_IMPACT, at) else 0
        app.session.addEvent(TripEventEntity.TYPE_IMPACT, at, g)
        app.controller.showFlash(Flash(TripEventEntity.TYPE_IMPACT, at, g, files))
        updateNotification()
    }

    /** 焼き込む値（4 回 / 秒）。地図を焼き込むときは軌跡とタイルの先読みも。 */
    private fun updateBurnIn() {
        if (!app.dashcam.isActive) return
        val l = lastLocation ?: app.locationHub.last.value
        val st = app.session.state.value
        // 記録の点（融合後・停車中の点・推測航法）が新しければそれを、無ければ端末の測位
        val recFresh = st?.lastFixAt != null && System.currentTimeMillis() - st.lastFixAt < 5_000 && st.lat != null
        val fresh = recFresh || (l != null && (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) < 5_000_000_000L)
        val speedKmh = when {
            !fresh -> null
            st?.stopped == true -> 0.0
            recFresh -> st!!.currentSpeedMps * 3.6
            l!!.hasSpeed() -> l.speed * 3.6
            else -> null
        }
        val lat = if (!fresh) null else if (recFresh) st!!.lat else l!!.latitude
        val lon = if (!fresh) null else if (recFresh) st!!.lon else l!!.longitude
        app.dashcam.feed.set(
            BurnInValues(
                speedKmh = speedKmh,
                lat = lat,
                lon = lon,
                headingDeg = st?.courseDeg,
                g = if (app.impact.available) app.impact.currentG else null,
                vehicleG = app.impact.vehicleG.value,
            ),
        )
        val s = app.settings.settings.value
        val map = DriveController.mapConfig(s)
        if (map != null && s.burnIn && app.dashcam.state.value.burnIn) {
            app.dashcam.trackFeed.set(app.session.line.value)
            if (lat != null && lon != null) {
                // 録画の短い辺は画質から（エミュレータなどで落ちていても多めに読むだけ）
                val win = MapGeometry.windowSize(map.sizeRatio, s.quality.shortSide, s.quality.shortSide)
                app.tiles.prefetch(map.layer, map.zoom, lat, lon, st?.courseDeg, speedKmh, MapGeometry.viewRadiusPx(win))
            }
        }
    }

    // ---- 熱 ----

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val l = PowerManager.OnThermalStatusChangedListener { status -> onThermal(status) }
        runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this) as Executor, l) }
        thermalListener = l
    }

    private fun onThermal(status: Int) {
        if (Build.VERSION.SDK_INT < 29) return
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && app.dashcam.isActive) {
            thermalPaused = true
            coolSince = 0L
            app.session.addEvent(TripEventEntity.TYPE_THERMAL, value = status.toDouble())
            app.dashcam.stop()
            alert(getString(R.string.notif_thermal))
        }
    }

    private fun checkThermalResume() {
        if (Build.VERSION.SDK_INT < 29 || !thermalPaused) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.currentThermalStatus <= PowerManager.THERMAL_STATUS_MODERATE) {
            val now = SystemClock.elapsedRealtime()
            if (coolSince == 0L) coolSince = now
            if (now - coolSince >= THERMAL_COOL_MS && app.controller.videoWanted.value && cameraType) {
                thermalPaused = false
                cancel(ALERT_ID)
                startEngine()
            }
        } else {
            coolSince = 0L
        }
    }

    override fun onDestroy() {
        tickJob?.cancel()
        app.impact.onMotion = null
        app.impact.stationary = false
        app.impact.stop()
        app.locationHub.setInterval(DRIVE_INTERVAL_MS)
        app.locationHub.setPaused(false)
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(it) }
            }
        }
        if (app.dashcam.isActive) app.dashcam.stop()
        if (started) {
            app.locationHub.release(OWNER)
            app.recorder.setRunning(false)
        }
        cancel(ALERT_ID)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveLogger:drive").apply {
            setReferenceCounted(false)
            acquire(24L * 60 * 60 * 1000)
        }
    }

    /** フォアグラウンドにする（型を変えるときも呼ぶ）。できなければ理由。 */
    private fun startForegroundSafely(camera: Boolean, mic: Boolean): StartFailure? {
        createChannels()
        return try {
            var type = 0
            if (Build.VERSION.SDK_INT >= 29) {
                if (RecorderStatus.hasLocation(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (camera && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (mic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            cameraType = camera
            micType = mic
            null
        } catch (e: RuntimeException) {
            RecorderStatus.classify(e)
        }
    }

    private fun updateNotification() {
        if (!started) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, DriveService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val s = app.session.state.value
        val video = app.dashcam.state.value
        val title = when {
            app.recorder.locationOff.value -> getString(R.string.notif_title_location_off)
            app.recorder.noFix.value -> getString(R.string.notif_title_no_fix)
            video.active && video.recording && video.mode == RecordMode.LONG -> getString(R.string.notif_title_recording_long)
            video.active && video.recording -> getString(R.string.notif_title_recording_video)
            thermalPaused -> getString(R.string.notif_title_thermal)
            s?.parked == true -> getString(R.string.notif_title_parked)
            app.session.isStopped -> getString(R.string.notif_title_stopped)
            else -> getString(R.string.notif_title_logging)
        }
        val text = when {
            s == null -> ""
            s.parked -> getString(R.string.notif_text_parked, Format.duration(System.currentTimeMillis() - (s.parkStartedAt ?: s.startedAt)), Format.km(s.distanceM))
            s.gpsLogging -> getString(
                R.string.notif_text_log, s.gpsLogCount, Format.duration(System.currentTimeMillis() - (s.gpsLogStartedAt ?: s.startedAt)),
                Format.km(s.gpsLogDistanceM), Format.km(s.distanceM),
            )
            else -> getString(R.string.notif_text, Format.duration(System.currentTimeMillis() - s.startedAt), Format.km(s.distanceM))
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // 保護はループ録画のときだけ（長時間録画は自動では消さない）
        if (video.active && video.mode == RecordMode.LOOP) b.addAction(0, getString(R.string.protect), actionIntent(ACTION_PROTECT, 1))
        // 記録を止める（ドライブは開いたまま）・ドライブを終了
        b.addAction(0, getString(R.string.notif_stop_recording), actionIntent(ACTION_STOP_RECORDING, 3))
        b.addAction(0, getString(R.string.finish_drive), actionIntent(ACTION_STOP, 2))
        return b.build()
    }

    private fun alert(text: String) {
        createChannels()
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n) }
    }

    private fun cancel(id: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    private fun notifyStopped() {
        createChannels()
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(getString(R.string.notif_stopped_title))
            .setContentText(getString(R.string.notif_stopped_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_stopped_text)))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(STOPPED_ID, n) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.notif_channel_desc)
            nm.createNotificationChannel(ch)
            val alert = NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.notif_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
            alert.description = getString(R.string.notif_alert_channel_desc)
            nm.createNotificationChannel(alert)
        }
    }

    companion object {
        private const val OWNER = "drive"
        private const val CHANNEL_ID = "drive"
        private const val ALERT_CHANNEL_ID = "drive_alert"
        private const val NOTIF_ID = 7
        private const val ALERT_ID = 8
        private const val STOPPED_ID = 9
        private const val TICK_MS = 250L
        private const val NOTIFY_INTERVAL_MS = 5_000L
        const val NO_FIX_MS = 60_000L
        private const val THERMAL_COOL_MS = 60_000L
        private const val TAG = "DL-Drive"
        /** 停車中、GPS を一時的に点けて動いていないか確かめる間隔と長さ。 */
        private const val PROBE_INTERVAL_MS = 120_000L
        private const val PROBE_WINDOW_MS = 20_000L
        /** 動き出してからこの間は停車にしない。 */
        private const val RESUME_GRACE_MS = 15_000L
        /** 走行中・駐車中の徒歩の測位の間隔。 */
        private const val DRIVE_INTERVAL_MS = 1_000L
        private const val WALK_INTERVAL_MS = 5_000L
        const val ACTION_START = "jp.kjtr.drivelogger.drive.START"
        const val ACTION_VIDEO_ON = "jp.kjtr.drivelogger.drive.VIDEO_ON"
        const val ACTION_VIDEO_OFF = "jp.kjtr.drivelogger.drive.VIDEO_OFF"
        const val ACTION_PROTECT = "jp.kjtr.drivelogger.drive.PROTECT"
        const val ACTION_STOP = "jp.kjtr.drivelogger.drive.STOP"
        const val ACTION_STOP_RECORDING = "jp.kjtr.drivelogger.drive.STOP_RECORDING"

        /** 前面から呼ぶ。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java).setAction(ACTION_START))
        }

        /** 動いているサービスへの操作（録画 ON/OFF など。前面から）。 */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, DriveService::class.java).setAction(action))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
