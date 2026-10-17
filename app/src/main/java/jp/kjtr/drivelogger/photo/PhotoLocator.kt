package jp.kjtr.drivelogger.photo

import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.geo.LatLon
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 写真の位置とトリップへの紐づけ（純ロジック。自転車ルートロガーの PhotoLocator と同じ）。 */
object PhotoLocator {
    /** 軌跡の端からこれ以上離れた時刻は補間しない。 */
    const val MAX_EDGE_MS = 5 * 60_000L
    /** 前後の点の間がこれ以上空いていたら（電源 OFF など）補間しない（近い方が [MAX_EDGE_MS] 以内ならその点）。 */
    const val MAX_GAP_MS = 10 * 60_000L
    /** トリップの前後これだけは「このトリップの写真」とみなす（出発前・到着後の写真）。 */
    const val TRIP_MARGIN_MS = 10 * 60_000L

    /** 撮った時刻 [timeMs] の位置を、軌跡の点（時刻順）から線形補間で求める。分からなければ null。 */
    fun interpolate(points: List<TripPointEntity>, timeMs: Long): LatLon? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (timeMs <= first.time) return if (first.time - timeMs <= MAX_EDGE_MS) LatLon(first.lat, first.lon) else null
        if (timeMs >= last.time) return if (timeMs - last.time <= MAX_EDGE_MS) LatLon(last.lat, last.lon) else null
        // 二分探索で前後の点
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].time <= timeMs) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val gap = b.time - a.time
        if (gap <= 0) return LatLon(a.lat, a.lon)
        if (gap > MAX_GAP_MS) {
            val da = timeMs - a.time
            val db = b.time - timeMs
            return when {
                da <= db && da <= MAX_EDGE_MS -> LatLon(a.lat, a.lon)
                db < da && db <= MAX_EDGE_MS -> LatLon(b.lat, b.lon)
                else -> null
            }
        }
        val f = (timeMs - a.time).toDouble() / gap
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** 時刻 [takenAt] が入る駐車の区間（終わっていない駐車は今まで）。無ければ null。 */
    fun parkAt(parks: List<ParkEntity>, takenAt: Long): ParkEntity? =
        parks.firstOrNull { takenAt >= it.startedAt && takenAt <= (it.endedAt ?: Long.MAX_VALUE) }

    /** トリップ [t] の時間帯（前後 [TRIP_MARGIN_MS] を含む）に撮った写真か。 */
    fun belongsTo(t: TripEntity, takenAt: Long): Boolean {
        // 記録中（終わりが無い）は終わりの制限なし（Long.MAX_VALUE に余裕を足すとあふれるので分ける）
        val end = t.endedAt ?: return takenAt >= t.startedAt - TRIP_MARGIN_MS
        return takenAt >= t.startedAt - TRIP_MARGIN_MS && takenAt <= end + TRIP_MARGIN_MS
    }
}

/** EXIF の日時（"yyyy:MM:dd HH:mm:ss" と OffsetTime "+09:00"）。 */
object ExifTime {
    private fun fmt(tz: TimeZone) = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply { timeZone = tz; isLenient = false }

    /**
     * EXIF の日時を UTC ms に。[offset]（OffsetTimeOriginal など）があればそれ、無ければ [defaultZone]（端末のタイムゾーン）で読む。
     * [subSec]（SubSecTimeOriginal）があればミリ秒を足す。読めなければ null。
     */
    fun parse(dateTime: String?, offset: String? = null, subSec: String? = null, defaultZone: TimeZone = TimeZone.getDefault()): Long? {
        val s = dateTime?.trim()?.takeIf { it.length >= 19 } ?: return null
        val tz = offset?.trim()?.let { parseOffset(it) } ?: defaultZone
        val d = fmt(tz).parse(s.substring(0, 19), ParsePosition(0)) ?: return null
        val ms = subSec?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { (("0.$it").toDouble() * 1000).toLong() } ?: 0L
        return d.time + ms
    }

    fun format(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String = fmt(zone).format(java.util.Date(timeMs))

    /** その時刻のオフセット "+09:00" / "-05:30"。 */
    fun formatOffset(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val off = zone.getOffset(timeMs) / 60_000
        val sign = if (off < 0) "-" else "+"
        val a = kotlin.math.abs(off)
        return "%s%02d:%02d".format(Locale.ROOT, sign, a / 60, a % 60)
    }

    private fun parseOffset(s: String): TimeZone? {
        val m = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(s) ?: return null
        return TimeZone.getTimeZone("GMT${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}")
    }
}

/**
 * EXIF の GPS の値（度分秒の有理数 "35/1,40/1,5244/100" と N/S・E/W）。ExifInterface に文字列で書くために使う
 * （ExifInterface 自体は Android のクラスに頼るので単体テストでは動かない。書式だけをここでテストする）。
 */
object ExifGps {
    /** 度 → "d/1,m/1,s×10000/10000"（秒は 1/10000 秒まで）。 */
    fun toRational(deg: Double): String {
        val a = kotlin.math.abs(deg)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = Math.round((mFull - m) * 60 * 10_000)
        return "$d/1,$m/1,$s/10000"
    }

    /** "d/1,m/1,s/den" → 度（符号なし）。読めなければ null。 */
    fun fromRational(s: String?): Double? {
        val parts = s?.split(',')?.takeIf { it.size == 3 } ?: return null
        val v = parts.map { p ->
            val (n, dd) = p.trim().split('/').let { if (it.size == 2) it[0] to it[1] else return null }
            val den = dd.toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            (n.toDoubleOrNull() ?: return null) / den
        }
        return v[0] + v[1] / 60 + v[2] / 3600
    }

    fun latRef(lat: Double) = if (lat < 0) "S" else "N"
    fun lonRef(lon: Double) = if (lon < 0) "W" else "E"

    /** EXIF の文字列 4 つから緯度経度。 */
    fun toLatLon(lat: String?, latRef: String?, lon: String?, lonRef: String?): Pair<Double, Double>? {
        val la = fromRational(lat) ?: return null
        val lo = fromRational(lon) ?: return null
        val sLa = if (latRef.equals("S", true)) -la else la
        val sLo = if (lonRef.equals("W", true)) -lo else lo
        if (sLa !in -90.0..90.0 || sLo !in -180.0..180.0 || (sLa == 0.0 && sLo == 0.0)) return null
        return sLa to sLo
    }
}
