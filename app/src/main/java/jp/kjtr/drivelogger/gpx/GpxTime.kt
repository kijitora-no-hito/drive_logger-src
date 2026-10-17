package jp.kjtr.drivelogger.gpx

/**
 * GPX の時刻（xsd:dateTime、ISO 8601）の読み書き。minSdk 24 では java.time が使えないので自前で計算する。
 * 読み: `2024-05-01T03:04:05Z` / 小数秒 / `+09:00` `+0900` / タイムゾーン無し（UTC とみなす）。
 * 書き: UTC の `Z` 形式。ミリ秒が 0 でなければ `.SSS` を付ける（読み戻すと同じ値になる）。
 */
object GpxTime {

    fun parse(text: String?): Long? {
        val s = text?.trim() ?: return null
        if (s.length < 19) return null
        return try {
            val year = s.substring(0, 4).toInt()
            if (s[4] != '-' || s[7] != '-' || (s[10] != 'T' && s[10] != 't' && s[10] != ' ') || s[13] != ':' || s[16] != ':') return null
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()
            val hour = s.substring(11, 13).toInt()
            val minute = s.substring(14, 16).toInt()
            val second = s.substring(17, 19).toInt()
            if (month !in 1..12 || day !in 1..31 || hour !in 0..24 || minute !in 0..59 || second !in 0..60) return null
            var i = 19
            var millis = 0
            if (i < s.length && (s[i] == '.' || s[i] == ',')) {
                i++
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                val frac = s.substring(start, i)
                if (frac.isEmpty()) return null
                millis = (frac + "00").substring(0, 3).toInt()
            }
            var offsetMin = 0
            if (i < s.length) {
                when (s[i]) {
                    'Z', 'z' -> i++
                    '+', '-' -> {
                        val sign = if (s[i] == '-') -1 else 1
                        val rest = s.substring(i + 1).replace(":", "")
                        if (rest.length < 2) return null
                        val oh = rest.substring(0, 2).toInt()
                        val om = if (rest.length >= 4) rest.substring(2, 4).toInt() else 0
                        offsetMin = sign * (oh * 60 + om)
                        i = s.length
                    }
                    else -> return null
                }
            }
            val days = daysFromCivil(year, month, day)
            val secs = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMin * 60L
            secs * 1000L + millis
        } catch (_: NumberFormatException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun format(epochMs: Long): String {
        val ms = Math.floorMod(epochMs, 1000L).toInt()
        val totalSec = Math.floorDiv(epochMs, 1000L)
        val days = Math.floorDiv(totalSec, 86_400L)
        val sod = Math.floorMod(totalSec, 86_400L).toInt()
        val (y, m, d) = civilFromDays(days)
        val sb = StringBuilder(24)
        sb.append(pad(y, 4)).append('-').append(pad(m, 2)).append('-').append(pad(d, 2))
        sb.append('T').append(pad(sod / 3600, 2)).append(':').append(pad(sod / 60 % 60, 2)).append(':').append(pad(sod % 60, 2))
        if (ms != 0) sb.append('.').append(pad(ms, 3))
        sb.append('Z')
        return sb.toString()
    }

    private fun pad(v: Int, n: Int): String = v.toString().padStart(n, '0')

    /** 1970-01-01 からの日数（Howard Hinnant の days_from_civil）。 */
    internal fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
