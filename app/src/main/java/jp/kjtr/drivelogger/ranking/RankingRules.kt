package jp.kjtr.drivelogger.ranking

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

// オンラインランキングの決まりごと（純粋な関数。単体テスト RankingTest）。
// API の正本は d:\work\android\ranking_server\docs\API.md。このファイルは基準点めぐり・神社めぐりの写しで、自転車ルートロガー・ドライブロガーで同じ形
// （パッケージだけ違う。基準点めぐり・神社めぐりとの違いは RankBoard の表示の割り算だけ）。

/**
 * 板 1 つ（id はサーバの板 id。表示名・単位は画面用）。
 * 送る値が m で画面は km のように単位を変えて出すときは [divisor]（1000 なら km）と小数の桁 [decimals]。
 */
data class RankBoard(val id: String, val label: String, val unit: String, val divisor: Long = 1, val decimals: Int = 0) {
    /** 画面に出す値（"1,234 マス" / "12.3 km"）。 */
    fun format(v: Long): String =
        if (divisor <= 1) "%,d %s".format(v, unit)
        else "%,.${decimals}f %s".format(v.toDouble() / divisor, unit)
}

/** 送る値（毎回ぜんぶ。差分にしない）。[month] は日本時間の年月 "YYYY-MM"。 */
data class RankValues(
    val month: String,
    val values: Map<String, Long>,
    val monthValues: Map<String, Long>,
) {
    /** 前回送った内容と同じかを比べる文字列（名前は別に比べる）。 */
    fun signature(): String =
        month + "|" + values.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } +
            "|" + monthValues.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
}

/** バックアップ（書き出し・読み込み）に入れる参加の情報（API.md §2）。参加していなければ入れない。 */
@Serializable
data class RankingBackup(
    val playerId: String,
    val secret: String,
    val name: String,
)

object RankingRules {
    /** 日本時間（月の区切り）。 */
    val JST: ZoneId = ZoneId.of("Asia/Tokyo")

    const val NAME_MAX = 12

    /** 上位の一覧が見られる参加者の数（サーバの MIN_PLAYERS の既定。画面の文言用。判定はサーバの unlocked）。 */
    const val MIN_PLAYERS = 10

    /** 連続した出来事をまとめる時間。 */
    const val BATCH_MS = 30_000L

    /** アプリを開いたとき、前回の送信からこれ以上たっていれば送る。 */
    const val RESEND_ON_OPEN_MS = 60 * 60 * 1000L

    /** 失敗したとき、アプリを開いている間に送り直す間隔（これを使い切ったら、次にアプリを開いたとき）。 */
    private val RETRY_DELAYS_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)

    /** 日本時間の年月 "YYYY-MM"。 */
    fun monthOf(epochMs: Long): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(JST)
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    /**
     * 名前をサーバと同じ形に整える（前後の空白を落として NFC、1〜12 文字（コードポイント）、制御文字・ゼロ幅・書式の文字なし）。
     * 使えなければ null。NG 語はサーバだけが調べる（bad-name が返る）。
     */
    fun cleanName(raw: String): String? {
        val s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val n = s.codePointCount(0, s.length)
        if (n < 1 || n > NAME_MAX) return null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isForbidden(cp)) return null
            i += Character.charCount(cp)
        }
        return s
    }

    /** 名前の文字数（コードポイント。入力欄の「n / 12」用）。 */
    fun nameLength(raw: String): Int {
        val s = raw.trim()
        return s.codePointCount(0, s.length)
    }

    /** 制御文字・ゼロ幅・行区切り・書式の文字（サーバの src/text.ts の CONTROL_RE と同じ範囲）。 */
    private fun isForbidden(cp: Int): Boolean =
        cp in 0x00..0x1F || cp in 0x7F..0x9F || cp in 0x200B..0x200F || cp in 0x2028..0x202E ||
            cp in 0x2060..0x2064 || cp == 0xFEFF

    /** アプリを開いたときに送るか（送信待ち・値が前回と違う・前回から 1 時間以上）。 */
    fun shouldSendOnOpen(pending: Boolean, sameAsLast: Boolean, lastSentAt: Long, now: Long): Boolean =
        pending || !sameAsLast || lastSentAt <= 0 || now - lastSentAt >= RESEND_ON_OPEN_MS

    /** [failures] 回目の失敗のあと、アプリを開いている間に送り直すまでの時間。null = 次にアプリを開いたとき。 */
    fun retryDelayMs(failures: Int): Long? = RETRY_DELAYS_MS.getOrNull(failures - 1)

    /** 429 のあと送り直すまで（retryAfter 秒 + 少し）。 */
    fun retryAfterMs(retryAfterSec: Int?): Long = ((retryAfterSec ?: 20).coerceIn(1, 3600) * 1000L) + 1000L

    /** 「○ 分前」。 */
    fun agoText(then: Long, now: Long): String {
        val min = ((now - then) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "1 分以内"
            min < 60 -> "$min 分前"
            min < 60 * 24 -> "${min / 60} 時間前"
            else -> "${min / (60 * 24)} 日前"
        }
    }

    /**
     * 自分の順位の文言。参加者が 10 人になるまで（[unlocked] が false）は人数を出さない（「あなたは 3 位」）。
     * 10 人以上なら「あなたは 3 位（12 人中）」（人数は [ranked]＝その板・期間に値がある人）。
     */
    fun myRankText(rank: Int, ranked: Int, unlocked: Boolean): String =
        if (unlocked) "あなたは $rank 位（$ranked 人中）" else "あなたは $rank 位"

    /** 参加者が 10 人未満のときの案内（いまの人数は出さない）。 */
    fun lockedText(minPlayers: Int): String = "参加者が $minPlayers 人になると、上位の一覧が見られます"

    /** 月の表示（"2026-11" → "2026年11月"）。 */
    fun monthLabel(month: String): String {
        val p = month.split('-')
        return if (p.size == 2) "${p[0]}年${p[1].toIntOrNull() ?: p[1]}月" else month
    }
}
