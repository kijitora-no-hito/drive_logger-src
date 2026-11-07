package jp.kjtr.drivelogger.ranking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ランキングサーバとのやりとり（docs: ranking_server/docs/API.md §3）。送るのは名前・ランダムな ID・数値・版・端末の種類だけ。

@Serializable
data class PutBody(
    val name: String,
    val platform: String,
    val appVersion: String,
    val month: String,
    val values: Map<String, Long>,
    val monthValues: Map<String, Long>,
)

@Serializable
data class PutReply(val ok: Boolean = false, val players: Int = 0, val unlocked: Boolean = false)

@Serializable
data class BoardMe(val rank: Int, val value: Long, val name: String = "")

@Serializable
data class BoardRow(val rank: Int, val name: String, val value: Long, val me: Boolean = false)

@Serializable
data class BoardReply(
    val board: String,
    val period: String,
    val month: String = "",
    val players: Int = 0,
    val ranked: Int = 0,
    val minPlayers: Int = RankingRules.MIN_PLAYERS,
    val unlocked: Boolean = false,
    val me: BoardMe? = null,
    val top: List<BoardRow> = emptyList(),
)

@Serializable
private data class ErrorReply(val error: String = "", val retryAfter: Int? = null)

/** 通信の結果。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** サーバが断った（[error] は本文の error。429 なら [retryAfter] 秒）。 */
    data class Refused(val status: Int, val error: String, val retryAfter: Int?) : ApiResult<Nothing>

    /** つながらない・応答が読めない。 */
    data class Failed(val message: String) : ApiResult<Nothing>
}

class RankingApi(private val baseUrl: String, private val appId: String, private val userAgent: String) {
    private class Http(val code: Int, val body: String)

    private fun call(method: String, path: String, headers: Map<String, String>, body: String?): Http {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Http(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun <T> run(method: String, path: String, headers: Map<String, String>, body: String?, parse: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val r = call(method, path, headers, body)
                if (r.code in 200..299) ApiResult.Ok(parse(r.body))
                else {
                    val e = runCatching { JSON.decodeFromString(ErrorReply.serializer(), r.body) }.getOrNull()
                    ApiResult.Refused(r.code, e?.error.orEmpty(), e?.retryAfter)
                }
            } catch (e: IOException) {
                ApiResult.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                // URL の形が違う（debug の接続先）・応答の JSON が読めない
                ApiResult.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: kotlinx.serialization.SerializationException) {
                ApiResult.Failed("応答を読めません")
            }
        }

    private fun auth(secret: String) = mapOf("Authorization" to "Bearer $secret")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun put(playerId: String, secret: String, body: PutBody): ApiResult<PutReply> =
        run("PUT", "/${enc(appId)}/players/${enc(playerId)}", auth(secret), JSON.encodeToString(PutBody.serializer(), body)) {
            JSON.decodeFromString(PutReply.serializer(), it)
        }

    suspend fun board(playerId: String, secret: String, board: String, period: String): ApiResult<BoardReply> =
        run("GET", "/${enc(appId)}/boards/${enc(board)}?period=${enc(period)}", auth(secret) + ("X-Player-Id" to playerId), null) {
            decodeBoard(it)
        }

    suspend fun delete(playerId: String, secret: String): ApiResult<Unit> =
        run("DELETE", "/${enc(appId)}/players/${enc(playerId)}", auth(secret), null) { }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun decodeBoard(text: String): BoardReply = JSON.decodeFromString(BoardReply.serializer(), text)
        fun encodeBoard(b: BoardReply): String = JSON.encodeToString(BoardReply.serializer(), b)

        const val PLATFORM = "android"
    }
}
