package jp.kjtr.drivelogger.data

import jp.kjtr.drivelogger.geo.SmoothConfig
import jp.kjtr.drivelogger.geo.TrackSmoother
import jp.kjtr.drivelogger.gpx.GpxData
import jp.kjtr.drivelogger.gpx.GpxPoint
import jp.kjtr.drivelogger.gpx.GpxTrack
import jp.kjtr.drivelogger.gpx.GpxWaypoint
import jp.kjtr.drivelogger.record.DriveTracker
import jp.kjtr.drivelogger.record.Sample
import jp.kjtr.drivelogger.record.TrackerConfig
import jp.kjtr.drivelogger.stats.DriveAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** GPX に書き出す位置（0.8.0〜）。[SMOOTHED] = なめらかにした位置、[RECORDED] = 記録した位置（融合後）、[RAW] = 元の GPS の値。 */
enum class GpxSource { SMOOTHED, RECORDED, RAW }

/** GPS ログの合計（[TripRepository.sumOfLogs]）。 */
data class LogTotals(
    val distanceM: Double,
    val movingMs: Long,
    val stillMs: Long,
    val maxSpeedMps: Double,
    val gpsLogs: Int,
    val videoLogs: Int,
)

class TripRepository(private val db: AppDatabase) {
    private val dao = db.trips()
    private val logDao = db.logs()

    fun finished(): Flow<List<TripEntity>> = dao.finished()
    /** 一覧（開いているドライブが先頭）。 */
    fun all(): Flow<List<TripEntity>> = dao.all()
    fun trip(id: Long): Flow<TripEntity?> = dao.trip(id)
    fun events(id: Long): Flow<List<TripEventEntity>> = dao.events(id)
    suspend fun eventsNow(id: Long) = withContext(Dispatchers.IO) { dao.eventsNow(id) }
    suspend fun tripNow(id: Long) = withContext(Dispatchers.IO) { dao.tripNow(id) }
    suspend fun points(id: Long): List<TripPointEntity> = withContext(Dispatchers.IO) { dao.points(id) }
    suspend fun pointsBetween(from: Long, to: Long): List<TripPointEntity> = withContext(Dispatchers.IO) { dao.pointsBetween(from, to) }
    /** 開いているドライブ（1 本まで）。 */
    suspend fun openDrive(): TripEntity? = withContext(Dispatchers.IO) { dao.openDrive() }
    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.clips().unlinkLogsOfTrip(id)
        dao.deleteTrip(id)
    }
    suspend fun allFinished() = dao.allFinished()

    // ---- GPS ログ・動画ログ（0.6.0〜。DESIGN.md §4.6） ----

    fun logs(tripId: Long): Flow<List<DriveLogEntity>> = logDao.forTrip(tripId)
    suspend fun logsNow(tripId: Long): List<DriveLogEntity> = withContext(Dispatchers.IO) { logDao.forTripNow(tripId) }

    /** GPS ログ [logId] の統計を、そのログの点から計算し直して保存する。 */
    suspend fun recomputeLog(logId: Long, autoPause: Boolean) = withContext(Dispatchers.IO) {
        val l = logDao.logNow(logId) ?: return@withContext
        if (!l.isGps) return@withContext
        val pts = dao.pointsOfLog(logId)
        // 距離は軌跡をなめらかにした点で（0.8.0〜。GPS のギザギザで長めに出ないよう）
        val tr = replay(pts, autoPause, smoothConfigOf(dao.tripNow(l.tripId)))
        logDao.update(l.copy(distanceM = tr.distanceM, movingMs = tr.movingMs, stillMs = tr.stillMs, maxSpeedMps = tr.maxSpeedMps, pointCount = pts.size))
    }

    /**
     * ドライブの統計（全 GPS ログの合計＝点を流し直す。ログとログの間はつながない）・最後の記録の時刻・録画したかを計算し直して保存する。
     */
    suspend fun recomputeTrip(tripId: Long): TripEntity? = withContext(Dispatchers.IO) {
        val t = dao.tripNow(tripId) ?: return@withContext null
        val pts = dao.points(tripId)
        val tr = replay(pts, t.autoPause, smoothConfigOf(t))
        val logs = logDao.forTripNow(tripId)
        val photos = db.photos().forTripNow(tripId)
        val parks = db.parks().forTripNow(tripId)
        val done = t.copy(
            distanceM = tr.distanceM, movingMs = tr.movingMs, stillMs = tr.stillMs, maxSpeedMps = tr.maxSpeedMps, pointCount = pts.size,
            recordedVideo = logs.any { it.isVideo },
            endedAt = if (t.isOpen) t.endedAt else lastActivity(t, logs, parks, photos, pts.lastOrNull()?.time),
            updatedAt = System.currentTimeMillis(),
        )
        dao.updateTrip(done)
        done
    }

    /**
     * GPS ログを消す（点ごと）。ドライブの統計は残りのログから計算し直す。記録中のログは消さない（false）。
     */
    suspend fun deleteGpsLog(logId: Long): Boolean = withContext(Dispatchers.IO) {
        val l = logDao.logNow(logId) ?: return@withContext false
        if (!l.isGps || l.endedAt == null) return@withContext false
        dao.deletePointsOfLog(logId)
        logDao.delete(logId)
        recomputeTrip(l.tripId)
        true
    }

    /** 動画ログの行を消す（動画のファイルは呼ぶ側が [jp.kjtr.drivelogger.dashcam.ClipStore] で先に消す）。記録中のログは消さない。 */
    suspend fun deleteVideoLogRow(logId: Long): Boolean = withContext(Dispatchers.IO) {
        val l = logDao.logNow(logId) ?: return@withContext false
        if (!l.isVideo || l.endedAt == null) return@withContext false
        logDao.delete(logId)
        recomputeTrip(l.tripId)
        true
    }

    suspend fun clipsOfLog(logId: Long): List<ClipEntity> = withContext(Dispatchers.IO) { db.clips().forLogNow(logId) }

    /**
     * ドライブを閉じる（［ドライブを終了］）。終わっていないログ・出来事・駐車は [at] で閉じ、統計を計算し直す。
     * 何も記録が無い（ログ・写真・駐車が無い）なら消して null。
     */
    suspend fun closeDrive(id: Long, at: Long = System.currentTimeMillis()): TripEntity? = withContext(Dispatchers.IO) {
        val t = dao.tripNow(id) ?: return@withContext null
        for (l in logDao.forTripNow(id)) if (l.endedAt == null) {
            val end = if (l.isGps) dao.pointsOfLog(l.id).lastOrNull()?.time ?: l.startedAt else maxOf(l.startedAt, at)
            logDao.close(l.id, end)
            if (l.isGps) recomputeLog(l.id, t.autoPause)
        }
        // 点が 1 つも無い GPS ログは残さない
        for (l in logDao.forTripNow(id)) if (l.isGps && dao.pointsOfLog(l.id).isEmpty()) logDao.delete(l.id)
        for (e in dao.eventsNow(id)) if (e.endedAt == null && e.type in OPEN_TYPES) dao.endEvents(id, e.type, at)
        db.parks().closeOpen(id, at)
        val logs = logDao.forTripNow(id)
        val photos = db.photos().forTripNow(id)
        val parks = db.parks().forTripNow(id)
        if (logs.isEmpty() && photos.isEmpty() && parks.isEmpty() && dao.pointCount(id) == 0) {
            db.clips().unlinkLogsOfTrip(id)
            dao.deleteTrip(id)
            return@withContext null
        }
        dao.updateTrip(t.copy(status = TripEntity.STATUS_FINISHED))
        recomputeTrip(id)
    }

    /** 閉じたドライブを開き直す（［このドライブを再開］）。ほかに開いているドライブがあれば何もしない（false）。 */
    suspend fun reopen(id: Long): Boolean = withContext(Dispatchers.IO) {
        val t = dao.tripNow(id) ?: return@withContext false
        val others = dao.openDrives().filter { it.id != id }
        if (others.isNotEmpty()) return@withContext false
        if (t.isOpen) return@withContext true
        dao.setStatus(id, TripEntity.STATUS_OPEN, System.currentTimeMillis())
        true
    }

    /**
     * 閉じたドライブの統計（GPS ログ・ドライブの距離など）を計算し直す（0.8.0〜。軌跡をなめらかにする設定が変わったとき・0.7.0 から上書きしたとき）。
     * 開いているドライブは記録中の統計を持つので触らない（閉じるときに計算し直す）。
     */
    suspend fun recomputeAllFinished() = withContext(Dispatchers.IO) {
        for (t in dao.allFinished()) {
            for (l in logDao.forTripNow(t.id)) if (l.isGps && l.endedAt != null) recomputeLog(l.id, t.autoPause)
            recomputeTrip(t.id)
        }
    }

    /** 表示用の点（軌跡をなめらかにする設定なら、そのドライブの乗り物でなめらかにした点）。 */
    suspend fun displayPoints(id: Long): List<TripPointEntity> = withContext(Dispatchers.Default) {
        smoothed(points(id), smoothConfigOf(tripNow(id)))
    }

    /** 動画と同期する点（どのドライブでも。なめらかにする設定ならドライブごとの乗り物で）。 */
    suspend fun displayPointsBetween(from: Long, to: Long): List<TripPointEntity> = withContext(Dispatchers.Default) {
        val pts = pointsBetween(from, to)
        if (!smoothingEnabled) return@withContext pts
        val trips = pts.map { it.tripId }.distinct().associateWith { tripNow(it) }
        smoothedBy(pts) { smoothConfigOf(trips[it]) }
    }

    fun parks(id: Long): Flow<List<ParkEntity>> = db.parks().forTrip(id)
    suspend fun parksNow(id: Long): List<ParkEntity> = withContext(Dispatchers.IO) { db.parks().forTripNow(id) }
    suspend fun walkPoints(id: Long): List<WalkPointEntity> = withContext(Dispatchers.IO) { db.parks().walkForTrip(id) }

    /**
     * GPX（[source] = なめらかにした位置 / 記録した位置（融合後）/ 元の GPS の値。0.8.0〜。バックアップは記録した位置）。[photoNames] があれば位置の分かる写真を wpt（type = photo）に
     * （名前は撮った時刻から作る関数。バックアップには入れない＝自転車ルートロガーと同じ）。
     */
    suspend fun toGpx(
        id: Long, source: GpxSource = GpxSource.RECORDED, photoNames: ((Long) -> String)? = null, walkName: String? = null,
        /** GPS ログ 1 本だけ（0.6.0〜。null ならドライブ全体＝ログごと・区間ごとに trkseg）。 */
        logId: Long? = null, logName: String? = null,
    ): GpxData? = withContext(Dispatchers.IO) {
        val t = dao.tripNow(id) ?: return@withContext null
        val log = logId?.let { logDao.logNow(it) }
        val pts = if (log != null) dao.pointsOfLog(log.id) else dao.points(id)
        val inLog: (Long) -> Boolean = { time -> log == null || (time >= log.startedAt && time <= (log.endedAt ?: Long.MAX_VALUE)) }
        val photos = if (photoNames != null) db.photos().forTripNow(id).filter { inLog(it.takenAt) } else emptyList()
        // 徒歩の軌跡は書き出しのときだけ（バックアップの読み込みは trk を走行の点として読むので入れない）。ログ 1 本のときは入れない
        val walk = if (walkName != null && log == null) db.parks().walkForTrip(id) else emptyList()
        val parks = if (log == null) db.parks().forTripNow(id) else emptyList()
        val named = if (log != null && logName != null) t.copy(name = t.name + " " + logName) else t
        val shown = when (source) {
            GpxSource.RAW -> rawPoints(pts)
            GpxSource.RECORDED -> pts
            GpxSource.SMOOTHED -> smoothed(pts, smoothConfigOf(t, force = true))
        }
        tripToGpx(named, shown, dao.eventsNow(id).filter { inLog(it.startedAt) }, photos, photoNames, parks, walk, walkName ?: "walk")
    }

    companion object {
        /** 始まりと終わりがある出来事（録画した区間は 0.6.0〜 動画ログ。0.5.0 までの出来事 video は移行で消した）。 */
        val OPEN_TYPES = setOf(TripEventEntity.TYPE_VIDEO, TripEventEntity.TYPE_NO_FIX, TripEventEntity.TYPE_LOCATION_OFF)

        /** 最後の記録の時刻（ログの終わり・駐車の終わり・写真・最後の点のいちばん遅いもの。無ければドライブを作った時刻）。 */
        fun lastActivity(t: TripEntity, logs: List<DriveLogEntity>, parks: List<ParkEntity>, photos: List<PhotoEntity>, lastPoint: Long?): Long =
            listOfNotNull(
                lastPoint, logs.maxOfOrNull { it.endedAt ?: it.startedAt }, parks.maxOfOrNull { it.endedAt ?: it.startedAt },
                photos.maxOfOrNull { it.takenAt },
            ).maxOrNull()?.coerceAtLeast(t.startedAt) ?: t.startedAt

        /**
         * 動画ログを「録画した時間帯」の出来事（type video）の形にする（タイムライン・地図の録画した区間・まとめの録画した時間で、
         * 0.5.0 までの出来事 video と同じに扱うため）。終わっていないログは [openEnd] まで。
         */
        fun videoEvents(logs: List<DriveLogEntity>, openEnd: Long? = null): List<TripEventEntity> =
            logs.filter { it.isVideo }.map {
                // value = 録画の方式（0.7.0〜。長時間録画だけ 1。バックアップで動画ログに戻すとき使う）
                TripEventEntity(
                    id = -it.id, tripId = it.tripId, type = TripEventEntity.TYPE_VIDEO, startedAt = it.startedAt, endedAt = it.endedAt ?: openEnd,
                    value = if (it.isLong) it.recordMode.toDouble() else null,
                )
            }

        /** GPS ログの統計の合計（ドライブの統計と同じになる。最高速度は最大）。 */
        fun sumOfLogs(logs: List<DriveLogEntity>): LogTotals {
            val gps = logs.filter { it.isGps }
            return LogTotals(
                distanceM = gps.sumOf { it.distanceM }, movingMs = gps.sumOf { it.movingMs }, stillMs = gps.sumOf { it.stillMs },
                maxSpeedMps = gps.maxOfOrNull { it.maxSpeedMps } ?: 0.0, gpsLogs = gps.size, videoLogs = logs.count { it.isVideo },
            )
        }

        fun sampleOf(p: TripPointEntity) = Sample(p.time, p.lat, p.lon, p.accuracy, p.ele, p.deviceSpeed, p.bearing)

        /**
         * 保存した点を流し直して統計を作る（区間・GPS ログが変わるところで切る＝ログとログの間の時間・距離は数えない）。
         */
        fun replay(points: List<TripPointEntity>, autoPause: Boolean, smooth: SmoothConfig? = null): DriveTracker {
            val tr = DriveTracker(TrackerConfig(autoPause = autoPause))
            var key: Pair<Int, Long?>? = null
            for (p in smoothed(points, smooth)) {
                val k = p.segment to p.logId
                if (k != key) {
                    if (key != null) tr.breakSegment()
                    key = k
                }
                tr.feed(sampleOf(p))
            }
            return tr
        }

        /** 詳細の区間の表・速度の分布。[replay] と同じ順・同じ切れ目で流す。 */
        fun analyze(points: List<TripPointEntity>, autoPause: Boolean, smooth: SmoothConfig? = null): DriveAnalysis =
            DriveAnalysis.from(segmentsOf(smoothed(points, smooth)).map { s -> s.map { sampleOf(it) } }, autoPause)

        /**
         * 軌跡をなめらかにする（0.8.0〜 設定「軌跡をなめらかにする」。[DriveApp] が設定から入れる。単体テストでは false＝記録のまま）。
         * 表示（詳細・まとめ・SNS 用の画像・動画に重ねる地図）と統計（距離）に使う。記録（DB の点）は変えない。
         */
        @Volatile var smoothingEnabled: Boolean = false

        /** ドライブ [t] の軌跡のなめらかさ（設定が OFF なら null。[force] = 設定によらず）。 */
        fun smoothConfigOf(t: TripEntity?, force: Boolean = false): SmoothConfig? =
            if (t != null && (smoothingEnabled || force)) t.vehicleKind.smoothConfig else null

        /**
         * 点をなめらかにした点（位置だけ置き換える。数・順・時刻・速度はそのまま）。ドライブ・区間・GPS ログごとに別々に流す。
         * [smoothOf] = ドライブの id → なめらかさ（null なら記録のまま）。
         */
        fun smoothed(points: List<TripPointEntity>, smooth: SmoothConfig?): List<TripPointEntity> =
            if (smooth == null) points else smoothedBy(points) { smooth }

        fun smoothedBy(points: List<TripPointEntity>, smoothOf: (Long) -> SmoothConfig?): List<TripPointEntity> {
            if (points.size < 3) return points
            val out = points.toMutableList()
            val groups = points.indices.groupBy { Triple(points[it].tripId, points[it].segment, points[it].logId) }
            for ((key, idx) in groups) {
                val cfg = smoothOf(key.first) ?: continue
                if (idx.size < 3) continue
                val sorted = idx.sortedBy { points[it].time }
                val r = TrackSmoother.smooth(sorted.map { val p = points[it]; TrackSmoother.In(p.time, p.lat, p.lon, p.accuracy) }, cfg)
                sorted.forEachIndexed { k, i -> out[i] = points[i].copy(lat = r.lat[k], lon = r.lon[k]) }
            }
            return out
        }

        /**
         * 元の GPS の値の点（融合した点は元の測位に置き換え、推測航法・停車中の点は除く。融合 OFF・0.2.0 以前の点はそのまま）。
         */
        fun rawPoints(points: List<TripPointEntity>): List<TripPointEntity> = points.mapNotNull { p ->
            when {
                p.rawLat != null && p.rawLon != null -> p.copy(lat = p.rawLat, lon = p.rawLon, accuracy = p.rawAccuracy, deviceSpeed = p.rawSpeed)
                p.source == TripPointEntity.SOURCE_GPS -> p
                else -> null
            }
        }

        /** 区間ごとの点（GPS ログが違えば別の区間＝地図の線・GPX の trkseg をログの間でつながない）。 */
        fun segmentsOf(points: List<TripPointEntity>): List<List<TripPointEntity>> =
            points.groupBy { it.segment to (it.logId ?: Long.MIN_VALUE) }
                .toSortedMap(compareBy<Pair<Int, Long>>({ it.first }, { it.second })).values.toList()

        /**
         * GPX（軌跡 = trk、区間で trkseg を分ける＝駐車の前後も分かれる。保護・衝撃の出来事と駐車の位置は wpt、[photoNames] があれば
         * 位置の分かる写真も wpt。時刻順）。[walk]（駐車中の徒歩の点）があれば 2 本目の trk（駐車ごとに trkseg）。
         */
        fun tripToGpx(
            t: TripEntity, points: List<TripPointEntity>, events: List<TripEventEntity>,
            photos: List<PhotoEntity> = emptyList(), photoNames: ((Long) -> String)? = null,
            parks: List<ParkEntity> = emptyList(), walk: List<WalkPointEntity> = emptyList(), walkName: String = "walk",
        ): GpxData {
            val parkWps = parks.filter { it.lat != null && it.lon != null }.map { p ->
                GpxWaypoint(p.lat!!, p.lon!!, null, p.startedAt, "parking", null, "parking", "Parking Area")
            }
            val walkTrack = if (walk.isEmpty()) emptyList() else listOf(
                GpxTrack(walkName, walk.groupBy { it.parkId }.toSortedMap().values.map { s -> s.sortedBy { it.time }.map { GpxPoint(it.lat, it.lon, null, it.time) } }),
            )
            val eventWps = events.filter { (it.type == TripEventEntity.TYPE_IMPACT || it.type == TripEventEntity.TYPE_PROTECT) && it.lat != null && it.lon != null }
                .map { e ->
                    val name = if (e.type == TripEventEntity.TYPE_IMPACT) {
                        "impact" + (e.value?.let { " " + String.format(java.util.Locale.ROOT, "%.2fG", it) } ?: "")
                    } else "protect"
                    GpxWaypoint(e.lat!!, e.lon!!, null, e.startedAt, name, null, e.type, "Flag")
                }
            val photoWps = if (photoNames == null) emptyList() else photos.filter { it.lat != null && it.lon != null }.map { p ->
                GpxWaypoint(p.lat!!, p.lon!!, null, p.takenAt, photoNames(p.takenAt), null, "photo", "Scenic Area")
            }
            return GpxData(
                name = t.name,
                desc = null,
                tracks = listOf(GpxTrack(t.name, segmentsOf(points).map { s -> s.map { GpxPoint(it.lat, it.lon, it.ele, it.time) } })) + walkTrack,
                waypoints = (eventWps + photoWps + parkWps).sortedBy { it.timeMs ?: 0L },
                version = "1.1",
            )
        }
    }
}
