package jp.kjtr.drivelogger.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 数値の表示形式（単位は km 固定）。 */
object Format {
    /** 経過時間 h:mm:ss（1 時間未満は m:ss）。 */
    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = totalSec / 60 % 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** 「8分32秒」「1時間02分」（速度の分布のタップ表示）。 */
    fun durationJa(ms: Long): String {
        val totalSec = ((ms.coerceAtLeast(0) + 500) / 1000)
        val h = totalSec / 3600
        val m = totalSec / 60 % 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%d時間%02d分", h, m)
            m > 0 -> String.format(Locale.ROOT, "%d分%02d秒", m, s)
            else -> "${s}秒"
        }
    }

    /** 区間の端の km（整数ならそのまま、端数は小数 2 桁）。 */
    fun kmEdge(m: Double): String {
        val km = m / 1000.0
        val r = km.roundToInt()
        return if (kotlin.math.abs(km - r) < 0.0005) r.toString() else String.format(Locale.ROOT, "%.2f", km)
    }

    /** km（小数 2 桁）。 */
    fun km(m: Double): String = String.format(Locale.ROOT, "%.2f km", m / 1000.0)

    /** km の数字だけ（小数 2 桁）。 */
    fun kmNumber(m: Double): String = String.format(Locale.ROOT, "%.2f", m / 1000.0)

    /** 1 km 未満は m、それ以上は km（小数 1 桁）。 */
    fun distance(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000.0)

    /** km/h の数字（小数 1 桁）。 */
    fun speed(mps: Double): String = String.format(Locale.ROOT, "%.1f", mps * 3.6)

    fun meters(m: Double): String = "${m.roundToInt()} m"

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy/MM/dd (E) HH:mm", Locale.JAPAN).format(Date(ms))

    /** M/d HH:mm（更新の確認の状態など）。 */
    fun shortDateTime(ms: Long): String = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(ms))

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(ms))

    /** 終わりの時刻（[start] と同じ日なら HH:mm、違う日なら M/d HH:mm。再開して日をまたいだドライブ）。 */
    fun dateTimeShort(start: Long, end: Long): String =
        if (dayStamp(start) == dayStamp(end)) time(end) else shortDateTime(end)

    fun dateForName(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date(ms))

    fun dayStamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))

    fun fileStamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(ms))

    /** HH:mm:ss（走行画面の時刻・動画の開始時刻）。 */
    fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(ms))

    /** 動画の一覧の日付の見出し「2026/10/13 (火)」。 */
    fun dayHeader(ms: Long): String = SimpleDateFormat("yyyy/MM/dd (E)", Locale.JAPAN).format(Date(ms))

    /** 容量（1 GB 以上は GB 小数 1 桁、それ未満は MB）。 */
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.ROOT, "%d MB", b / (1024 * 1024))
        else -> String.format(Locale.ROOT, "%d KB", (b / 1024).coerceAtLeast(0))
    }

    /** おおよその時間（長時間録画の残り。1 時間以上は「3.1 時間」、それ未満は「25 分」）。 */
    fun approxHours(h: Double): String =
        if (h >= 1.0) String.format(Locale.ROOT, "%.1f 時間", h) else "${(h * 60).toInt().coerceAtLeast(0)} 分"

    /** MB の設定値の表示（1024 MB 以上は GB）。 */
    fun megabytes(mb: Int): String = if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

    /** ファイル名に使えない文字を置き換える。 */
    fun safeFileName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "export" }.take(80)
}
