package jp.kjtr.drivelogger.playback

import jp.kjtr.drivelogger.data.ClipEntity
import jp.kjtr.drivelogger.data.DriveLogEntity
import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.PhotoEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.geo.Geo

/** タイムラインの 1 行の種類（同じ時刻のときはこの順に並べる）。 */
enum class TimelineKind { START, GPS, VIDEO, PARK, STOP, IMPACT, PROTECT, PHOTO, OTHER, END }

/**
 * タイムラインの 1 行。[endMs] は区間のもの（録画した区間・停車・GPS 不通など）だけ。
 * [photoId] は写真、[eventType] は出来事の種類（[TripEventEntity.type]）、[value] は衝撃の G・駐車中の徒歩の距離 [m]。
 * [inPark] は駐車の区間の中の行（写真など。タイムラインで字下げして駐車の下に並べる）、[parkId] は駐車の行の id。
 */
data class TimelineItem(
    val kind: TimelineKind,
    val startMs: Long,
    val endMs: Long? = null,
    val photoId: Long? = null,
    val eventType: String? = null,
    val value: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val inPark: Boolean = false,
    val parkId: Long? = null,
    /** GPS ログ・動画ログの番号（1 から）。 */
    val logNumber: Int = 0,
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }
}

/** 停車していた時間帯（[lat]/[lon] は止まった位置）。 */
data class StopSpan(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double)

/**
 * トリップの出来事を時系列に並べる（純ロジック。単体テスト TimelineTest）。
 * 録画した区間・写真・保護・衝撃・停車を時刻順に、始まり・終わりも入れる。
 */
object TripTimeline {
    /** 停車とみなす長さ（信号待ちの短い停車は出さない）。 */
    const val MIN_STOP_MS = 60_000L
    /** 停車とみなす速さ（トリップの「停車時間」と同じ 5 km/h 未満）[m/s]。 */
    const val STOP_SPEED_MPS = 5.0 / 3.6
    /** 出来事の時刻から動画を開くとき、少し前から見せる [ms]。 */
    const val EVENT_LEAD_MS = 5_000L
    /** 分割の切れ目（前のファイルの終わり〜次の Start）に落ちた時刻は、この範囲で次のファイルに寄せる [ms]。 */
    const val CLIP_GAP_TOLERANCE_MS = 3_000L

    fun build(
        startedAt: Long,
        endedAt: Long?,
        events: List<TripEventEntity>,
        photos: List<PhotoEntity>,
        stops: List<StopSpan>,
        parks: List<ParkEntity> = emptyList(),
        /** GPS ログ（0.6.0〜。開始〜終了の区間。[TimelineItem.value] = 距離 [m]、[TimelineItem.logNumber] = 何本目か）。 */
        gpsLogs: List<DriveLogEntity> = emptyList(),
    ): List<TimelineItem> {
        val out = ArrayList<TimelineItem>()
        out.add(TimelineItem(TimelineKind.START, startedAt))
        gpsLogs.filter { it.isGps }.sortedWith(compareBy({ it.startedAt }, { it.id })).forEachIndexed { i, l ->
            out.add(TimelineItem(TimelineKind.GPS, l.startedAt, l.endedAt ?: endedAt, value = l.distanceM, logNumber = i + 1))
        }
        for (p in parks) {
            out.add(TimelineItem(TimelineKind.PARK, p.startedAt, p.endedAt ?: endedAt, value = p.walkDistanceM.takeIf { it > 0 }, lat = p.lat, lon = p.lon, parkId = p.id))
        }
        var videoN = 0
        for (e in events.sortedWith(compareBy({ it.startedAt }, { it.id }))) {
            when (e.type) {
                TripEventEntity.TYPE_VIDEO -> out.add(TimelineItem(TimelineKind.VIDEO, e.startedAt, e.endedAt ?: endedAt, eventType = e.type, lat = e.lat, lon = e.lon, logNumber = ++videoN))
                TripEventEntity.TYPE_IMPACT -> out.add(TimelineItem(TimelineKind.IMPACT, e.startedAt, eventType = e.type, value = e.value, lat = e.lat, lon = e.lon))
                TripEventEntity.TYPE_PROTECT -> out.add(TimelineItem(TimelineKind.PROTECT, e.startedAt, eventType = e.type, lat = e.lat, lon = e.lon))
                else -> out.add(
                    TimelineItem(
                        TimelineKind.OTHER, e.startedAt, e.endedAt?.takeIf { it > e.startedAt }, eventType = e.type, value = e.value, lat = e.lat, lon = e.lon,
                    ),
                )
            }
        }
        for (p in photos) out.add(TimelineItem(TimelineKind.PHOTO, p.takenAt, photoId = p.id, lat = p.lat, lon = p.lon))
        for (s in stops) out.add(TimelineItem(TimelineKind.STOP, s.startMs, s.endMs, lat = s.lat, lon = s.lon))
        endedAt?.let { out.add(TimelineItem(TimelineKind.END, it)) }
        // 駐車の区間の中の行（写真・出来事）は、その駐車の下に入れ子にする（写真は紐づけ [PhotoEntity.parkId] か時刻で）
        val nested = out.map { item ->
            if (item.kind == TimelineKind.START || item.kind == TimelineKind.END || item.kind == TimelineKind.PARK || item.kind == TimelineKind.GPS) return@map item
            val byLink = item.photoId?.let { id -> photos.firstOrNull { it.id == id }?.parkId }?.let { pid -> parks.firstOrNull { it.id == pid } }
            val park = byLink ?: parks.firstOrNull { item.startMs >= it.startedAt && item.startMs <= (it.endedAt ?: endedAt ?: Long.MAX_VALUE) }
            if (park != null) item.copy(inPark = true, parkId = park.id) else item
        }
        return nested.sortedWith(compareBy<TimelineItem>({ order(it) }, { sortTime(it, parks) }, { if (it.inPark) 1 else 0 }, { it.startMs }, { it.kind.ordinal }, { it.photoId ?: 0L }))
    }

    /** 入れ子の行は、その駐車の始まりの時刻で並べてから駐車の中で時刻順（駐車の行のすぐ下に並ぶ）。 */
    private fun sortTime(i: TimelineItem, parks: List<ParkEntity>): Long =
        if (i.inPark) parks.firstOrNull { it.id == i.parkId }?.startedAt ?: i.startMs else i.startMs

    /** 始まりは常に先頭、終わりは常に最後（出発前・到着後の写真があっても）。 */
    private fun order(i: TimelineItem) = when (i.kind) {
        TimelineKind.START -> 0
        TimelineKind.END -> 2
        else -> 1
    }

    /**
     * 停車していた時間帯（[minMs] 以上）。停車中の点（[TripPointEntity.SOURCE_STOPPED]）か、速さが [maxSpeedMps] 未満の点が続いた間。
     * 速さが無い点は前の点からの距離 / 時間で。区間が変わったところで切る。点は時刻順でなくてもよい。
     */
    fun stops(points: List<TripPointEntity>, minMs: Long = MIN_STOP_MS, maxSpeedMps: Double = STOP_SPEED_MPS): List<StopSpan> {
        val pts = points.sortedWith(compareBy({ it.time }, { it.id }))
        val out = ArrayList<StopSpan>()
        var runStart: TripPointEntity? = null
        var runLast: TripPointEntity? = null
        fun close(endMs: Long) {
            val s = runStart ?: return
            if (endMs - s.time >= minMs) out.add(StopSpan(s.time, endMs, s.lat, s.lon))
            runStart = null
            runLast = null
        }
        var prev: TripPointEntity? = null
        for (p in pts) {
            val sameSeg = prev != null && prev.segment == p.segment
            if (!sameSeg && runStart != null) close(runLast!!.time)
            val speed = p.speed?.toDouble() ?: p.deviceSpeed?.toDouble() ?: prev?.takeIf { sameSeg && p.time > it.time }?.let {
                Geo.distanceM(it.lat, it.lon, p.lat, p.lon) / ((p.time - it.time) / 1000.0)
            }
            val still = p.source == TripPointEntity.SOURCE_STOPPED || (speed != null && speed < maxSpeedMps)
            if (still) {
                if (runStart == null) runStart = p
                runLast = p
            } else if (runStart != null) {
                // 動き出した点の時刻までを停車にする
                close(p.time)
            }
            prev = p
        }
        runLast?.let { close(it.time) }
        return out
    }

    /**
     * 時刻 [t] を映した動画と、その中の位置 [ms]（[leadMs] だけ前から。動画の頭より前にはしない）。
     * 分割の切れ目に落ちたら [gapToleranceMs] 以内に始まる次のファイルの頭（録画 ON の時刻は、カメラが開くまでの分だけ
     * 最初のファイルより前なので、録画した区間から開くときは長めに渡す）。無ければ null（容量の管理で消えた など）。
     */
    fun clipAt(clips: List<ClipEntity>, t: Long, leadMs: Long = 0, gapToleranceMs: Long = CLIP_GAP_TOLERANCE_MS): Pair<ClipEntity, Long>? {
        val usable = clips.filter { it.status != ClipEntity.STATUS_RECORDING || it.durationMs > 0 }.sortedBy { it.startedAt }
        for (c in usable) {
            val end = c.endedAt ?: (c.startedAt + c.durationMs)
            if (t >= c.startedAt && t < end) return c to (t - leadMs - c.startedAt).coerceAtLeast(0)
        }
        val next = usable.firstOrNull { it.startedAt >= t && it.startedAt - t <= gapToleranceMs } ?: return null
        return next to 0L
    }
}
