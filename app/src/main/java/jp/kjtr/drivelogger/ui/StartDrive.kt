package jp.kjtr.drivelogger.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.verticalScroll
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.LongRecordingPolicy
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.service.StartMode

/** 記録を始める操作（0.6.0〜。ドライブを開く・GPS 記録・録画）。 */
enum class RecordAction {
    /** 新しいドライブを開く（記録しない）。 */
    OPEN_DRIVE,
    /** 新しいドライブを開いて GPS 記録から。 */
    OPEN_DRIVE_GPS,
    /** 新しいドライブを開いて GPS 記録＋録画。 */
    OPEN_DRIVE_VIDEO,
    /** 開いているドライブで GPS 記録を始める。 */
    GPS,
    /** 開いているドライブで録画を始める（GPS 記録が止まっていれば一緒に）。 */
    VIDEO,
}

/**
 * 記録を始める窓口（ホームの「ドライブを始める」、走行画面の［GPS 記録］［録画］。0.5.0 の rememberDriveStarter を 0.6.0 で広げた）。
 * 位置・通知の権限 →（録画なら）熱の注意・カメラ（・マイク）の権限 → ViewModel。記録しないで開くときは、位置の権限は聞くが断られても開く
 * （地図に現在地を出すため）。カメラを断られたら GPS 記録だけで始める。熱の注意のダイアログもここで出す。
 */
@Composable
fun rememberRecordStarter(vm: MainViewModel): (RecordAction) -> Unit {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<RecordAction?>(null) }
    var heatNotice by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 長時間録画を始める前の確認（空き容量・おおよその時間・電源と熱の注意。0.7.0〜）。 */
    var longAsk by remember { mutableStateOf<LongAsk?>(null) }
    /** ホームの［記録＋録画］で、この回だけ方式を変えた（長時間 → ループにした）。 */
    var openVideoMode by remember { mutableStateOf<RecordMode?>(null) }

    fun run(a: RecordAction, cameraOk: Boolean = true) {
        when (a) {
            RecordAction.OPEN_DRIVE -> vm.startDrive(StartMode.NONE)
            RecordAction.OPEN_DRIVE_GPS -> vm.startDrive(StartMode.GPS)
            RecordAction.OPEN_DRIVE_VIDEO -> vm.startDrive(if (cameraOk) StartMode.GPS_VIDEO else StartMode.GPS, openVideoMode)
            RecordAction.GPS -> vm.startGps()
            RecordAction.VIDEO -> if (cameraOk) vm.setVideo(true) else vm.startGps()
        }
    }

    val videoPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (settings.audio) add(Manifest.permission.RECORD_AUDIO)
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res[Manifest.permission.CAMERA] == true || RecorderStatus.hasCamera(context)
        val a = pending
        pending = null
        if (a != null) {
            // カメラが使えなければ GPS 記録だけで始める
            if (!ok) Toast.makeText(context, R.string.perm_camera_needed, Toast.LENGTH_LONG).show()
            run(a, cameraOk = ok)
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val a = pending ?: return@rememberLauncherForActivityResult
        if (RecorderStatus.hasLocation(context)) vm.locationHub.restart()
        if (a == RecordAction.OPEN_DRIVE) {
            pending = null
            run(a)
            return@rememberLauncherForActivityResult
        }
        if (!RecorderStatus.hasLocation(context)) {
            Toast.makeText(context, R.string.perm_location_needed, Toast.LENGTH_LONG).show()
            pending = null
            return@rememberLauncherForActivityResult
        }
        val video = a == RecordAction.OPEN_DRIVE_VIDEO || a == RecordAction.VIDEO
        if (video && !videoPermissions.all { RecorderStatus.granted(context, it) }) {
            videoLauncher.launch(videoPermissions.toTypedArray())
        } else {
            pending = null
            run(a)
        }
    }

    heatNotice?.let { action ->
        AlertDialog(
            onDismissRequest = { heatNotice = null },
            title = { Text(stringResource(R.string.heat_title)) },
            text = { Text(stringResource(R.string.heat_text)) },
            confirmButton = {
                TextButton(onClick = {
                    heatNotice = null
                    vm.updateSettings { it.copy(heatNoticeShown = true) }
                    action()
                }) { Text(stringResource(R.string.heat_ok)) }
            },
            dismissButton = { TextButton(onClick = { heatNotice = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    longAsk?.let { ask ->
        val useLoop = {
            longAsk = null
            if (ask.action == RecordAction.VIDEO) vm.controller.setNextVideoMode(RecordMode.LOOP) else openVideoMode = RecordMode.LOOP
            ask.go()
        }
        AlertDialog(
            onDismissRequest = { longAsk = null },
            title = { Text(stringResource(R.string.long_start_title)) },
            text = {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(
                        if (ask.canStart) {
                            stringResource(
                                R.string.long_start_text, Format.bytes(ask.freeBytes), settings.quality.label, settings.fps,
                                Format.approxHours(ask.hours), settings.longSegmentMinutes,
                            )
                        } else stringResource(R.string.long_no_space, Format.bytes(ask.freeBytes)),
                    )
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    if (ask.canStart) {
                        TextButton(onClick = {
                            longAsk = null
                            // 長時間録画の注意は熱の注意を含む
                            vm.updateSettings { it.copy(heatNoticeShown = true) }
                            ask.go()
                        }) { Text(stringResource(R.string.long_start_ok)) }
                    }
                    TextButton(onClick = useLoop) { Text(stringResource(R.string.long_start_use_loop)) }
                    TextButton(onClick = { longAsk = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    return { a ->
        val video = a == RecordAction.OPEN_DRIVE_VIDEO || a == RecordAction.VIDEO
        openVideoMode = null
        val go: () -> Unit = {
            pending = a
            val base = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (base.all { RecorderStatus.granted(context, it) } || RecorderStatus.hasLocation(context) && Build.VERSION.SDK_INT < 33) {
                if (video && !videoPermissions.all { RecorderStatus.granted(context, it) }) {
                    videoLauncher.launch(videoPermissions.toTypedArray())
                } else {
                    pending = null
                    run(a)
                }
            } else {
                locationLauncher.launch(base.toTypedArray())
            }
        }
        // 長時間録画は毎回、空き容量と注意を出してから（ループ録画は初回だけ熱の注意）
        val mode = when (a) {
            RecordAction.VIDEO -> vm.controller.nextVideoMode.value
            RecordAction.OPEN_DRIVE_VIDEO -> settings.recordMode
            else -> RecordMode.LOOP
        }
        if (video && mode == RecordMode.LONG) {
            val free = vm.clips.freeBytes()
            val bph = LongRecordingPolicy.bytesPerHour(settings.quality, settings.fps, settings.audio)
            longAsk = LongAsk(a, free, LongRecordingPolicy.hoursLeft(free, bph), LongRecordingPolicy.canStart(free, bph)) {
                go()
            }
        } else if (video && !settings.heatNoticeShown) heatNotice = go else go()
    }
}

/** 長時間録画を始める前の確認の中身。 */
private class LongAsk(val action: RecordAction, val freeBytes: Long, val hours: Double, val canStart: Boolean, val go: () -> Unit)
