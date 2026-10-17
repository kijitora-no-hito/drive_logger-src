package jp.kjtr.drivelogger.data

import jp.kjtr.drivelogger.stats.ElevationGain
import jp.kjtr.drivelogger.geo.LatLon
import jp.kjtr.drivelogger.geo.Simplify
import jp.kjtr.drivelogger.playback.RecordedSpans
import jp.kjtr.drivelogger.playback.TimeSpan
import jp.kjtr.drivelogger.stats.DriveAnalysis
import jp.kjtr.drivelogger.stats.GridCells
import jp.kjtr.drivelogger.stats.TripFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** まとめの地図に描くトリップ 1 本の線（ズームに応じて [coarse] / [fine] を使い分ける）。 */
data class TripLines(val tripId: Long, val coarse: List<List<LatLon>>, val fine: List<List<LatLon>>)

/** トリップ 1 本分のまとめの材料。 */
data class TripDigest(val facts: TripFacts, val lines: TripLines)

/**
 * 全記録のまとめの材料（DESIGN.md §4.1）。トリップごとに点を読み、統計の材料と表示用に間引いた線を作ってメモリに覚える
 * （トリップの点の数が変わらなければ作り直さない）。重い計算は IO スレッドで。
 */
class SummaryRepository(private val db: AppDatabase) {
    private val cache = HashMap<Long, Pair<Triple<Int, Long, jp.kjtr.drivelogger.geo.SmoothConfig?>, TripDigest>>()

    suspend fun digests(trips: List<TripEntity>): List<TripDigest> = withContext(Dispatchers.Default) {
        val out = ArrayList<TripDigest>(trips.size)
        for (t in trips) {
            val hit = synchronized(cache) { cache[t.id] }
            // 点の数と最終更新（0.6.0〜 ログを消した・再開して足した）・なめらかさ（0.8.0〜）が変わらなければ作り直さない
            val key = Triple(t.pointCount, t.updatedAt, TripRepository.smoothConfigOf(t))
            if (hit != null && hit.first == key && hit.second.facts.name == t.name) {
                out.add(hit.second)
                continue
            }
            val d = build(t)
            synchronized(cache) { cache[t.id] = key to d }
            out.add(d)
        }
        synchronized(cache) { cache.keys.retainAll(trips.map { it.id }.toSet()) }
        out
    }

    private suspend fun build(t: TripEntity): TripDigest {
        val pts = withContext(Dispatchers.IO) { db.trips().points(t.id) }
        val events = withContext(Dispatchers.IO) { db.trips().eventsNow(t.id) }
        val parks = withContext(Dispatchers.IO) { db.parks().forTripNow(t.id) }
        val logs = withContext(Dispatchers.IO) { db.logs().forTripNow(t.id) }
        // 地図の線・速度の分布はなめらかにした点（0.8.0〜。設定が OFF なら記録のまま）。獲得標高・マスは記録した点
        val cfg = TripRepository.smoothConfigOf(t)
        val segs = TripRepository.segmentsOf(TripRepository.smoothed(pts, cfg))
        val bins = HashMap<Int, Long>()
        if (pts.isNotEmpty()) {
            val a = TripRepository.analyze(pts, t.autoPause, cfg)
            for (s in a.steps) {
                val i = DriveAnalysis.binIndex(s.speedMps * 3.6, 10.0)
                bins[i] = (bins[i] ?: 0L) + s.dtMs
            }
        }
        // 獲得標高は ElevationGain（0.8.0〜。精度の悪い点・ネットワーク位置を除き、ならして 10 m 以上の登りだけ。詳細と同じ計算）
        val ascent = ElevationGain.of(pts)
        val end = t.endedAt ?: pts.lastOrNull()?.time ?: t.startedAt
        // 録画した時間は動画ログ（0.6.0〜）から。0.5.0 までの出来事 video は移行で動画ログにした
        val spans = (events.filter { it.type == TripEventEntity.TYPE_VIDEO } + TripRepository.videoEvents(logs, end))
            .map { TimeSpan(it.startedAt, minOf(it.endedAt ?: end, end)) }
        val protects = events.count { it.type == TripEventEntity.TYPE_PROTECT || it.type == TripEventEntity.TYPE_IMPACT }
        val lineSegs = segs.map { s -> s.map { LatLon(it.lat, it.lon) } }.filter { it.size >= 2 }
        val facts = TripFacts(
            id = t.id, name = t.name, startedAt = t.startedAt, endedAt = end, distanceM = t.distanceM, movingMs = t.movingMs,
            maxSpeedMps = t.maxSpeedMps, ascentM = ascent, videoMs = RecordedSpans.totalMs(spans), protects = protects,
            speedBinsMs = bins, cells = GridCells.cellsOf(pts.map { it.lat to it.lon }),
            parks = parks.size, parkMs = parks.sumOf { ((it.endedAt ?: end) - it.startedAt).coerceAtLeast(0) },
            // 曜日・時間帯は GPS ログの時間帯で数える（再開して日をまたいだドライブの、記録していない時間を入れない）
            logSpans = logs.filter { it.isGps }.map { it.startedAt to (it.endedAt ?: end) },
        )
        val lines = TripLines(
            t.id,
            coarse = lineSegs.map { Simplify.forDisplay(it, maxPoints = 300, toleranceM = 60.0) },
            fine = lineSegs.map { Simplify.forDisplay(it, maxPoints = 2000, toleranceM = 8.0) },
        )
        return TripDigest(facts, lines)
    }
}
