package jp.kjtr.drivelogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.record.StartFailure

/** 記録を裏で続けるための端末の設定画面を開く。 */
object BackgroundSettings {
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }
    }

    fun openNotificationSettings(context: Context) {
        val i = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        runCatching { context.startActivity(i) }.onFailure { openAppDetails(context) }
    }

    /** 電池の最適化から外す確認（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。開けなければ一覧、それも無理ならアプリの設定。 */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppDetails(context) }
    }
}

/** 記録を始められなかったときの説明（理由と対処）。 */
@Composable
fun RecordStartErrorDialog(failure: StartFailure, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (text, action) = when (failure) {
        StartFailure.PERMISSION -> R.string.rec_fail_permission to R.string.open_app_settings
        StartFailure.NOT_FOREGROUND -> R.string.rec_fail_not_foreground to null
        StartFailure.TIMEOUT -> R.string.rec_fail_timeout to R.string.open_battery_settings
        StartFailure.OTHER -> R.string.rec_fail_other to R.string.open_app_settings
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rec_fail_title)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            if (action != null) {
                TextButton(onClick = {
                    onDismiss()
                    if (action == R.string.open_battery_settings) BackgroundSettings.requestIgnoreBatteryOptimizations(context)
                    else BackgroundSettings.openAppDetails(context)
                }) { Text(stringResource(action)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = if (action != null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }) else null,
    )
}

/**
 * 設定画面の「バックグラウンドでの記録」: 通知・電池の最適化・位置の「常に許可」の状態と、設定を開くボタン。
 * どれも任意（押し付けない）。画面に戻ってきたら状態を読み直す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundRecordingSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val notif = remember(tick) { RecorderStatus.notificationsAllowed(context) }
    val battery = remember(tick) { RecorderStatus.ignoringBatteryOptimizations(context) }
    val always = remember(tick) { RecorderStatus.hasBackgroundLocation(context) }
    var explainAlways by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) BackgroundSettings.openNotificationSettings(context)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.bg_desc), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(if (notif) R.string.bg_notif_on else R.string.bg_notif_off))
        if (!notif) {
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else BackgroundSettings.openNotificationSettings(context)
            }) { Text(stringResource(R.string.bg_notif_allow)) }
        }

        Text(stringResource(if (battery) R.string.bg_battery_on else R.string.bg_battery_off))
        Text(stringResource(R.string.bg_battery_desc), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!battery) OutlinedButton(onClick = { BackgroundSettings.requestIgnoreBatteryOptimizations(context) }) { Text(stringResource(R.string.bg_battery_request)) }
            OutlinedButton(onClick = { BackgroundSettings.openAppDetails(context) }) { Text(stringResource(R.string.open_app_settings)) }
        }

        Text(stringResource(if (always) R.string.bg_always_on else R.string.bg_always_off))
        Text(stringResource(R.string.bg_always_desc), style = MaterialTheme.typography.bodySmall)
        if (!always && Build.VERSION.SDK_INT >= 29) {
            OutlinedButton(onClick = { explainAlways = true }) { Text(stringResource(R.string.bg_always_request)) }
        }
    }

    if (explainAlways) {
        AlertDialog(
            onDismissRequest = { explainAlways = false },
            title = { Text(stringResource(R.string.bg_always_title)) },
            text = { Text(stringResource(R.string.bg_always_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    explainAlways = false
                    val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (fine && Build.VERSION.SDK_INT >= 29) {
                        // Android 11+ はシステムの設定画面（位置情報 → 常に許可）が開く
                        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        BackgroundSettings.openAppDetails(context)
                    }
                }) { Text(stringResource(R.string.bg_always_go)) }
            },
            dismissButton = { TextButton(onClick = { explainAlways = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
