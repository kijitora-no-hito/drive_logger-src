package jp.kjtr.drivelogger.data

import android.util.Xml
import jp.kjtr.drivelogger.gpx.GpxParser
import jp.kjtr.drivelogger.gpx.GpxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val format: Int = 1,
    val app: String = "jp.kjtr.drivelogger",
    val exportedAt: Long,
    val trips: List<TripMeta>,
)

@Serializable
data class TripMeta(
    val file: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val autoPause: Boolean,
    val recordedVideo: Boolean = false,
    val distanceM: Double,
    val movingMs: Long,
    val stillMs: Long = 0,
    val maxSpeedMps: Double,
    val events: List<EventMeta> = emptyList(),
    /** 駐車・寄り道の区間（0.4.0〜。徒歩の軌跡と写真の紐づけは入れない）。 */
    val parks: List<ParkMeta> = emptyList(),
    /**
     * GPS ログの区間（0.6.0〜）。読み込むとき点を時刻でこのログに分ける。無い（0.5.0 までの）バックアップは駐車で分ける。
     * 動画ログは今までどおり出来事 video として [events] に入れる（読み込むと動画ログになる。動画のファイルは入れない）。
     */
    val gpsLogs: List<LogMeta> = emptyList(),
)

@Serializable
data class LogMeta(
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Serializable
data class ParkMeta(
    val startedAt: Long,
    val endedAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val walkDistanceM: Double = 0.0,
)

@Serializable
data class EventMeta(
    val type: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val value: Double? = null,
)

/**
 * トリップの ZIP バックアップ（manifest.json + トリップごとの GPX）。**動画は入れない**（大きいので。必要なら動画の一覧から書き出す）。
 * 読み込みは追加（今あるトリップは消さない）。
 */
class Backup(private val db: AppDatabase, private val trips: TripRepository) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun export(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val list = trips.allFinished()
        val metas = ArrayList<TripMeta>()
        ZipOutputStream(out).use { zip ->
            val w = OutputStreamWriter(zip, Charsets.UTF_8)
            for (t in list) {
                val file = "trips/trip_${t.id}.gpx"
                val data = trips.toGpx(t.id) ?: continue
                zip.putNextEntry(ZipEntry(file))
                GpxWriter.write(w, data, t.startedAt)
                w.flush()
                zip.closeEntry()
                val logs = db.logs().forTripNow(t.id)
                val events = (db.trips().eventsNow(t.id) + TripRepository.videoEvents(logs))
                    .sortedBy { it.startedAt }
                    .map { EventMeta(it.type, it.startedAt, it.endedAt, it.lat, it.lon, it.value) }
                val parks = db.parks().forTripNow(t.id).map { ParkMeta(it.startedAt, it.endedAt, it.lat, it.lon, it.walkDistanceM) }
                val gpsLogs = logs.filter { it.isGps }.map { LogMeta(it.startedAt, it.endedAt) }
                metas.add(TripMeta(file, t.name, t.startedAt, t.endedAt, t.autoPause, t.recordedVideo, t.distanceM, t.movingMs, t.stillMs, t.maxSpeedMps, events, parks, gpsLogs))
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            w.write(json.encodeToString(BackupManifest.serializer(), BackupManifest(exportedAt = System.currentTimeMillis(), trips = metas)))
            w.flush()
            zip.closeEntry()
        }
        metas.size
    }

    suspend fun import(zipFile: File): Int = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val mEntry = zip.getEntry(MANIFEST) ?: throw IllegalArgumentException("no manifest")
            val manifest = zip.getInputStream(mEntry).use { json.decodeFromString(BackupManifest.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
            var count = 0
            for (tm in manifest.trips) {
                val e = zip.getEntry(tm.file) ?: continue
                val data = zip.getInputStream(e).use { GpxParser().parse(Xml.newPullParser(), it) }
                val segs = data.tracks.flatMap { it.segments }
                val id = db.trips().insertTrip(
                    TripEntity(
                        name = tm.name, startedAt = tm.startedAt, endedAt = tm.endedAt, status = TripEntity.STATUS_FINISHED,
                        autoPause = tm.autoPause, recordedVideo = tm.recordedVideo, distanceM = tm.distanceM, movingMs = tm.movingMs,
                        stillMs = tm.stillMs, maxSpeedMps = tm.maxSpeedMps, pointCount = segs.sumOf { it.size }, updatedAt = System.currentTimeMillis(),
                    ),
                )
                val raw = ArrayList<TripPointEntity>()
                segs.forEachIndexed { si, s ->
                    for (p in s) raw.add(TripPointEntity(tripId = id, segment = si, time = p.timeMs ?: tm.startedAt, lat = p.lat, lon = p.lon, ele = p.ele, accuracy = null, deviceSpeed = null, speed = null, bearing = null))
                }
                // GPS ログ（0.6.0〜）: 書き出したときのログの区間で分ける。無ければ（0.5.0 までのバックアップ）駐車で分ける
                val groups: List<Pair<IntRange, Pair<Long, Long?>>> = if (tm.gpsLogs.isNotEmpty()) {
                    val logs = tm.gpsLogs.sortedBy { it.startedAt }
                    val idx = raw.map { p -> logs.indexOfLast { it.startedAt <= p.time }.coerceAtLeast(0) }
                    idx.indices.groupBy { idx[it] }.toSortedMap().map { (li, list) -> (list.first()..list.last()) to (logs[li].startedAt to logs[li].endedAt) }
                } else {
                    LogMigration.splitByParks(raw.mapIndexed { i, p -> LogMigration.Pt(i.toLong(), p.time) }, tm.parks.map { it.startedAt })
                        .map { (r, _) -> r to (raw[r.first].time to raw[r.last].time) }
                }
                val pts = ArrayList<TripPointEntity>(raw.size)
                val logIds = ArrayList<Long>()
                for ((r, span) in groups) {
                    val logId = db.logs().insert(DriveLogEntity(tripId = id, kind = DriveLogEntity.KIND_GPS, startedAt = minOf(span.first, raw[r.first].time), endedAt = span.second ?: raw[r.last].time))
                    logIds.add(logId)
                    for (i in r) pts.add(raw[i].copy(logId = logId))
                }
                pts.chunked(5_000).forEach { db.trips().insertPoints(it) }
                for (l in logIds) trips.recomputeLog(l, tm.autoPause)
                for (ev in tm.events) {
                    if (ev.type == TripEventEntity.TYPE_VIDEO) {
                        db.logs().insert(
                            DriveLogEntity(
                                tripId = id, kind = DriveLogEntity.KIND_VIDEO, startedAt = ev.startedAt, endedAt = ev.endedAt ?: tm.endedAt ?: ev.startedAt,
                                // 録画の方式（0.7.0〜。value = 1 は長時間録画。それより前のバックアップは無し＝ループ）
                                recordMode = ev.value?.toInt() ?: 0,
                            ),
                        )
                    } else {
                        db.trips().insertEvent(TripEventEntity(tripId = id, type = ev.type, startedAt = ev.startedAt, endedAt = ev.endedAt, lat = ev.lat, lon = ev.lon, value = ev.value))
                    }
                }
                for (pm in tm.parks) db.parks().insert(ParkEntity(tripId = id, startedAt = pm.startedAt, endedAt = pm.endedAt, lat = pm.lat, lon = pm.lon, walkDistanceM = pm.walkDistanceM))
                count++
            }
            count
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
    }
}
