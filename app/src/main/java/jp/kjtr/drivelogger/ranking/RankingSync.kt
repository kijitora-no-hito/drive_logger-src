package jp.kjtr.drivelogger.ranking

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 設定・ランキングの画面に出す参加の状態。 */
data class RankingState(
    val enabled: Boolean,
    val name: String,
    val pending: Boolean,
    val lastSentAt: Long,
    val notice: String?,
    val baseUrl: String,
    val pendingDeletes: Int,
)

/** 参加・名前の変更の結果。 */
enum class JoinResult {
    OK, OFFLINE, BAD_NAME, BANNED,

    /** 記録中なので送らなかった（参加・名前は保存した。記録が終わったら送る）。 */
    HELD,
}

/** ランキングの画面に出す 1 つの板（[fetchedAt] に読んだ内容。[error] があれば今回は読めなかった）。 */
data class BoardView(val reply: BoardReply?, val fetchedAt: Long, val error: String?)

/**
 * オンラインランキングの送信・削除・読み込み（API.md §5）。アプリに 1 つ（Application が持つ）。
 * - 送るのは参加を ON にしている間だけ。値が変わったら 30 秒まとめて 1 回（毎回ぜんぶ）。
 * - 失敗・429 は「未送信」の印を残し、アプリを開いている間は少し待って送り直し、使い切ったら次にアプリを開いたとき（WorkManager は使わない）。
 * - OFF にしたら DELETE。届かなければ印を残して次の通信の機会に。
 * - [hold] が true の間（記録中・取り込み中）は送らない（値も計算しない）。終わって [watch] の Flow が流れたら送る。
 * このファイルは基準点めぐり・神社めぐりの写しに [hold] を足したもの（自転車ルートロガー・ドライブロガーで同じ形。パッケージだけ違う）。
 */
class RankingSync(
    context: Context,
    private val scope: CoroutineScope,
    private val appId: String,
    private val appVersion: String,
    private val userAgent: String,
    private val releaseBaseUrl: String,
    private val debug: Boolean,
    /** いまの記録から送る値を計算する（Room から読む）。 */
    private val snapshot: suspend () -> RankValues,
    /** true の間は送らない（記録中など）。終わったら [watch] に渡した Flow が流れるようにしておく。 */
    private val hold: () -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    val store = RankingStore(context)
    private val lock = Mutex()
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<RankingState> = _state
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 画面の下に出す知らせ（参加できなくなった・名前が使えない など）。 */
    val messages: SharedFlow<String> = _messages

    private var job: Job? = null
    private var jobAt = Long.MAX_VALUE
    private var failures = 0

    /** [hold] のために送らなかった（終わったら値が同じでも送る）。 */
    @Volatile private var held = false

    /** 接続先。release は定数 1 か所（BuildConfig.RANKING_BASE）だけ。debug だけ設定で変えられる。 */
    val baseUrl: String get() = if (debug) store.debugBaseUrl?.takeIf { it.isNotBlank() } ?: releaseBaseUrl else releaseBaseUrl

    private fun api() = RankingApi(baseUrl, appId, userAgent)

    private fun readState() = RankingState(
        enabled = store.enabled,
        name = store.name,
        pending = store.pendingSend,
        lastSentAt = store.lastSentAt,
        notice = store.notice,
        baseUrl = baseUrl,
        pendingDeletes = store.pendingDeletes.size,
    )

    private fun refresh() {
        _state.value = readState()
    }

    private fun sig(v: RankValues) = v.signature() + "|" + store.name

    /** 記録が変わるたびに流れるもの（Room の Flow。[hold] が終わったときにも流れること）を見張る。 */
    fun watch(changes: Flow<*>) {
        scope.launch {
            changes.collect {
                if (!store.enabled) return@collect
                if (hold()) {
                    held = true
                    return@collect
                }
                val v = runCatching { snapshot() }.getOrNull() ?: return@collect
                if (sig(v) != store.lastSentSig || held) {
                    held = false
                    store.pendingSend = true
                    refresh()
                    schedule(RankingRules.BATCH_MS)
                }
            }
        }
    }

    /** アプリを開いたとき（Activity の onStart）。消し残し・送り残し・1 時間以上たった分を送る。 */
    fun onAppOpen() {
        scope.launch {
            flushDeletes()
            if (!store.enabled) return@launch
            failures = 0
            if (hold()) {
                held = true
                return@launch
            }
            val v = runCatching { snapshot() }.getOrNull() ?: return@launch
            if (RankingRules.shouldSendOnOpen(store.pendingSend, sig(v) == store.lastSentSig, store.lastSentAt, now())) {
                schedule(OPEN_DELAY_MS)
            }
        }
    }

    /** [delayMs] 後に送る（もっと早い予定があればそちら）。 */
    @Synchronized
    private fun schedule(delayMs: Long) {
        val at = now() + delayMs
        if (job?.isActive == true && jobAt <= at) return
        job?.cancel()
        jobAt = at
        job = scope.launch {
            delay(delayMs)
            synchronized(this@RankingSync) {
                job = null
                jobAt = Long.MAX_VALUE
            }
            send()
        }
    }

    @Synchronized
    private fun cancelScheduled() {
        job?.cancel()
        job = null
        jobAt = Long.MAX_VALUE
    }

    /** 送る（ロックの中）。結果を返す。 */
    private suspend fun send(): JoinResult = lock.withLock { sendLocked() }

    private suspend fun sendLocked(retriedSecret: Boolean = false): JoinResult {
        if (!store.enabled) return JoinResult.OK
        val id = store.playerId
        val secret = store.secret
        if (id == null || secret == null) return JoinResult.OK
        if (hold()) {
            // 記録中は送らない（終わって watch の Flow が流れたら送る）
            held = true
            markPending()
            return JoinResult.HELD
        }
        val v = runCatching { snapshot() }.getOrElse { return JoinResult.OFFLINE }
        val body = PutBody(store.name, RankingApi.PLATFORM, appVersion, v.month, v.values, v.monthValues)
        return when (val r = api().put(id, secret, body)) {
            is ApiResult.Ok -> {
                failures = 0
                store.pendingSend = false
                store.lastSentAt = now()
                store.lastSentSig = sig(v)
                store.players = r.value.players
                refresh()
                JoinResult.OK
            }
            is ApiResult.Refused -> when {
                r.status == 429 -> {
                    markPending()
                    schedule(RankingRules.retryAfterMs(r.retryAfter))
                    JoinResult.OFFLINE
                }
                r.status == 403 && r.error == "banned" -> {
                    onBanned()
                    JoinResult.BANNED
                }
                r.status == 403 && r.error == "bad-secret" && !retriedSecret -> {
                    // 同じ ID が別の secret で登録されている（ふつうは起きない）。新しい ID で登録し直す
                    store.newIdentity()
                    store.lastSentSig = ""
                    sendLocked(retriedSecret = true)
                }
                r.status == 400 && r.error == "bad-name" -> {
                    // 名前が使えない（NG 語など）。送り直しても同じなので印は消し、設定で変えてもらう
                    store.pendingSend = false
                    refresh()
                    JoinResult.BAD_NAME
                }
                // 404 = サーバがまだ置かれていない・道が違う（本番の設置前など）。5xx と同じく後で送り直す
                r.status == 404 || r.status in 500..599 -> retryLater()
                else -> {
                    // bad-value・bad-request など（送り直しても同じ）。次に値が変わったときにまた送る
                    store.pendingSend = false
                    refresh()
                    JoinResult.OFFLINE
                }
            }
            is ApiResult.Failed -> retryLater()
        }
    }

    private fun markPending() {
        store.pendingSend = true
        refresh()
    }

    private fun retryLater(): JoinResult {
        markPending()
        failures++
        RankingRules.retryDelayMs(failures)?.let { schedule(it) }
        return JoinResult.OFFLINE
    }

    private fun onBanned() {
        cancelScheduled()
        store.enabled = false
        store.clearIdentity()
        store.clearCache()
        store.notice = BANNED_TEXT
        refresh()
        _messages.tryEmit(BANNED_TEXT)
    }

    /**
     * 参加する（名前を決めて ON）。初めてなら ID と secret を作る。すぐ送ってみて、名前が使えなければ OFF に戻す。
     * つながらなければ ON のまま「未送信」（次の機会に送る）。
     */
    suspend fun join(rawName: String): JoinResult {
        val name = RankingRules.cleanName(rawName) ?: return JoinResult.BAD_NAME
        val r = lock.withLock {
            if (store.playerId == null || store.secret == null) store.newIdentity()
            store.name = name
            store.enabled = true
            store.notice = null
            store.pendingSend = true
            store.lastSentSig = ""
            refresh()
            sendLocked()
        }
        if (r == JoinResult.BAD_NAME) {
            lock.withLock {
                store.enabled = false
                store.clearIdentity()
                refresh()
            }
        }
        return r
    }

    /** 名前を変える。使えない名前なら元に戻す。つながらなければ新しい名前で「未送信」。 */
    suspend fun rename(rawName: String): JoinResult {
        val name = RankingRules.cleanName(rawName) ?: return JoinResult.BAD_NAME
        return lock.withLock {
            if (!store.enabled) return@withLock JoinResult.OK
            val old = store.name
            store.name = name
            store.pendingSend = true
            refresh()
            val r = sendLocked()
            if (r == JoinResult.BAD_NAME) {
                store.name = old
                // 元の名前の送信が済んでいたかは分からないので、次の機会に送り直す
                store.pendingSend = true
                refresh()
            }
            r
        }
    }

    /** 参加をやめる（OFF）。サーバの自分の行を消す。届かなければ印を残して次の通信の機会に。 */
    suspend fun leave() {
        lock.withLock {
            cancelScheduled()
            val id = store.playerId
            val secret = store.secret
            if (id != null && secret != null) store.pendingDeletes = store.pendingDeletes + PendingDelete(id, secret)
            store.enabled = false
            store.clearIdentity()
            store.clearCache()
            refresh()
        }
        flushDeletes()
    }

    /** 消し残しを消す（成功・404 で印を消す）。 */
    suspend fun flushDeletes() {
        lock.withLock {
            val list = store.pendingDeletes
            if (list.isEmpty()) return@withLock
            val left = list.filter { d ->
                when (val r = api().delete(d.playerId, d.secret)) {
                    is ApiResult.Ok -> false
                    is ApiResult.Refused -> r.status == 429 || r.status in 500..599
                    is ApiResult.Failed -> true
                }
            }
            store.pendingDeletes = left
            refresh()
        }
    }

    /** 1 つの板を読む。読めなければ最後に読んだ内容と、読めなかった理由。 */
    suspend fun loadBoard(board: String, period: String): BoardView {
        val id = store.playerId
        val secret = store.secret
        if (!store.enabled || id == null || secret == null) return BoardView(null, 0, "参加していません")
        val cache = store.cached(board, period)
        return when (val r = api().board(id, secret, board, period)) {
            is ApiResult.Ok -> {
                val at = now()
                store.putCache(board, period, r.value, at)
                store.players = r.value.players
                BoardView(r.value, at, null)
            }
            is ApiResult.Refused -> {
                if (r.status == 403 && r.error == "banned") {
                    onBanned()
                    return BoardView(null, 0, BANNED_TEXT)
                }
                val why = when {
                    r.status == 401 && store.pendingSend -> "まだ登録が済んでいません（送信待ち）。少し待ってから更新してください"
                    r.status == 401 -> "サーバに登録がありません。少し待ってから更新してください"
                    r.status == 429 -> "読み込みが多すぎます。少し待ってから更新してください"
                    else -> "つながりません（${r.status}）"
                }
                // 登録が無い（管理で消された・送信待ち）なら送り直しを予約
                if (r.status == 401) {
                    markPending()
                    schedule(OPEN_DELAY_MS)
                }
                BoardView(cache?.first, cache?.second ?: 0, why)
            }
            is ApiResult.Failed -> BoardView(cache?.first, cache?.second ?: 0, "つながりません")
        }
    }

    /** バックアップに入れる参加の情報（参加していなければ null）。 */
    fun backup(): RankingBackup? {
        if (!store.enabled) return null
        val id = store.playerId ?: return null
        val secret = store.secret ?: return null
        return RankingBackup(id, secret, store.name)
    }

    /**
     * バックアップから参加を引き継ぐ（同じ参加者として続く）。バックアップに無ければ今の設定のまま。
     * いま別の ID で参加していれば、その ID はサーバから消す。
     */
    suspend fun restore(b: RankingBackup?): Boolean {
        if (b == null) return false
        val name = RankingRules.cleanName(b.name) ?: return false
        if (b.playerId.isBlank() || b.secret.isBlank()) return false
        lock.withLock {
            val curId = store.playerId
            val curSecret = store.secret
            if (curId != null && curSecret != null && curId != b.playerId) {
                store.pendingDeletes = store.pendingDeletes + PendingDelete(curId, curSecret)
            }
            store.clearCache()
            store.playerId = b.playerId
            store.secret = b.secret
            store.name = name
            store.enabled = true
            store.notice = null
            store.pendingSend = true
            store.lastSentSig = ""
            refresh()
        }
        flushDeletes()
        schedule(OPEN_DELAY_MS)
        return true
    }

    /** 知らせを消す。 */
    fun dismissNotice() {
        store.notice = null
        refresh()
    }

    /** debug ビルドだけ: 接続先を変える（空なら本番）。 */
    fun setDebugBaseUrl(url: String) {
        if (!debug) return
        store.debugBaseUrl = url.trim().ifEmpty { null }
        store.clearCache()
        refresh()
    }

    /** debug ビルドだけ: すぐ送る。 */
    fun sendNowForDebug() {
        if (!debug) return
        schedule(0)
    }

    companion object {
        const val OPEN_DELAY_MS = 3_000L
        const val BANNED_TEXT = "この端末はランキングに参加できなくなりました"
    }
}
