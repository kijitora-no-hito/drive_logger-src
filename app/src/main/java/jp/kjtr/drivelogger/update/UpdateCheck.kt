package jp.kjtr.drivelogger.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 更新の確認。自転車ルートロガー（cycle_route_logger）の update/UpdateCheck.kt と同じ仕組み・同じ挙動。
 * Android・通信に依存しない純 Kotlin（単体テストあり）。
 *
 * 公開リポジトリ（kijitora-no-hito/android-apps）の `releases.json`（全アプリのリリース一覧）を読み、`folder == "drive_logger"` の
 * 版がいまの版より新しければ、ホーム（ドライブしていないとき）に控えめな帯を出す（Android の通知は使わない）。取得そのものは [UpdateManager]
 * （HttpURLConnection の GET 1 回）。設定で OFF なら一切通信しない。
 */

/**
 * 版（`0.3.0` など）。[nums] = 数の組（`0.9` < `0.10`）、[pre] = `-` の後ろ（`dev` など。空 = 正式版）。
 * 同じ数の組なら正式版の方が新しい（`0.4.0-dev` < `0.4.0`）。足りない桁は 0（`0.4` = `0.4.0`）。
 */
data class AppVersion(val nums: List<Int>, val pre: String) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(nums.size, other.nums.size)) {
            val c = nums.getOrElse(i) { 0 }.compareTo(other.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        return pre.compareTo(other.pre)
    }

    /** 画面に出す形。 */
    override fun toString(): String = nums.joinToString(".") + if (pre.isEmpty()) "" else "-$pre"

    companion object {
        /** 頭の `v` の有無を問わずに読む。読めなければ null（数でない・空・数が 5 つ以上）。 */
        fun parse(text: String): AppVersion? {
            val s = text.trim().removePrefix("v").removePrefix("V")
            if (s.isEmpty()) return null
            val head = s.substringBefore('-')
            val pre = if (s.contains('-')) s.substringAfter('-') else ""
            val parts = head.split('.')
            if (parts.size > 4) return null
            val nums = parts.map { p -> if (p.isEmpty() || !p.all { it.isDigit() } || p.length > 6) return null else p.toInt() }
            return AppVersion(nums, pre)
        }
    }
}

/** releases.json のこのアプリの 1 件。[version] = タグの `v` の後ろ（`0.4.0`）、[notes] = リリースの本文（Markdown）。 */
data class UpdateRelease(val tag: String, val version: String, val notes: String) {
    val parsed: AppVersion? get() = AppVersion.parse(version)
}

/** releases.json の読み取り。 */
object Releases {
    /** releases.json の中のこのアプリのフォルダ名。 */
    const val FOLDER = "drive_logger"
    /** タグの頭（`drivelogger-v0.1.0`）。ダウンロードの APK の名前の頭も同じ。 */
    const val TAG_PREFIX = "drivelogger-v"

    /** タグに使ってよい文字（URL にそのまま入れるので、それ以外を含むタグは読まない）。 */
    private val TAG_OK = Regex("[A-Za-z0-9._-]+")

    /**
     * [text] から [FOLDER] のエントリを読む（複数あれば版が一番新しいもの）。無い・壊れた JSON・タグの形が違う → null。
     * `apk`（公開する人の PC のパス）・`sha256` は使わない。
     */
    fun parse(text: String): UpdateRelease? = try {
        val root = Json.parseToJsonElement(text) as? JsonObject
        val list = root?.get("releases") as? JsonArray
        var best: UpdateRelease? = null
        for (entry in list.orEmpty()) {
            if (entry !is JsonObject || entry.str("folder") != FOLDER) continue
            val tag = entry.str("tag")?.trim().orEmpty()
            val ver = tag.removePrefix(TAG_PREFIX)
            if (tag.startsWith(TAG_PREFIX) && TAG_OK.matches(tag) && AppVersion.parse(ver) != null) {
                val r = UpdateRelease(tag, ver, entry.str("notes") ?: "")
                if (best == null || r.parsed!! > best.parsed!!) best = r
            }
        }
        best
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 更新内容の箇条書き（notes の `- ` の行、先頭 [max] 行。`**` の強調は外す）。見出し・説明ページの URL・SHA-256 の行は入れない。
     */
    fun summary(notes: String, max: Int = 6): List<String> = notes.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("・") }
        .map { it.removePrefix("- ").removePrefix("・").replace("**", "").trim() }
        .filter { it.isNotEmpty() }
        .take(max)
}

/** 取得先・リンク（公開物の置き場は android-apps の Releases と GitHub Pages）。 */
object UpdateLinks {
    const val RELEASES_URL = "https://raw.githubusercontent.com/kijitora-no-hito/android-apps/main/releases.json"
    const val PAGE_URL = "https://kijitora-no-hito.github.io/android-apps/drive_logger/"
    private const val DOWNLOAD_BASE = "https://github.com/kijitora-no-hito/android-apps/releases/download/"

    /** その版の APK（`drivelogger-<版>-release.apk`）。 */
    fun apkUrl(r: UpdateRelease): String = DOWNLOAD_BASE + r.tag + "/drivelogger-" + r.version + "-release.apk"
}

/** いつ取りに行くか。 */
object UpdatePolicy {
    /** 前回の試行からこれ以内なら起動時の確認を省く（時間）。 */
    const val INTERVAL_HOURS = 6
    const val INTERVAL_MS = INTERVAL_HOURS * 3_600_000L
    /** 接続・読み取りのタイムアウト（ミリ秒。どちらも）。 */
    const val TIMEOUT_MS = 8_000
    /** これより大きい応答は読まない（releases.json は数十 KB）。 */
    const val MAX_BYTES = 1 shl 20

    /** 起動時に取りに行ってよいか（前回の試行から [INTERVAL_MS] 経った。時計が戻っていたら取りに行く）。 */
    fun isDue(now: Long, lastAttempt: Long): Boolean = lastAttempt <= 0L || now < lastAttempt || now - lastAttempt >= INTERVAL_MS
}

/** 端末に覚えておくもの（前回の試行・成功の時刻と、最後に読めた版。オフラインでも帯を出せるように）。 */
data class UpdateMemo(
    val lastAttemptMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val tag: String = "",
    val notes: String = "",
) {
    /** 覚えている版（無ければ null）。 */
    val release: UpdateRelease?
        get() = if (tag.startsWith(Releases.TAG_PREFIX)) UpdateRelease(tag, tag.removePrefix(Releases.TAG_PREFIX), notes).takeIf { it.parsed != null } else null
}

/** 確認の状態（設定の状態の 1 行）。 */
enum class UpdateStatus { UNKNOWN, CHECKING, LATEST, AVAILABLE, FAILED }

/**
 * 更新の確認の流れ（メインスレッドだけで使う）。
 *
 * - [onLaunch]: 起動時（ホームが出た後に 1 回）。設定 OFF・取得できない環境・前回の試行から [UpdatePolicy.INTERVAL_HOURS] 時間以内なら何もしない。
 * - [checkNow]: 設定の［今すぐ確認］（間隔は見ない。OFF なら何もしない）。
 * - 失敗は黙って無視（状態が [UpdateStatus.FAILED] になるだけ。覚えている版はそのまま）。
 * - [dismiss]: ホームの［あとで］。その版は次に起動するまで出さない（メモリだけ）。
 *
 * [current] = いまの versionName、[available] = 取得できる環境か、[fetch] = 取得を始めて結果（失敗 = null）を
 * メインスレッドで返す、[load] / [save] = [UpdateMemo] の読み書き。
 */
class UpdateChecker(
    current: String,
    private val available: Boolean,
    private val fetch: ((String?) -> Unit) -> Unit,
    load: () -> UpdateMemo,
    private val save: (UpdateMemo) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 比べるいまの版（debug ビルドの確認用に [pretend] で下げられる）。 */
    var compareAs: AppVersion? = AppVersion.parse(current)
        private set

    var memo: UpdateMemo = load()
        private set

    var status: UpdateStatus = UpdateStatus.UNKNOWN
        private set

    /** いまの版より新しい版（無ければ null）。 */
    var newer: UpdateRelease? = null
        private set

    /** ［あとで］を押した版のタグ。 */
    var dismissedTag: String? = null
        private set

    /** 取りに行った回数（テスト・ログ用）。 */
    var fetches = 0
        private set

    init {
        evaluate(memo.release, fromCache = true)
    }

    /** 起動時の確認。取りに行ったら true。 */
    fun onLaunch(enabled: Boolean): Boolean {
        if (!enabled || !available || status == UpdateStatus.CHECKING) return false
        if (!UpdatePolicy.isDue(clock(), memo.lastAttemptMs)) return false
        start()
        return true
    }

    /** ［今すぐ確認］。取りに行ったら true。押したら［あとで］も取り消す（ホームにまた出す）。 */
    fun checkNow(enabled: Boolean): Boolean {
        if (!enabled || !available || status == UpdateStatus.CHECKING) return false
        dismissedTag = null
        start()
        return true
    }

    /** ホームの帯に出す版（OFF・無い・［あとで］を押した版なら null）。 */
    fun banner(enabled: Boolean): UpdateRelease? = if (!enabled) null else newer?.takeIf { it.tag != dismissedTag }

    fun dismiss() {
        dismissedTag = newer?.tag
    }

    /** ダウンロードの URL。 */
    fun apkUrl(r: UpdateRelease): String = UpdateLinks.apkUrl(r)

    /**
     * debug ビルドだけ: いまの版を [version] として比べ直し、すぐ取りに行く（古い版として動かす確認用）。
     * 読めない版なら何もしない。取りに行ったら true。
     */
    fun pretend(version: String, enabled: Boolean): Boolean {
        compareAs = AppVersion.parse(version) ?: return false
        dismissedTag = null
        evaluate(memo.release, fromCache = true)
        return checkNow(enabled)
    }

    private fun start() {
        val now = clock()
        status = UpdateStatus.CHECKING
        fetches++
        memo = memo.copy(lastAttemptMs = now)
        save(memo)
        fetch { text ->
            val r = text?.let { Releases.parse(it) }
            if (r == null) {
                status = UpdateStatus.FAILED
            } else {
                memo = memo.copy(lastSuccessMs = clock(), tag = r.tag, notes = r.notes)
                save(memo)
                evaluate(r, fromCache = false)
            }
        }
    }

    private fun evaluate(r: UpdateRelease?, fromCache: Boolean) {
        val cur = compareAs
        val v = r?.parsed
        newer = if (r != null && v != null && cur != null && v > cur) r else null
        status = when {
            newer != null -> UpdateStatus.AVAILABLE
            fromCache -> UpdateStatus.UNKNOWN
            else -> UpdateStatus.LATEST
        }
    }
}
