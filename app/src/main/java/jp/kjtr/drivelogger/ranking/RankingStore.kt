package jp.kjtr.drivelogger.ranking

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom
import android.util.Base64
import java.util.UUID

/** サーバから消してもらう参加（OFF にしたが DELETE が届いていない分）。 */
@Serializable
data class PendingDelete(val playerId: String, val secret: String)

/**
 * ランキングの参加の情報（アプリ専用の SharedPreferences "ranking"。端末のクラウドバックアップには入れない）。
 * playerId・secret はバックアップの書き出しに入る（API.md §2）。
 */
class RankingStore(context: Context) {
    private val sp = context.getSharedPreferences("ranking", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var playerId: String?
        get() = sp.getString("player_id", null)
        set(v) = sp.edit().putString("player_id", v).apply()

    var secret: String?
        get() = sp.getString("secret", null)
        set(v) = sp.edit().putString("secret", v).apply()

    var name: String
        get() = sp.getString("name", "").orEmpty()
        set(v) = sp.edit().putString("name", v).apply()

    /** 未送信の印（値が変わったが、まだ送れていない）。 */
    var pendingSend: Boolean
        get() = sp.getBoolean("pending_send", false)
        set(v) = sp.edit().putBoolean("pending_send", v).apply()

    var lastSentAt: Long
        get() = sp.getLong("last_sent_at", 0L)
        set(v) = sp.edit().putLong("last_sent_at", v).apply()

    /** 最後に送れた内容（値 + 名前）。 */
    var lastSentSig: String
        get() = sp.getString("last_sent_sig", "").orEmpty()
        set(v) = sp.edit().putString("last_sent_sig", v).apply()

    /** 最後に分かった参加者の数。 */
    var players: Int
        get() = sp.getInt("players", 0)
        set(v) = sp.edit().putInt("players", v).apply()

    /** 設定に出す知らせ（「参加できなくなりました」など）。 */
    var notice: String?
        get() = sp.getString("notice", null)
        set(v) = sp.edit().putString("notice", v).apply()

    /** debug ビルドだけ使う接続先（release は読まない）。 */
    var debugBaseUrl: String?
        get() = sp.getString("debug_base_url", null)
        set(v) = sp.edit().putString("debug_base_url", v).apply()

    var pendingDeletes: List<PendingDelete>
        get() = runCatching {
            RankingApi.JSON.decodeFromString(ListSerializer(PendingDelete.serializer()), sp.getString("pending_deletes", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("pending_deletes", RankingApi.JSON.encodeToString(ListSerializer(PendingDelete.serializer()), v)).apply()

    /** 最後に読んだ板（つながらないときに「○ 分前」と出す）。 */
    fun cached(board: String, period: String): Pair<BoardReply, Long>? {
        val text = sp.getString("cache_${board}_$period", null) ?: return null
        val at = sp.getLong("cache_at_${board}_$period", 0L)
        return runCatching { RankingApi.decodeBoard(text) }.getOrNull()?.let { it to at }
    }

    fun putCache(board: String, period: String, reply: BoardReply, at: Long) {
        sp.edit().putString("cache_${board}_$period", RankingApi.encodeBoard(reply)).putLong("cache_at_${board}_$period", at).apply()
    }

    fun clearCache() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("cache_")) e.remove(k)
        e.apply()
    }

    /** 新しい参加者として ID と secret を作る（UUID v4・32 バイトの乱数を base64url）。 */
    fun newIdentity() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        playerId = UUID.randomUUID().toString()
        secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun clearIdentity() {
        sp.edit().remove("player_id").remove("secret").remove("name").remove("last_sent_sig").remove("last_sent_at")
            .remove("pending_send").remove("players").apply()
    }
}
