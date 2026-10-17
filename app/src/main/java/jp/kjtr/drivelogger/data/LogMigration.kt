package jp.kjtr.drivelogger.data

/**
 * SQL を流す窓口（Room の SupportSQLiteDatabase と、単体テストの sqlite-jdbc の両方で同じ移行の処理を使うため）。
 * 値は SQLite の型のまま（整数 = Long、実数 = Double、文字列 = String、NULL = null）。
 */
interface SqlRunner {
    fun rows(sql: String, vararg args: Any?): List<List<Any?>>
    fun exec(sql: String, vararg args: Any?)
    /** INSERT して新しい行の id。 */
    fun insert(sql: String, vararg args: Any?): Long
}

/**
 * Room 版 3 → 4（0.6.0）のデータの移行（DESIGN.md §4.6）。表を足す SQL（[AppDatabase.MIGRATION_3_4_SQL]）の後に流す。
 *
 * - 0.5.0 までのドライブは「［開始］〜［終了］の GPS 記録 1 本」（駐車の間だけ GPS を止めた）。これを
 *   「ドライブ＋駐車で分かれた GPS ログ＋録画した区間ごとの動画ログ」にする。
 * - GPS ログ: 点（id 順）を駐車の開始時刻で分ける（駐車の前後で別のログ）。ログの統計は、そのログの点を流し直して計算する。
 * - 動画ログ: 出来事 `video`（録画した時間帯）を動画ログにし、出来事からは消す。動画（clip）はその時間帯（頭は 15 秒の余裕）のログに紐づける
 *   （どれにも入らなければいちばん近いログ。出来事が無いのに動画があれば、動画の範囲で 1 本作る）。
 * - 記録中のまま落ちたドライブ（status 0）は「開いているドライブ」のまま、最後の GPS ログ（駐車中でなければ）・終わっていない録画を
 *   記録中（endedAt = null）のまま残す → 次に開いたとき「途中で終わった記録」として続けるか聞く（今までどおり）。
 */
object LogMigration {
    /** 録画 ON の時刻から最初のファイルが始まるまでの余裕（カメラが開くまで）。 */
    const val CLIP_LEAD_MS = 15_000L
    const val CLIP_TAIL_MS = 5_000L

    /** 点 1 つ（移行の計算用）。 */
    data class Pt(val id: Long, val time: Long)

    /**
     * 点（id の順）を駐車の開始時刻 [parkStarts] で分ける。点の位置は「それより前に始まった駐車の数」で決まる（時刻が戻っても前のログには戻さない）。
     * 戻り値は空でないグループ（[points] の添字の範囲）と、そのグループが何番目の駐車の後か。
     */
    fun splitByParks(points: List<Pt>, parkStarts: List<Long>): List<Pair<IntRange, Int>> {
        if (points.isEmpty()) return emptyList()
        val starts = parkStarts.sorted()
        val out = ArrayList<Pair<IntRange, Int>>()
        var groupStart = 0
        var cur = -1
        for ((i, p) in points.withIndex()) {
            val k = maxOf(cur, starts.count { it <= p.time })
            if (cur == -1) {
                cur = k
            } else if (k != cur) {
                out.add((groupStart until i) to cur)
                groupStart = i
                cur = k
            }
        }
        out.add((groupStart until points.size) to cur)
        return out
    }

    /** 動画 [clipStart] をどの動画ログ（[logs] = 開始〜終了、終了 null は終わっていない）に入れるか（添字。無ければ null）。 */
    fun logForClip(clipStart: Long, logs: List<Pair<Long, Long?>>): Int? {
        if (logs.isEmpty()) return null
        logs.withIndex().firstOrNull { (_, l) -> clipStart >= l.first - CLIP_LEAD_MS && clipStart <= (l.second ?: Long.MAX_VALUE / 2) + CLIP_TAIL_MS }?.let { return it.index }
        return logs.withIndex().minByOrNull { (_, l) ->
            when {
                clipStart < l.first -> l.first - clipStart
                else -> clipStart - (l.second ?: l.first)
            }
        }?.index
    }

    fun run(db: SqlRunner) {
        val trips = db.rows("SELECT id, status, startedAt, endedAt, autoPause FROM trip ORDER BY id")
        for (t in trips) {
            convertTrip(
                db, id = long(t[0])!!, open = long(t[1]) != TripEntity.STATUS_FINISHED.toLong(), startedAt = long(t[2])!!, endedAt = long(t[3]),
                autoPause = long(t[4]) == 1L,
            )
        }
        db.exec("DELETE FROM trip_event WHERE type = ?", TripEventEntity.TYPE_VIDEO)
    }

    private fun convertTrip(db: SqlRunner, id: Long, open: Boolean, startedAt: Long, endedAt: Long?, autoPause: Boolean) {
        val pts = db.rows(
            "SELECT id, segment, time, lat, lon, ele, accuracy, deviceSpeed, bearing, speed, source FROM trip_point WHERE tripId = ? ORDER BY id", id,
        ).map { r ->
            TripPointEntity(
                id = long(r[0])!!, tripId = id, segment = long(r[1])!!.toInt(), time = long(r[2])!!, lat = dbl(r[3])!!, lon = dbl(r[4])!!,
                ele = dbl(r[5]), accuracy = dbl(r[6])?.toFloat(), deviceSpeed = dbl(r[7])?.toFloat(), bearing = dbl(r[8])?.toFloat(),
                speed = dbl(r[9])?.toFloat(), source = long(r[10])?.toInt() ?: 0,
            )
        }
        val parks = db.rows("SELECT startedAt, endedAt FROM park WHERE tripId = ? ORDER BY startedAt, id", id).map { long(it[0])!! to long(it[1]) }
        // ---- GPS ログ ----
        val groups = splitByParks(pts.map { Pt(it.id, it.time) }, parks.map { it.first })
        for ((gi, g) in groups.withIndex()) {
            val (range, afterParks) = g
            val slice = pts.subList(range.first, range.last + 1)
            val tr = TripRepository.replay(slice, autoPause)
            // 記録中のまま落ちたドライブの、最後の駐車より後のログは「記録中」のまま（駐車中に落ちたなら閉じる）
            val stillOpen = open && gi == groups.lastIndex && afterParks == parks.size && (parks.isEmpty() || parks.last().second != null)
            val logId = db.insert(
                "INSERT INTO drive_log (tripId, kind, startedAt, endedAt, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, DriveLogEntity.KIND_GPS.toLong(), slice.first().time, if (stillOpen) null else slice.last().time,
                tr.distanceM, tr.movingMs, tr.stillMs, tr.maxSpeedMps, slice.size.toLong(),
            )
            db.exec("UPDATE trip_point SET logId = ? WHERE tripId = ? AND id BETWEEN ? AND ?", logId, id, slice.first().id, slice.last().id)
        }
        // ---- 動画ログ ----
        val tripEnd = endedAt ?: pts.lastOrNull()?.time ?: startedAt
        val videoEvents = db.rows("SELECT startedAt, endedAt FROM trip_event WHERE tripId = ? AND type = ? ORDER BY startedAt, id", id, TripEventEntity.TYPE_VIDEO)
            .map { long(it[0])!! to (long(it[1]) ?: if (open) null else maxOf(tripEnd, long(it[0])!!)) }
            .toMutableList()
        val clips = db.rows("SELECT id, startedAt, durationMs FROM clip WHERE tripId = ? ORDER BY startedAt, id", id).map { Triple(long(it[0])!!, long(it[1])!!, long(it[2]) ?: 0L) }
        if (videoEvents.isEmpty() && clips.isNotEmpty()) {
            videoEvents.add(clips.first().second to clips.maxOf { it.second + it.third })
        }
        val videoIds = videoEvents.map { (s, e) ->
            db.insert("INSERT INTO drive_log (tripId, kind, startedAt, endedAt, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0)", id, DriveLogEntity.KIND_VIDEO.toLong(), s, e)
        }
        for (c in clips) {
            val i = logForClip(c.second, videoEvents) ?: continue
            db.exec("UPDATE clip SET logId = ? WHERE id = ?", videoIds[i], c.first)
        }
        if (videoIds.isNotEmpty()) db.exec("UPDATE trip SET recordedVideo = 1 WHERE id = ?", id)
        db.exec("UPDATE trip SET updatedAt = ? WHERE id = ?", endedAt ?: pts.lastOrNull()?.time ?: startedAt, id)
    }

    private fun long(v: Any?): Long? = when (v) {
        null -> null
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun dbl(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
