package jp.kjtr.drivelogger.stats

import jp.kjtr.drivelogger.geo.Geo
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** まとめの期間。 */
enum class SummaryPeriod { ALL, THIS_YEAR, THIS_MONTH, LAST_30_DAYS, CUSTOM }

/** 期間 [fromMs, toMs)（null = 区切りなし）。 */
data class TimeRange(val fromMs: Long?, val toMs: Long?) {
    operator fun contains(t: Long): Boolean = (fromMs == null || t >= fromMs) && (toMs == null || t < toMs)
}

/** まとめに使うトリップ 1 本分（トリップの行と、点から計算したもの）。 */
data class TripFacts(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double,
    /** 獲得標高 [m]（点の標高から。無ければ 0）。 */
    val ascentM: Double = 0.0,
    /** 録画していた時間 [ms]。 */
    val videoMs: Long = 0,
    /** 保護した出来事（衝撃・ボタン）の数。 */
    val protects: Int = 0,
    /** 速度の分布（10 km/h ごとの bin 番号 → 時間 [ms]）。 */
    val speedBinsMs: Map<Int, Long> = emptyMap(),
    /** 走った 1 km のマス目（[GridCells.cellOf]）。 */
    val cells: Set<Long> = emptySet(),
    /** 駐車・寄り道の回数と時間の合計 [ms]（0.4.0〜。走行時間には入らない）。 */
    val parks: Int = 0,
    val parkMs: Long = 0,
    /** 写真の数（まとめの画面が写真の表から数えて入れる）。 */
    val photos: Int = 0,
    /** GPS ログの時間帯（0.6.0〜。曜日・時間帯はここで数える。空なら開始〜終了）。 */
    val logSpans: List<Pair<Long, Long>> = emptyList(),
)

/** まとめの結果。 */
data class DriveSummary(
    val tripCount: Int,
    val distanceM: Double,
    val movingMs: Long,
    val avgSpeedMps: Double,
    /** 最高速度とそのトリップ。 */
    val maxSpeed: Pair<Double, TripFacts>?,
    /** いちばん長い距離のトリップ。 */
    val longestDistance: TripFacts?,
    /** いちばん長い時間のトリップ（経過時間）。 */
    val longestTime: TripFacts?,
    val ascentM: Double,
    /** 走った日数（端末のタイムゾーンの日付で数える）。 */
    val days: Int,
    val videoMs: Long,
    val protects: Int,
    /** 月ごとの距離（"2026-10" → m。古い順）。 */
    val monthlyM: List<Pair<String, Double>>,
    /** 曜日ごとの走行時間 [ms]（0 = 日曜 … 6 = 土曜）。 */
    val weekdayMs: LongArray,
    /** 時間帯ごとの走行時間 [ms]（0〜23 時）。 */
    val hourMs: LongArray,
    /** 速度の分布（10 km/h ごと、0 から最高の bin まで）[ms]。 */
    val speedBinsMs: List<Long>,
    /** 開拓したマス（1 km 四方）の数。 */
    val cells: Int,
    val parks: Int = 0,
    val parkMs: Long = 0,
    val photos: Int = 0,
)

/**
 * 全記録のまとめ（純ロジック。単体テスト: DriveSummaryTest）。
 * 期間はトリップの開始時刻で選ぶ。曜日・時間帯はトリップの開始〜終了をその時刻の枠に割り振る（端末のタイムゾーン）。
 */
object DriveSummaries {

    /** [period] の範囲（[now] 基準。CUSTOM は [custom]）。 */
    fun range(period: SummaryPeriod, now: Long, zone: TimeZone = TimeZone.getDefault(), custom: TimeRange? = null): TimeRange {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = now
        fun startOfDay(c: Calendar) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        }
        return when (period) {
            SummaryPeriod.ALL -> TimeRange(null, null)
            SummaryPeriod.THIS_YEAR -> {
                startOfDay(cal); cal.set(Calendar.DAY_OF_YEAR, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                TimeRange(from, cal.timeInMillis)
            }
            SummaryPeriod.THIS_MONTH -> {
                startOfDay(cal); cal.set(Calendar.DAY_OF_MONTH, 1)
                val from = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                TimeRange(from, cal.timeInMillis)
            }
            SummaryPeriod.LAST_30_DAYS -> TimeRange(now - 30L * 86_400_000L, null)
            SummaryPeriod.CUSTOM -> custom ?: TimeRange(null, null)
        }
    }

    fun inRange(trips: List<TripFacts>, r: TimeRange): List<TripFacts> = trips.filter { it.startedAt in r }

    fun summarize(trips: List<TripFacts>, zone: TimeZone = TimeZone.getDefault()): DriveSummary {
        val distance = trips.sumOf { it.distanceM }
        val moving = trips.sumOf { it.movingMs }
        // 同じ値なら新しい方（開始が後）を選ぶ
        val newestFirst = trips.sortedByDescending { it.startedAt }
        val fastest = newestFirst.maxByOrNull { it.maxSpeedMps }?.takeIf { it.maxSpeedMps > 0 }
        val longestD = newestFirst.maxByOrNull { it.distanceM }?.takeIf { it.distanceM > 0 }
        val longestT = newestFirst.maxByOrNull { it.endedAt - it.startedAt }?.takeIf { it.endedAt > it.startedAt }
        val cal = Calendar.getInstance(zone)
        val days = HashSet<Long>()
        val monthly = java.util.TreeMap<String, Double>()
        val weekday = LongArray(7)
        val hour = LongArray(24)
        for (t in trips) {
            cal.timeInMillis = t.startedAt
            days.add(cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR))
            val key = String.format(java.util.Locale.ROOT, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            monthly[key] = (monthly[key] ?: 0.0) + t.distanceM
            if (t.logSpans.isEmpty()) spread(t.startedAt, t.endedAt, zone, weekday, hour, days)
            else for ((a, b) in t.logSpans) spread(a, b, zone, weekday, hour, days)
        }
        val bins = HashMap<Int, Long>()
        for (t in trips) for ((k, v) in t.speedBinsMs) bins[k] = (bins[k] ?: 0L) + v
        val top = bins.keys.maxOrNull() ?: -1
        return DriveSummary(
            tripCount = trips.size,
            distanceM = distance,
            movingMs = moving,
            avgSpeedMps = if (moving > 0) distance / (moving / 1000.0) else 0.0,
            maxSpeed = fastest?.let { it.maxSpeedMps to it },
            longestDistance = longestD,
            longestTime = longestT,
            ascentM = trips.sumOf { it.ascentM },
            days = days.size,
            videoMs = trips.sumOf { it.videoMs },
            protects = trips.sumOf { it.protects },
            monthlyM = monthly.entries.map { it.key to it.value },
            weekdayMs = weekday,
            hourMs = hour,
            speedBinsMs = (0..top).map { bins[it] ?: 0L },
            cells = trips.flatMapTo(HashSet()) { it.cells }.size,
            parks = trips.sumOf { it.parks },
            parkMs = trips.sumOf { it.parkMs },
            photos = trips.sumOf { it.photos },
        )
    }

    /** [from]〜[to] を時間の枠（1 時間ごと）に割り振って曜日・時間帯に足す。日をまたいだら走った日も足す。 */
    private fun spread(from: Long, to: Long, zone: TimeZone, weekday: LongArray, hour: LongArray, days: MutableSet<Long>) {
        if (to <= from) return
        val cal = Calendar.getInstance(zone)
        var t = from
        var guard = 0
        while (t < to && guard++ < 24 * 31) {
            cal.timeInMillis = t
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val end = min(cal.timeInMillis, to)
            cal.timeInMillis = t
            val ms = end - t
            weekday[cal.get(Calendar.DAY_OF_WEEK) - 1] += ms
            hour[cal.get(Calendar.HOUR_OF_DAY)] += ms
            days.add(cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR))
            t = end
        }
    }
}

/**
 * 1 km 四方のマス目（「開拓したマス」）。緯度は 1 km ごとの帯、経度はその帯の中央の緯度で 1 km ごと。
 * マスの番号は (行, 列) を 1 つの Long にまとめたもの。
 */
object GridCells {
    private const val KM = 1000.0

    fun cellOf(lat: Double, lon: Double): Long {
        val row = floor(lat * Geo.METERS_PER_DEG_LAT / KM).toLong()
        val centerLat = (row + 0.5) * KM / Geo.METERS_PER_DEG_LAT
        val mPerLon = max(1.0, Geo.EARTH_RADIUS_M * Math.PI / 180.0 * cos(Math.toRadians(centerLat)))
        val col = floor(lon * mPerLon / KM).toLong()
        return (row shl 32) xor (col and 0xffffffffL)
    }

    /** 点の列が通ったマス（点と点の間が 1 km より離れていれば、間も 500 m おきに補って数える）。 */
    fun cellsOf(points: List<Pair<Double, Double>>): Set<Long> {
        val out = HashSet<Long>()
        var prev: Pair<Double, Double>? = null
        for (p in points) {
            val q = prev
            if (q != null) {
                val d = Geo.distanceM(q.first, q.second, p.first, p.second)
                if (d > 1000.0 && d < 50_000.0) {
                    val n = (d / 500.0).toInt()
                    for (i in 1 until n) {
                        val f = i.toDouble() / n
                        out.add(cellOf(q.first + (p.first - q.first) * f, q.second + (p.second - q.second) * f))
                    }
                }
            }
            out.add(cellOf(p.first, p.second))
            prev = p
        }
        return out
    }
}
