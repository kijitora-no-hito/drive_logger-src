package jp.kjtr.drivelogger.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInText
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.gauge.GReading
import jp.kjtr.drivelogger.gauge.GaugeRenderer
import jp.kjtr.drivelogger.gauge.MeterStyle
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.sensor.rememberDeviceHeading
import kotlinx.coroutines.delay

/** これより速い間は設定・一覧へ移れない [km/h]。 */
private const val MOVING_KMH = MainViewModel.MOVING_KMH
/** ［駐車する］を押せる速さの上限 [km/h]（停車中だけ）。 */
private const val PARK_MAX_KMH = 5.0

/**
 * 走行画面（ドライブを開いている間だけ。0.5.0〜 起動時の画面はホーム。DESIGN.md §7）。
 * 0.6.0〜 ドライブは GPS ログ・動画ログの入れ物: ［GPS 記録］［録画］をそれぞれ開始/停止できる（何も記録していない状態もある＝地図と現在地だけ）。
 * - 録画していない: 地図全面＋大きな速度などの HUD。［GPS 記録 / GPS 停止］［録画］［駐車する］［終了］。
 * - 録画中: カメラ全面＋HUD＋地図の小窓。［保護］［GPS 停止］［録画 停止］［駐車する］［終了］。録画を始めると GPS 記録も始まる。
 * - 駐車中: [ParkedScreen]。
 * 戻る（とメニューの［ホーム］）はホームへ。走行中（10 km/h 超）は移れない。ドライブが終わったら [onExit]（ホームへ）。
 */
@Composable
fun DriveScreen(
    vm: MainViewModel, onTrips: () -> Unit, onClips: () -> Unit, onSettings: () -> Unit,
    onCamera: () -> Unit = {}, onCard: (Long) -> Unit = {},
    onHome: () -> Unit = {}, onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val drive by vm.session.state.collectAsStateWithLifecycle()
    val line by vm.session.line.collectAsStateWithLifecycle()
    val cam by vm.dashcam.state.collectAsStateWithLifecycle()
    val videoWanted by vm.controller.videoWanted.collectAsStateWithLifecycle()
    val nextMode by vm.controller.nextVideoMode.collectAsStateWithLifecycle()
    val flash by vm.controller.flash.collectAsStateWithLifecycle()
    val startError by vm.controller.startError.collectAsStateWithLifecycle()
    val running by vm.recorder.serviceRunning.collectAsStateWithLifecycle()
    val locationOff by vm.recorder.locationOff.collectAsStateWithLifecycle()
    val noFix by vm.recorder.noFix.collectAsStateWithLifecycle()
    val last by vm.locationHub.last.collectAsStateWithLifecycle()
    val deviceHeading by rememberDeviceHeading()
    val vehicleG by vm.app.impact.vehicleG.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 時刻（1 秒ごと）
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }

    // 画面が見えている間は現在地を取る（記録中はサービスも取る）。駐車中は常時の測位をしない（［現在地を測る］の 1 回だけ）
    val parkedNow = drive?.parked == true
    LifecycleStartEffect(parkedNow) {
        if (!parkedNow) vm.locationHub.acquire(UI_OWNER)
        onStopOrDispose { vm.locationHub.release(UI_OWNER) }
    }
    val starter = rememberRecordStarter(vm)
    var confirmStopGps by remember { mutableStateOf(false) }
    // ドライブが終わった（通知の［終了］・点が無くて保存しなかったなど）→ ホームへ。
    // 少し待つのは、ナビゲーションの切り替えの途中で一瞬 null に見えるのを避けるため
    LaunchedEffect(drive == null) {
        if (drive == null) {
            delay(300)
            if (vm.session.state.value == null) onExit()
        }
    }

    // 画面を点けたまま
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 録画 ON の間は横向きに固定（設定「録画の向き: 横」。既定）。記録だけ・駐車中は縦横どちらでも
    val lockLandscape = cam.active && settings.videoOrientation == jp.kjtr.drivelogger.data.VideoOrientation.LANDSCAPE
    val activity = context as? android.app.Activity
    DisposableEffect(lockLandscape) {
        activity?.requestedOrientation =
            if (lockLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { if (lockLandscape) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val driving = drive != null
    val fresh = last?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 5_000_000_000L } == true
    val speedKmh: Double? = when {
        drive != null && drive?.lastFixAt != null && now - (drive?.lastFixAt ?: 0L) < 5_000 -> (drive?.currentSpeedMps ?: 0.0) * 3.6
        fresh && last?.hasSpeed() == true -> (last?.speed ?: 0f) * 3.6
        else -> null
    }
    val moving = (speedKmh ?: 0.0) > MOVING_KMH
    val heading: Double? = when {
        moving && drive?.courseDeg != null -> drive?.courseDeg
        moving && fresh && last?.hasBearing() == true -> last?.bearing?.toDouble()
        deviceHeading != null -> deviceHeading?.toDouble()
        else -> drive?.courseDeg
    }
    val restricted = settings.restrictWhileMoving && moving

    // 戻る → ホーム（走行中は移れない）
    BackHandler {
        if (restricted) Toast.makeText(context, R.string.restricted_while_moving, Toast.LENGTH_SHORT).show() else onHome()
    }

    // ［GPS 記録］［録画］（権限・熱の注意は rememberRecordStarter。0.6.0〜 それぞれ独立に開始/停止）
    var confirmFinish by remember { mutableStateOf(false) }
    val gpsOn = drive?.gpsLogging == true
    val videoOn = cam.active || videoWanted

    fun requestVideoOn() = starter(RecordAction.VIDEO)
    // 録画の方式の切替（録画ボタンの上の小さなボタン。録画していない間だけ。0.7.0〜）
    val toggleMode: (() -> Unit)? = if (videoOn) null else {
        { vm.controller.setNextVideoMode(if (nextMode == RecordMode.LOOP) RecordMode.LONG else RecordMode.LOOP) }
    }
    fun onGps(on: Boolean) {
        if (on) starter(RecordAction.GPS)
        // 録画中に GPS 記録を止めると録画も止まる（動画の表示に GPS の記録を使うため）ので確かめる
        else if (videoOn) confirmStopGps = true
        else vm.stopGps()
    }

    // 写真: 録画中は走行画面のプレビューのまま（録画を止めずに）撮る。記録だけ・駐車中はアプリ内カメラ画面へ
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.takePhotoWhileRecording() }
    fun takePhoto() {
        if (cam.active) {
            if (Build.VERSION.SDK_INT <= 28 && !RecorderStatus.granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else vm.takePhotoWhileRecording()
        } else onCamera()
    }
    var confirmPark by remember { mutableStateOf(false) }
    // 駐車は停車中だけ（5 km/h 未満か、速さが分からないとき）
    val canPark = driving && drive?.parked != true && (speedKmh ?: 0.0) < PARK_MAX_KMH

    val parkedState = drive?.takeIf { it.parked }
    if (parkedState != null) {
        ParkedScreen(vm, parkedState, now, onCamera = onCamera, onTrips = onTrips, onSettings = onSettings, onHome = onHome, onFinish = { confirmFinish = true })
        if (confirmFinish) {
            ConfirmDialog(
                title = stringResource(R.string.finish_confirm_title), text = stringResource(R.string.finish_confirm_text_parked),
                confirm = stringResource(R.string.finish_drive), onConfirm = { vm.finishDrive() }, onDismiss = { confirmFinish = false },
            )
        }
        startError?.let { f -> RecordStartErrorDialog(f, onDismiss = { vm.controller.dismissStartError() }) }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 背景（地図 または カメラ） ----
        if (cam.active) {
            CameraPreview(vm, Modifier.fillMaxSize())
        } else {
            DriveMap(
                lat = drive?.lat ?: last?.latitude, lon = drive?.lon ?: last?.longitude, headingDeg = heading, track = line, mapKind = settings.mapKind,
                full = true, modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- HUD ----
        // 横向きは、ボタンを右端に縦に並べる（ドラレコは横で録るのが普通。HUD は左、地図の小窓はボタンの左下）
        val side = landscape && driving
        val sideW = 112.dp
        // 縦向きの下のボタンの列の高さ
        // （録画していない間は［録画］の上に方式の切替が乗るので、その分も上げる。0.7.0〜）
        // （記録中のボタンは「● GPS 記録中」「● 録画中」の 2 行目の分も）
        val bottomBar = when {
            driving && !videoOn -> 124.dp
            gpsOn || videoOn -> 96.dp
            else -> 84.dp
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            // 左上: 速度（文字 または メーター）
            val gauges = settings.hudStyle == MeterStyle.GAUGES
            if (gauges) {
                GaugeHud(
                    speedKmh = speedKmh, headingDeg = heading, g = vehicleG, maxKmh = settings.gaugeMaxKmh,
                    compass = settings.hudCompass, gMeter = settings.hudG, big = landscape, modifier = Modifier.align(Alignment.TopStart),
                )
            } else {
                SpeedPanel(speedKmh, big = landscape, modifier = Modifier.align(Alignment.TopStart))
            }
            // 上中央: 時刻・方位
            // 縦向きは幅が足りないので速度の下に置く
            Column(
                (if (landscape) Modifier.align(Alignment.TopCenter) else Modifier.align(Alignment.TopStart).padding(top = if (gauges) 146.dp else 92.dp)).hudPanel(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Format.clock(now), color = colorResource(R.color.hud_text), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(BurnInText.heading(heading), color = colorResource(R.color.hud_accent), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            // 右上: 状態とメニュー
            // 横向きの走行中は、右端のボタンの列にかからないよう左へ
            // 横向きはボタンの列（0.6.0〜 5 つで上まで届く）にかからないよう、その左に置く
            Column(
                Modifier.align(Alignment.TopEnd).padding(end = if (side) sideW + 8.dp else 0.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPanel(
                        fresh = fresh, noFix = noFix, locationOff = locationOff, cam = cam, now = now, stopped = drive?.stopped == true, compact = landscape && driving,
                        drive = drive,
                    )
                    MenuButton(
                        restricted, onTrips, onClips, onSettings,
                        onCard = drive?.let { d -> { onCard(d.tripId) } },
                        onHome = onHome,
                    )
                }
            }
            // 左下: 走行距離・時間
            // 距離・走行時間は全 GPS ログの合計、経過はドライブを開いてから
            drive?.let { d ->
                Column(Modifier.align(Alignment.BottomStart).padding(bottom = if (side) 0.dp else bottomBar).hudPanel()) {
                    HudStat(stringResource(R.string.hud_distance), Format.km(d.distanceM))
                    HudStat(stringResource(R.string.hud_running), Format.duration(d.movingMs))
                    HudStat(stringResource(R.string.hud_elapsed), Format.duration(now - d.startedAt))
                }
            }
            // 右下: 地図の小窓（録画中）
            if (cam.active) {
                DriveMap(
                    lat = drive?.lat ?: last?.latitude, lon = drive?.lon ?: last?.longitude, headingDeg = heading, track = line, mapKind = settings.mapKind, full = false,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (side) 0.dp else bottomBar, end = if (side) sideW + 8.dp else 0.dp)
                        .size(if (landscape) 220.dp else 180.dp, if (landscape) 150.dp else 130.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            // 警告の帯
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // サービスは記録している間だけ動く（0.6.0〜）
                if (drive?.recording == true && !running) {
                    WarningBar(stringResource(R.string.warn_service_stopped), action = stringResource(R.string.restart)) { vm.restartService() }
                }
                if (locationOff) WarningBar(stringResource(R.string.warn_location_off))
                if (cam.active && cam.cameraError) WarningBar(stringResource(R.string.warn_camera_error))
                if (cam.active && cam.lowSpace) {
                    WarningBar(
                        if (cam.mode == RecordMode.LONG) stringResource(R.string.warn_long_low_space, Format.approxHours(cam.hoursLeft ?: 0.0))
                        else stringResource(R.string.warn_low_space),
                    )
                }
                if (cam.active && cam.protectedOverLimit) WarningBar(stringResource(R.string.warn_protected_over))
                if (cam.active && cam.burnInUnavailable) WarningBar(stringResource(R.string.warn_burn_unavailable))
                if (driving && videoWanted && !cam.active) WarningBar(stringResource(R.string.warn_video_paused))
                FlashBar(flash, now)
            }
            // 右: 写真（走行中。録画中はプレビューのまま撮る・記録だけはカメラ画面）。小さく「撮りました」
            if (driving) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(
                        bottom = when {
                            side -> 0.dp
                            cam.active -> if (landscape) bottomBar + 158.dp else bottomBar + 138.dp
                            else -> bottomBar
                        },
                        // 横向きは地図の小窓（録画中）の左に
                        end = if (side) sideW + 8.dp + (if (cam.active) 228.dp else 0.dp) else 0.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PhotoFlash(flash, now)
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color(0xE6202020))
                            .clickable(enabled = !photoBusy) { takePhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.camera_open), tint = if (photoBusy) Color.Gray else Color.White)
                            Text(stringResource(R.string.photo_button), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        stringResource(R.string.photo_button_safety), color = colorResource(R.color.hud_text), fontSize = 9.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp).widthIn(max = 96.dp).clip(RoundedCornerShape(6.dp))
                            .background(colorResource(R.color.hud_panel_bg)).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            // 右端（横向きの走行中）: 操作を縦に
            if (side) {
                DrivingButtons(
                    gpsOn = gpsOn,
                    videoOn = videoOn,
                    onGps = { on -> onGps(on) },
                    onProtect = { vm.protect() },
                    onVideo = { on -> if (on) requestVideoOn() else vm.setVideo(false) },
                    onFinish = { confirmFinish = true },
                    canProtect = cam.active && cam.mode == RecordMode.LOOP,
                    canPark = canPark,
                    onPark = { confirmPark = true },
                    mode = nextMode,
                    onToggleMode = toggleMode,
                    vertical = true,
                    modifier = Modifier.align(Alignment.BottomEnd).width(sideW),
                )
            }
            // 下: 操作（0.6.0〜［GPS 記録］［録画］を独立に。何も記録していない状態もある）
            if (!side) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (driving) {
                    DrivingButtons(
                        gpsOn = gpsOn,
                        videoOn = videoOn,
                        onGps = { on -> onGps(on) },
                        onProtect = { vm.protect() },
                        onVideo = { on -> if (on) requestVideoOn() else vm.setVideo(false) },
                        onFinish = { confirmFinish = true },
                        canProtect = cam.active && cam.mode == RecordMode.LOOP,
                        canPark = canPark,
                        onPark = { confirmPark = true },
                        mode = nextMode,
                        onToggleMode = toggleMode,
                    )
                }
            }
        }
    }

    if (confirmPark) {
        ConfirmDialog(
            title = stringResource(R.string.park_confirm_title),
            text = stringResource(
                when {
                    videoOn -> R.string.park_confirm_text_video
                    gpsOn -> R.string.park_confirm_text
                    else -> R.string.park_confirm_text_idle
                },
            ),
            confirm = stringResource(R.string.park),
            onConfirm = { vm.park() },
            onDismiss = { confirmPark = false },
        )
    }
    if (confirmFinish) {
        ConfirmDialog(
            title = stringResource(R.string.finish_confirm_title),
            text = stringResource(
                when {
                    videoOn -> R.string.finish_confirm_text_video
                    gpsOn -> R.string.finish_confirm_text
                    else -> R.string.finish_confirm_text_idle
                },
            ),
            confirm = stringResource(R.string.finish_drive),
            onConfirm = { vm.finishDrive() },
            onDismiss = { confirmFinish = false },
        )
    }
    if (confirmStopGps) {
        ConfirmDialog(
            title = stringResource(R.string.gps_stop_confirm_title),
            text = stringResource(R.string.gps_stop_confirm_text),
            confirm = stringResource(R.string.gps_stop_confirm_ok),
            onConfirm = { vm.stopGps() },
            onDismiss = { confirmStopGps = false },
        )
    }
    startError?.let { f -> RecordStartErrorDialog(f, onDismiss = { vm.controller.dismissStartError() }) }
}

private const val UI_OWNER = "ui"

/** カメラのプレビュー（録画中だけ。画面が見えている間だけ録画のカメラにつなぐ）。 */
@Composable
private fun CameraPreview(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    LifecycleStartEffect(previewView) {
        // フレームは ImageCapture を結べない端末で録画中の写真に使う（焼き込みの手前の映像・画面に見えている範囲）
        vm.dashcam.setPreviewSurface(previewView.surfaceProvider) { previewView.bitmap }
        onStopOrDispose { vm.dashcam.setPreviewSurface(null) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun SpeedPanel(speedKmh: Double?, big: Boolean, modifier: Modifier) {
    Row(modifier.hudPanel(), verticalAlignment = Alignment.Bottom) {
        Text(
            speedKmh?.let { it.coerceAtLeast(0.0).toInt().toString() } ?: "--",
            color = colorResource(R.color.hud_text), fontSize = if (big) 72.sp else 60.sp, fontWeight = FontWeight.Bold,
            lineHeight = if (big) 72.sp else 60.sp,
        )
        Text(" km/h", color = colorResource(R.color.hud_text_sub), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/**
 * メーターの HUD（速度計＋コンパス・G メーター）。焼き込みと同じ [GaugeRenderer] で描く。
 * 縦向き: 速度計 130dp の右にコンパス・G を縦に 62dp。横向き: 150dp と 72dp。
 */
@Composable
private fun GaugeHud(
    speedKmh: Double?, headingDeg: Double?, g: GReading, maxKmh: Int, compass: Boolean, gMeter: Boolean, big: Boolean, modifier: Modifier,
) {
    val renderer = remember { GaugeRenderer() }
    val main = if (big) 150.dp else 130.dp
    val sub = if (big) 72.dp else 62.dp
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(main)) {
            drawIntoCanvas { renderer.speed(it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2, speedKmh, maxKmh) }
        }
        if (compass || gMeter) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (compass) {
                    Canvas(Modifier.size(sub)) {
                        drawIntoCanvas { renderer.compass(it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2, headingDeg) }
                    }
                }
                if (gMeter) {
                    Canvas(Modifier.size(sub)) {
                        drawIntoCanvas { renderer.gMeter(it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2, g) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = colorResource(R.color.hud_text_sub), fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Text(value, color = colorResource(R.color.hud_text), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** GPS・録画（REC・今のファイルの経過・使用量）の状態。 */
@Composable
private fun StatusPanel(
    fresh: Boolean, noFix: Boolean, locationOff: Boolean, cam: jp.kjtr.drivelogger.dashcam.DashcamState, now: Long, stopped: Boolean = false,
    /** 横向きの走行中（右端のボタンの列にかからないよう、使用量と保護を 1 行に）。 */
    compact: Boolean = false,
    drive: jp.kjtr.drivelogger.record.DriveState? = null,
) {
    Column(Modifier.hudPanel(), horizontalAlignment = Alignment.End) {
        // 記録の状態（0.6.0〜 ドライブが開いていても記録していないことがある）
        if (drive != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (drive.gpsLogging) Box(Modifier.size(10.dp).clip(CircleShape).background(colorResource(R.color.recording)))
                Text(
                    (if (drive.gpsLogging) " " else "") + if (drive.gpsLogging) {
                        stringResource(R.string.hud_gps_logging, drive.gpsLogCount, Format.km(drive.gpsLogDistanceM))
                    } else stringResource(R.string.hud_not_recording),
                    color = colorResource(if (drive.gpsLogging) R.color.hud_text else R.color.hud_accent), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        val gpsOk = stopped || (fresh && !noFix && !locationOff)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colorResource(if (gpsOk) R.color.hud_ok else R.color.hud_warn)))
            Text(
                " " + stringResource(
                    when {
                        stopped -> R.string.hud_gps_stopped
                        // 0.8.0〜 歩いている（GPS のまま記録）・Google の融合測位
                        gpsOk && drive?.walking == true -> R.string.hud_gps_walk
                        gpsOk && drive?.fusedProvider == true -> R.string.hud_gps_google
                        gpsOk -> R.string.hud_gps_ok
                        locationOff -> R.string.hud_gps_off
                        else -> R.string.hud_gps_wait
                    },
                ),
                color = colorResource(R.color.hud_text), fontSize = 13.sp,
            )
        }
        if (cam.active) {
            val blink = (now / 1000) % 2 == 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (cam.recording && blink) colorResource(R.color.recording) else Color.Transparent))
                val seg = cam.segmentStartedAt?.let { Format.duration(now - it) } ?: "--:--"
                Text(" REC $seg", color = colorResource(R.color.hud_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                // 長時間録画: この録画を始めてからの長さ（REC はいまのファイルの経過）
                if (cam.mode == RecordMode.LONG && !compact) {
                    Text(
                        "  " + stringResource(R.string.hud_long_total, Format.duration(now - (cam.startedAt ?: now))),
                        color = colorResource(R.color.hud_accent), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (cam.mode == RecordMode.LONG) {
                // 長時間録画は上限・保護が無いので、この録画の大きさと端末の空き（おおよその残り時間）
                Text(
                    // 横向き（右端のボタンの列・上中央の時刻にかからないよう短く）
                    if (compact) stringResource(
                        R.string.hud_long_compact, Format.duration(now - (cam.startedAt ?: now)), Format.bytes(cam.longBytes), Format.approxHours(cam.hoursLeft ?: 0.0),
                    ) else stringResource(R.string.hud_long, Format.bytes(cam.longBytes), Format.bytes(cam.freeBytes), Format.approxHours(cam.hoursLeft ?: 0.0)),
                    color = colorResource(if (cam.lowSpace) R.color.hud_warn else R.color.hud_text_sub), fontSize = 12.sp,
                )
            } else {
                Text(
                    stringResource(R.string.hud_storage, Format.bytes(cam.usedBytes), Format.bytes(cam.limitBytes)) +
                        if (compact && cam.protectedBytes > 0) " · " + stringResource(R.string.hud_protected, Format.bytes(cam.protectedBytes)) else "",
                    color = colorResource(if (cam.lowSpace) R.color.hud_warn else R.color.hud_text_sub), fontSize = 12.sp,
                )
                if (cam.protectedBytes > 0 && !compact) {
                    Text(stringResource(R.string.hud_protected, Format.bytes(cam.protectedBytes)), color = colorResource(R.color.protect), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MenuButton(
    restricted: Boolean, onTrips: () -> Unit, onClips: () -> Unit, onSettings: () -> Unit,
    onCamera: (() -> Unit)? = null, onCard: (() -> Unit)? = null, onHome: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                if (restricted) Toast.makeText(context, R.string.restricted_while_moving, Toast.LENGTH_SHORT).show() else open = true
            },
            modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(colorResource(R.color.hud_panel_bg)),
        ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu), tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onHome != null) DropdownMenuItem(text = { Text(stringResource(R.string.menu_home)) }, onClick = { open = false; onHome() })
            if (onCamera != null) DropdownMenuItem(text = { Text(stringResource(R.string.camera_open_menu)) }, onClick = { open = false; onCamera() })
            if (onCard != null) DropdownMenuItem(text = { Text(stringResource(R.string.card_while_recording)) }, onClick = { open = false; onCard() })
            DropdownMenuItem(text = { Text(stringResource(R.string.trips_title)) }, onClick = { open = false; onTrips() })
            DropdownMenuItem(text = { Text(stringResource(R.string.clips_title)) }, onClick = { open = false; onClips() })
            DropdownMenuItem(text = { Text(stringResource(R.string.settings_title)) }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
private fun WarningBar(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(colorResource(R.color.hud_warn).copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

/** 保護した・衝撃を検知した（4 秒だけ出す）。 */
@Composable
private fun FlashBar(flash: jp.kjtr.drivelogger.service.Flash?, now: Long) {
    val f = flash ?: return
    if (f.type == jp.kjtr.drivelogger.service.DriveController.FLASH_PHOTO) return
    // 長時間録画を空き不足で止めた知らせは長めに
    val longStop = f.type == jp.kjtr.drivelogger.service.DriveController.FLASH_LONG_STORAGE
    if (now - f.at > if (longStop) 15_000 else 4_000) return
    val text = when (f.type) {
        jp.kjtr.drivelogger.service.DriveController.FLASH_LONG_STORAGE -> stringResource(R.string.flash_long_storage)
        TripEventEntity.TYPE_IMPACT -> if (f.files > 0) stringResource(R.string.flash_impact_protected, f.g ?: 0.0) else stringResource(R.string.flash_impact, f.g ?: 0.0)
        jp.kjtr.drivelogger.service.DriveController.FLASH_GPS_AUTO -> stringResource(R.string.flash_gps_auto)
        else -> stringResource(R.string.flash_protected, f.files)
    }
    Text(
        text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colorResource(R.color.protect_dark)).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 録画中に写真を撮った（写真のボタンの上に小さく 2 秒だけ。運転の邪魔をしない）。 */
@Composable
private fun PhotoFlash(flash: jp.kjtr.drivelogger.service.Flash?, now: Long) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(flash) {
        // 1 秒ごとの now より細かく消すため
        while (flash != null && System.currentTimeMillis() - flash.at <= 2_100) {
            tick = System.currentTimeMillis()
            delay(250)
        }
        tick = System.currentTimeMillis()
    }
    val f = flash ?: return
    if (f.type != jp.kjtr.drivelogger.service.DriveController.FLASH_PHOTO || maxOf(now, tick) - f.at > 2_000) return
    Text(
        stringResource(R.string.photo_taken), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC1565C0)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 走行画面の操作（0.6.0〜）: ［保護］（録画中だけ）［GPS 記録 / GPS 停止］［録画 / 録画 停止］［駐車する］［終了］。
 * GPS 記録・録画は記録中なら塗りつぶし（止めるボタン）、止まっていれば暗い地（始めるボタン）。
 */
@Composable
private fun DrivingButtons(
    gpsOn: Boolean, videoOn: Boolean, onGps: (Boolean) -> Unit,
    canProtect: Boolean, onProtect: () -> Unit, onVideo: (Boolean) -> Unit, onFinish: () -> Unit,
    canPark: Boolean, onPark: () -> Unit,
    /** 次の［録画］の方式と、その切替（録画中は null＝出さない）。 */
    mode: RecordMode = RecordMode.LOOP, onToggleMode: (() -> Unit)? = null,
    vertical: Boolean = false, modifier: Modifier = Modifier,
) {
    if (vertical) {
        // 横向き: 右端に縦に（保護をいちばん大きく）
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canProtect) {
                BigButton(
                    text = stringResource(R.string.protect), sub = null, icon = { Icon(Icons.Filled.Shield, null) }, primary = true,
                    color = colorResource(R.color.protect), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), stacked = true, minHeight = 44.dp,
                ) { onProtect() }
            }
            BigButton(
                text = stringResource(if (gpsOn) R.string.gps_off else R.string.gps_on), sub = null,
                icon = { Icon(if (gpsOn) Icons.Filled.LocationOff else Icons.Filled.Route, null) },
                primary = gpsOn, color = colorResource(R.color.recording), modifier = Modifier.fillMaxWidth(), stacked = true, minHeight = 44.dp,
            ) { onGps(!gpsOn) }
            if (onToggleMode != null) ModeSwitch(mode, onToggleMode, Modifier.fillMaxWidth())
            BigButton(
                text = stringResource(if (videoOn) R.string.video_off else R.string.video_on), sub = null,
                icon = { Icon(if (videoOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam, null) },
                primary = videoOn, color = colorResource(R.color.recording), modifier = Modifier.fillMaxWidth(), stacked = true, minHeight = 44.dp,
            ) { onVideo(!videoOn) }
            BigButton(
                text = stringResource(R.string.park), sub = null, icon = { Icon(Icons.Filled.LocalParking, null) },
                primary = canPark, color = colorResource(R.color.park), modifier = Modifier.fillMaxWidth(), stacked = true, minHeight = 44.dp, enabled = canPark,
            ) { onPark() }
            BigButton(
                text = stringResource(R.string.finish), sub = null, icon = { Icon(Icons.Filled.Stop, null) }, primary = false,
                color = colorResource(R.color.brand_primary), modifier = Modifier.fillMaxWidth(), stacked = true, minHeight = 44.dp,
            ) { onFinish() }
        }
        return
    }
    // 4 つ以上並ぶので、アイコンを文字の上にして幅を詰める
    val stacked = true
    Row(modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        if (canProtect) {
            BigButton(
                text = stringResource(R.string.protect), sub = null, icon = { Icon(Icons.Filled.Shield, null) }, primary = true,
                color = colorResource(R.color.protect), modifier = Modifier.weight(1.2f).heightIn(min = 72.dp), stacked = stacked,
            ) { onProtect() }
        }
        BigButton(
            text = stringResource(if (gpsOn) R.string.gps_off else R.string.gps_on), sub = if (gpsOn) stringResource(R.string.gps_logging_now) else null,
            icon = { Icon(if (gpsOn) Icons.Filled.LocationOff else Icons.Filled.Route, null) },
            primary = gpsOn, color = colorResource(R.color.recording), modifier = Modifier.weight(1f), stacked = stacked,
        ) { onGps(!gpsOn) }
        // ［録画］の上に方式の切替（録画していない間だけ。押すたびにループ ⇄ 長時間）
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onToggleMode != null) ModeSwitch(mode, onToggleMode, Modifier.fillMaxWidth())
            BigButton(
                text = stringResource(if (videoOn) R.string.video_off else R.string.video_on), sub = if (videoOn) stringResource(R.string.video_recording_now) else null,
                icon = { Icon(if (videoOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam, null) },
                primary = videoOn, color = colorResource(R.color.recording), modifier = Modifier.fillMaxWidth(), stacked = stacked,
            ) { onVideo(!videoOn) }
        }
        // ［駐車する］: 停車中だけ押せる
        BigButton(
            text = stringResource(R.string.park), sub = null, icon = { Icon(Icons.Filled.LocalParking, null) },
            primary = canPark, color = colorResource(R.color.park), modifier = Modifier.weight(1f), stacked = stacked, enabled = canPark,
        ) { onPark() }
        BigButton(
            text = stringResource(R.string.finish), sub = null, icon = { Icon(Icons.Filled.Stop, null) }, primary = false,
            color = colorResource(R.color.brand_primary), modifier = Modifier.weight(1f), stacked = stacked,
        ) { onFinish() }
    }
}

/** 録画の方式の切替（小さな丸角の札。押すとループ ⇄ 長時間。0.7.0〜）。長時間は赤い縁で目立たせる。 */
@Composable
private fun ModeSwitch(mode: RecordMode, onToggle: () -> Unit, modifier: Modifier) {
    val long = mode == RecordMode.LONG
    val label = stringResource(if (long) R.string.record_mode_long_short else R.string.record_mode_loop_short)
    val desc = stringResource(R.string.video_mode_switch, label)
    Row(
        modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(10.dp))
            .background(if (long) colorResource(R.color.recording).copy(alpha = 0.85f) else Color(0xE6202020))
            .clickable(onClickLabel = desc) { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            " " + stringResource(R.string.video_mode_chip, label), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false,
        )
    }
}

@Composable
private fun BigButton(
    text: String, sub: String?, icon: @Composable () -> Unit, primary: Boolean, color: Color, modifier: Modifier,
    stacked: Boolean = false, enabled: Boolean = true, minHeight: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight ?: if (stacked) 52.dp else 60.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = if (stacked) 4.dp else 8.dp, vertical = 8.dp),
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)
        else ButtonDefaults.buttonColors(
            containerColor = Color(0xE6202020), contentColor = Color.White,
            disabledContainerColor = Color(0xB3202020), disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (stacked) {
                icon()
                Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.width(6.dp))
                    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            if (sub != null) Text(sub, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        }
    }
}

/**
 * 駐車中の画面（DESIGN.md §4.4）。走行の GPS は止めている。経過時間・車までの方角と距離（［現在地を測る］か写真を撮ったときに 1 回だけ測る）・
 * ［写真を撮る］［ドライブを再開］［ドライブを終える］。
 */
@Composable
private fun ParkedScreen(
    vm: MainViewModel, d: jp.kjtr.drivelogger.record.DriveState, now: Long,
    onCamera: () -> Unit, onTrips: () -> Unit, onSettings: () -> Unit, onHome: () -> Unit, onFinish: () -> Unit,
) {
    val here by vm.shooter.hereFix.collectAsStateWithLifecycle()
    val measuring by vm.shooter.measuring.collectAsStateWithLifecycle()
    val walk by vm.session.walkLine.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val photos by remember(d.tripId) { vm.photos.forTrip(d.tripId) }.collectAsStateWithLifecycle(emptyList())
    val parkPhotos = photos.filter { it.parkId != null && it.parkId == d.parkId }
    val car = if (d.parkLat != null && d.parkLon != null) jp.kjtr.drivelogger.geo.LatLon(d.parkLat, d.parkLon) else null
    // 駐車を始めたときは前の現在地を使わない
    LaunchedEffect(d.parkStartedAt) { if (here?.second?.let { it < (d.parkStartedAt ?: 0L) } == true) vm.shooter.clearHere() }
    val h = here?.takeIf { it.second >= (d.parkStartedAt ?: 0L) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ParkMap(car = car, here = h?.first, walk = walk, photos = parkPhotos, mapKind = settings.mapKind, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).hudPanel()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalParking, null, tint = colorResource(R.color.park_light))
                        Text(" " + stringResource(R.string.parked_title), color = colorResource(R.color.hud_text), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.parked_elapsed, Format.duration(now - (d.parkStartedAt ?: now))),
                        color = colorResource(R.color.hud_text), fontSize = 16.sp,
                    )
                    Text(stringResource(R.string.parked_gps_off), color = colorResource(R.color.hud_text_sub), fontSize = 12.sp)
                    if (settings.parkWalkTrack) Text(stringResource(R.string.parked_walk, Format.distance(d.walkDistanceM)), color = colorResource(R.color.hud_text_sub), fontSize = 12.sp)
                }
                MenuButton(false, onTrips, onClips = {}, onSettings = onSettings, onHome = onHome)
            }
            Spacer(Modifier.height(8.dp))
            // 車までの方角と距離（最後に測った現在地から）
            Column(Modifier.hudPanel()) {
                if (car == null) {
                    Text(stringResource(R.string.parked_no_car_pos), color = colorResource(R.color.hud_text), fontSize = 14.sp)
                } else if (h == null) {
                    Text(stringResource(R.string.parked_measure_hint), color = colorResource(R.color.hud_text), fontSize = 14.sp)
                } else {
                    val dist = jp.kjtr.drivelogger.geo.Geo.distanceM(h.first, car)
                    val brg = jp.kjtr.drivelogger.geo.Bearing.initialDeg(h.first, car)
                    Text(
                        stringResource(R.string.parked_to_car, Format.distance(dist), BurnInText.heading(brg)),
                        color = colorResource(R.color.hud_text), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.parked_measured_at, Format.clock(h.second)), color = colorResource(R.color.hud_text_sub), fontSize = 12.sp)
                }
                TextButton(onClick = { vm.measureHere() }, enabled = !measuring) {
                    Text(stringResource(if (measuring) R.string.parked_measuring else R.string.parked_measure), color = Color.White)
                }
            }
            if (parkPhotos.isNotEmpty()) {
                Text(
                    stringResource(R.string.parked_photos, parkPhotos.size), color = colorResource(R.color.hud_text), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp).hudPanel(),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(
                    text = stringResource(R.string.parked_photo), sub = null, icon = { Icon(Icons.Filled.PhotoCamera, null) }, primary = true,
                    color = Color(0xFF1565C0), modifier = Modifier.weight(1f), stacked = true,
                ) { onCamera() }
                BigButton(
                    text = stringResource(R.string.parked_resume), sub = null, icon = { Icon(Icons.Filled.DirectionsCar, null) }, primary = true,
                    color = colorResource(R.color.brand_primary), modifier = Modifier.weight(1.2f), stacked = true,
                ) { vm.resumeDrive() }
                BigButton(
                    text = stringResource(R.string.parked_finish), sub = null, icon = { Icon(Icons.Filled.Stop, null) }, primary = false,
                    color = colorResource(R.color.brand_primary), modifier = Modifier.weight(1f), stacked = true,
                ) { onFinish() }
            }
        }
    }
}
