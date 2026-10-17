package jp.kjtr.drivelogger.record

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 走行サービスを始められなかった理由。 */
enum class StartFailure {
    /** 位置・カメラの権限が無い・外された（SecurityException）。 */
    PERMISSION,
    /** アプリが前面にないので始められない（ForegroundServiceStartNotAllowedException）。 */
    NOT_FOREGROUND,
    /** 4 秒待ってもサービスが始まらない（端末の電池の制限など）。 */
    TIMEOUT,
    OTHER,
}

/**
 * 走行サービスの様子（アプリ全体で 1 つ。サービスが書き、画面が読む）。
 * 位置の点を取り込むのはサービスだけ（[jp.kjtr.drivelogger.service.DriveService]）。
 */
class RecorderStatus {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    private val _startFailure = MutableStateFlow<StartFailure?>(null)
    val startFailure: StateFlow<StartFailure?> = _startFailure

    private val _locationOff = MutableStateFlow(false)
    val locationOff: StateFlow<Boolean> = _locationOff

    private val _noFix = MutableStateFlow(false)
    val noFix: StateFlow<Boolean> = _noFix

    fun setRunning(v: Boolean) {
        _serviceRunning.value = v
        if (!v) {
            _locationOff.value = false
            _noFix.value = false
        }
    }

    fun setFailure(f: StartFailure?) {
        _startFailure.value = f
    }

    fun setLocationOff(v: Boolean) {
        _locationOff.value = v
    }

    fun setNoFix(v: Boolean) {
        _noFix.value = v
    }

    companion object {
        fun classify(e: Throwable): StartFailure = when {
            e is SecurityException -> StartFailure.PERMISSION
            Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException -> StartFailure.NOT_FOREGROUND
            else -> StartFailure.OTHER
        }

        fun notificationsAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun hasBackgroundLocation(context: Context): Boolean {
            val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT < 29) return fine
            return fine && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        fun hasLocation(context: Context): Boolean =
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun hasCamera(context: Context): Boolean = granted(context, Manifest.permission.CAMERA)

        fun hasAudio(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)

        fun granted(context: Context, p: String): Boolean = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        fun ignoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
        }
    }
}
