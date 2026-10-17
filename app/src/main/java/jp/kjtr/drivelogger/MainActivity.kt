package jp.kjtr.drivelogger

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import jp.kjtr.drivelogger.ui.AppNav
import jp.kjtr.drivelogger.ui.DriveTheme
import jp.kjtr.drivelogger.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 では edge-to-edge が強制。各画面は WindowInsets で避ける。
        enableEdgeToEdge()
        reportRotation()
        setContent {
            DriveTheme { AppNav(vm) }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reportRotation()
    }

    override fun onResume() {
        super.onResume()
        reportRotation()
    }

    /** 録画の向き（次のファイルから）に、いまの画面の回転を伝える。 */
    private fun reportRotation() {
        runCatching { (application as DriveApp).dashcam.setDisplayRotation(ContextCompat.getDisplayOrDefault(this).rotation) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (!BuildConfig.DEBUG) return
        // debug だけ: 更新の確認を古い版として試す（`am start … --es updateAs 0.0.1`）
        intent.getStringExtra("updateAs")?.let { (application as DriveApp).updates.pretend(it) }
        // debug だけ: 動画のフレームを PNG に書き出す（焼き込みの確認。`--es extractFrame DL_….mp4 --el atMs 5000`）
        intent.getStringExtra("extractFrame")?.let { vm.debugExtractFrame(it, intent.getLongExtra("atMs", 3_000L), intent.getStringExtra("dir")) }
        // debug だけ: SNS 用の書き出しの画面を開く（`--el openSns <clip id>`）
        intent.getLongExtra("openSns", -1L).takeIf { it > 0 }?.let { vm.debugNavigate("sns/$it") }
        // debug だけ: 録画中の写真を ImageCapture 無しで（プレビューのフレームから）撮る試験。次に録画を ON にしたときから（`--ez photoFallback true`）
        if (intent.hasExtra("photoFallback")) {
            (application as DriveApp).dashcam.debugNoImageCapture = intent.getBooleanExtra("photoFallback", false)
            vm.toast("photoFallback=" + intent.getBooleanExtra("photoFallback", false))
        }
        // debug だけ: 長時間録画の空き容量の判定に使う空きを MB で（`--el debugFreeMb 1100`。0 以下で端末の空きに戻す）
        if (intent.hasExtra("debugFreeMb")) {
            val mb = intent.getLongExtra("debugFreeMb", -1L)
            (application as DriveApp).dashcam.debugFreeBytes = if (mb > 0) mb * 1024 * 1024 else null
            vm.toast("debugFreeMb=$mb")
        }
        // debug だけ: トリップの詳細・SNS 用の画像を開く（`--el openTrip <id>` / `--el openCard <id>`）
        intent.getLongExtra("openTrip", -1L).takeIf { it > 0 }?.let { vm.debugNavigate("trip/$it") }
        intent.getLongExtra("openCard", -1L).takeIf { it > 0 }?.let { vm.debugNavigate("card/$it") }
    }
}
