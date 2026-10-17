package jp.kjtr.drivelogger.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import jp.kjtr.drivelogger.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 画面に渡す更新の確認の様子。[banner] = ホームの帯に出す版（OFF・［あとで］なら null）。 */
data class UpdateUi(
    val enabled: Boolean,
    val status: UpdateStatus,
    val newer: UpdateRelease?,
    val banner: UpdateRelease?,
    val lastSuccessMs: Long,
    val lastAttemptMs: Long,
)

/**
 * 更新の確認の Android 側（自転車ルートロガーと同じ）。[UpdateChecker] をメインスレッドで動かし、取得だけ IO スレッドで行う。
 * 覚えておく版と時刻は SharedPreferences `update`（設定の `settings` とは別のファイル。バックアップの対象外）。
 * [enabled] = 設定の「更新の確認」（OFF の間は一切通信しない）。
 */
class UpdateManager(context: Context, private val scope: CoroutineScope, private val enabled: () -> Boolean) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val checker = UpdateChecker(
        current = BuildConfig.VERSION_NAME,
        available = true,
        fetch = ::fetchAsync,
        load = ::readMemo,
        save = ::writeMemo,
    )

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<UpdateUi> = _state

    /** 起動時の確認はプロセスごとに 1 回。 */
    private var launchDone = false

    /** ホームが出たときに呼ぶ: 起動して最初の 1 回だけ、裏で releases.json を読む（OFF・6 時間以内なら何もしない）。 */
    fun checkOnLaunch() {
        if (launchDone) return
        launchDone = true
        val started = checker.onLaunch(enabled())
        Log.i(TAG, "launch check: fetch=$started status=${checker.status}")
        publish()
    }

    /** 設定の［今すぐ確認］。 */
    fun checkNow() {
        val started = checker.checkNow(enabled())
        Log.i(TAG, "check now: fetch=$started")
        publish()
    }

    /** ホームの帯の［あとで］。 */
    fun dismiss() {
        checker.dismiss()
        publish()
    }

    fun apkUrl(r: UpdateRelease): String = checker.apkUrl(r)

    /** debug ビルドだけ: いまの版を [version] として比べてすぐ確かめる（古い版として動かす確認用。DESIGN.md §10）。 */
    fun pretend(version: String) {
        if (!BuildConfig.DEBUG) return
        val started = checker.pretend(version, enabled())
        Log.i(TAG, "pretend $version -> ${checker.compareAs} fetch=$started")
        publish()
    }

    /** 設定の ON/OFF が変わったときなどに、画面の様子を作り直す。 */
    fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): UpdateUi {
        val on = enabled()
        return UpdateUi(on, checker.status, checker.newer, checker.banner(on), checker.memo.lastSuccessMs, checker.memo.lastAttemptMs)
    }

    private fun fetchAsync(done: (String?) -> Unit) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { fetchText(UpdateLinks.RELEASES_URL) }
            done(text)
            Log.i(TAG, "status=${checker.status} newer=${checker.newer?.version}")
            publish()
        }
    }

    private fun readMemo(): UpdateMemo = UpdateMemo(
        prefs.getLong(K_ATTEMPT, 0L), prefs.getLong(K_SUCCESS, 0L), prefs.getString(K_TAG, "") ?: "", prefs.getString(K_NOTES, "") ?: "",
    )

    private fun writeMemo(m: UpdateMemo) {
        prefs.edit()
            .putLong(K_ATTEMPT, m.lastAttemptMs)
            .putLong(K_SUCCESS, m.lastSuccessMs)
            .putString(K_TAG, m.tag)
            .putString(K_NOTES, m.notes)
            .apply()
    }

    companion object {
        private const val TAG = "DL-Update"
        const val PREFS = "update"
        private const val K_ATTEMPT = "last_attempt"
        private const val K_SUCCESS = "last_success"
        private const val K_TAG = "tag"
        private const val K_NOTES = "notes"

        /**
         * HttpURLConnection の GET 1 回（https だけ・`useCaches = false`・ヘッダは既定のまま・タイムアウト各 8 秒・2xx 以外と 1 MB 超えは失敗）。
         * 失敗は null。IO スレッドで呼ぶ。
         */
        /** [max] バイトまで読む（超えたら失敗）。 */
        fun readCapped(input: java.io.InputStream, max: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw java.io.IOException("too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        fun fetchText(url: String): String? {
            if (!url.startsWith("https://")) return null
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = UpdatePolicy.TIMEOUT_MS
                    conn.readTimeout = UpdatePolicy.TIMEOUT_MS
                    conn.useCaches = false
                    val code = conn.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val bytes = conn.inputStream.use { readCapped(it, UpdatePolicy.MAX_BYTES.toLong()) }
                    String(bytes, Charsets.UTF_8).also { Log.i(TAG, "fetched ${it.length} chars") }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.i(TAG, "fetch failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
                null
            }
        }
    }
}
