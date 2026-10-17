package jp.kjtr.drivelogger.record

import android.location.Location
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.data.AppDatabase
import jp.kjtr.drivelogger.data.DriveLogEntity
import jp.kjtr.drivelogger.data.ParkEntity
import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.data.WalkPointEntity
import jp.kjtr.drivelogger.geo.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 開いているドライブの表示用の状態（何も記録していないときもある）。 */
data class DriveState(
    val tripId: Long,
    val name: String,
    val startedAt: Long,
    /** 全 GPS ログの合計（ログとログの間は数えない）。 */
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val stillMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val averageSpeedMps: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val courseDeg: Double? = null,
    val autoPaused: Boolean = false,
    val pointCount: Int = 0,
    val lastFixAt: Long? = null,
    /** いまの位置（記録した最後の点。融合が ON なら融合後）。 */
    val lat: Double? = null,
    val lon: Double? = null,
    /** 停車中（測位を止めている）。 */
    val stopped: Boolean = false,
    /** 最後の点の出どころ（[TripPointEntity.SOURCE_GPS] など）。 */
    val source: Int = TripPointEntity.SOURCE_GPS,
    /** 歩いていると判定して、GPS のまま記録している（0.8.0〜）。 */
    val walking: Boolean = false,
    /** 最後の点が Google の融合測位（0.8.0〜）。 */
    val fusedProvider: Boolean = false,
    // ---- GPS ログ・動画ログ（0.6.0〜） ----
    /** GPS ログを記録中。 */
    val gpsLogging: Boolean = false,
    val gpsLogStartedAt: Long? = null,
    /** 今の GPS ログの距離 [m]。 */
    val gpsLogDistanceM: Double = 0.0,
    /** このドライブの GPS ログの数（記録中のものを含む）。 */
    val gpsLogCount: Int = 0,
    /** 動画ログを記録中（録画中）。 */
    val videoLogging: Boolean = false,
    val videoLogCount: Int = 0,
    // ---- 駐車・寄り道（0.4.0〜） ----
    /** 駐車中（GPS ログを閉じて駐車の位置を記録した。ドライブは終わっていない）。 */
    val parked: Boolean = false,
    /** 今の駐車の行の id（行ができるまで null のことがある）。 */
    val parkId: Long? = null,
    val parkStartedAt: Long? = null,
    /** 駐車した位置（車に戻る目安）。 */
    val parkLat: Double? = null,
    val parkLon: Double? = null,
    /** 今の駐車の徒歩の距離 [m]（徒歩の軌跡を記録しているときだけ）。 */
    val walkDistanceM: Double = 0.0,
    /** このドライブの駐車の回数（今の駐車を含む）と、終わった駐車の時間の合計 [ms]。 */
    val parkCount: Int = 0,
    val parkedMs: Long = 0,
) {
    /** 何かを記録している（GPS か録画）。 */
    val recording: Boolean get() = gpsLogging || videoLogging
}

/** [DriveSession.restore] の結果: 記録中のまま落ちたログがあったか。 */
data class Restored(val trip: TripEntity, val interruptedGps: Boolean, val interruptedVideo: Boolean) {
    val interrupted: Boolean get() = interruptedGps || interruptedVideo
}

/**
 * 開いているドライブ（アプリ全体で 1 つ。0.6.0〜 DESIGN.md §4.6）。ドライブは GPS ログ・動画ログの入れ物で、
 * 開いている間に［GPS 記録 開始/停止］［録画 開始/停止］をそれぞれ何度でもできる（[startGpsLog] / [stopGpsLog]・[startVideoLog] / [endVideoLog]）。
 * 位置は [jp.kjtr.drivelogger.service.DriveService]（記録している間だけ動く）が [onLocation] に渡す（メインスレッド）。
 * 採用した点はその場で Room に書く（1 本のキューで順番に）。統計（ドライブ＝全ログの合計）は 10 点ごとと終了で保存する。
 * 落ちても点は残り、[restore] で流し直して続きから記録できる。
 */
class DriveSession(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val dao = db.trips()
    private val logDao = db.logs()
    private val repo = TripRepository(db)

    private val _state = MutableStateFlow<DriveState?>(null)
    val state: StateFlow<DriveState?> = _state

    private val _line = MutableStateFlow<List<List<LatLon>>>(emptyList())
    /** 走った軌跡（区間ごと。GPS ログの間はつながない）。 */
    val line: StateFlow<List<List<LatLon>>> = _line

    private var tracker = DriveTracker()
    private var entity: TripEntity? = null
    private var segment = 0
    private var segmentHasPoints = false
    private var sinceSave = 0
    private var lastLocation: Location? = null

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // ---- GPS ログ・動画ログ（0.6.0〜） ----
    private var gpsLogDeferred: CompletableDeferred<Long>? = null
    private var gpsLogStartedAt = 0L
    private var gpsLogStartDistance = 0.0
    /** 今の GPS ログで採用した点の数。 */
    private var gpsLogPoints = 0
    private var gpsLogCount = 0
    private var videoLogDeferred: CompletableDeferred<Long>? = null
    private var videoLogCount = 0

    // ---- 融合・停車（0.3.0〜。DESIGN.md §4.2・§4.3） ----
    /** 融合を使うか（設定。点のたびに読む）。 */
    var fusionEnabled: () -> Boolean = { true }
    /** 融合・歩行の判定（0.8.0〜 歩いている間と Google の融合測位のときは自前の融合をしない。DESIGN.md §4.7）。 */
    private val pipeline = FixPipeline()
    private var lastProvider = TripPointEntity.PROVIDER_UNKNOWN
    /** 新しいドライブの乗り物（設定。0.8.0〜）。 */
    var vehicleNow: () -> Vehicle = { Vehicle.CAR }
    /** 記録中の線を前向きのフィルタでならす（設定「軌跡をなめらかにする」。0.8.0〜）。 */
    var smoothingEnabled: () -> Boolean = { false }
    private var liveSmoother: jp.kjtr.drivelogger.geo.TrackSmoother.Forward? = null
    private var stopped = false
    private var holdLat = 0.0
    private var holdLon = 0.0
    private var holdAlt: Double? = null
    /** UTC = elapsedRealtime + これ（最後の測位から）。 */
    private var utcOffsetMs: Long? = null
    private var lastGpsElapsed = Long.MIN_VALUE
    private var lastPointElapsed = Long.MIN_VALUE
    private var lastSource = TripPointEntity.SOURCE_GPS

    val isStopped: Boolean get() = stopped

    // ---- 駐車・寄り道（0.4.0〜。DESIGN.md §4.4） ----
    private val parks = db.parks()
    private var parked = false
    /** GPS ログを記録していたときに駐車した（［走行を再開］で GPS ログを始め直す）。 */
    private var parkedWhileLogging = false
    private var parkIdDeferred: CompletableDeferred<Long>? = null
    private var parkId: Long? = null
    private var parkStartedAt = 0L
    private var parkLat: Double? = null
    private var parkLon: Double? = null
    private var walkM = 0.0
    private var lastWalk: Location? = null
    private var parkCount = 0
    private var parkedMsClosed = 0L

    /** 駐車中。 */
    val isParked: Boolean get() = parked

    /** 続いている出来事（位置情報 OFF・位置が届かない）。 */
    private val openConditions = HashSet<String>()

    init {
        scope.launch(Dispatchers.IO) {
            for (w in writes) runCatching { w() }
        }
    }

    /** ドライブが開いている。 */
    val isActive: Boolean get() = _state.value != null
    val tripId: Long? get() = entity?.id
    /** GPS ログを記録中。 */
    val isGpsLogging: Boolean get() = gpsLogDeferred != null
    /** 動画ログを記録中。 */
    val isVideoLogging: Boolean get() = videoLogDeferred != null

    /** 乗り物を変えた（設定。0.8.0〜）。開いているドライブにも使う（メインスレッドから）。 */
    fun setVehicle(v: Vehicle) {
        val e = entity ?: return
        if (e.vehicle == v.ordinal) return
        entity = e.copy(vehicle = v.ordinal)
        liveSmoother = null
        saveEntity()
    }

    /** 新しいドライブを開く（何も記録しない。DB に行を作る）。 */
    suspend fun create(name: String, autoPause: Boolean): Long {
        val now = System.currentTimeMillis()
        val t = TripEntity(name = name, startedAt = now, status = TripEntity.STATUS_OPEN, autoPause = autoPause, updatedAt = now, vehicle = vehicleNow().ordinal)
        val id = withContext(Dispatchers.IO) { dao.insertTrip(t) }
        withContext(Dispatchers.Main) {
            entity = t.copy(id = id)
            tracker = DriveTracker(TrackerConfig(autoPause = autoPause))
            segment = 0
            segmentHasPoints = false
            sinceSave = 0
            lastLocation = null
            openConditions.clear()
            resetLogs()
            resetPositioning()
            resetParking()
            _line.value = emptyList()
            publish()
        }
        return id
    }

    /**
     * 開いているドライブ [t] を読み込む（アプリの起動時・［このドライブを再開］）。保存済みの点を流し直して統計を作る。
     * 記録中のまま落ちたログ（endedAt = null）は最後の点（動画は最後のファイル）の時刻で閉じて、結果で知らせる（続けるかは呼ぶ側が決める）。
     * 駐車中に落ちたなら駐車中のまま。
     */
    suspend fun restore(t: TripEntity): Restored {
        var gpsCut = false
        var videoCut = false
        val logs = withContext(Dispatchers.IO) {
            for (l in logDao.forTripNow(t.id)) if (l.endedAt == null) {
                if (l.isGps) {
                    gpsCut = true
                    logDao.close(l.id, dao.pointsOfLog(l.id).lastOrNull()?.time ?: l.startedAt)
                    repo.recomputeLog(l.id, t.autoPause)
                } else {
                    videoCut = true
                    val clips = db.clips().forLogNow(l.id)
                    logDao.close(l.id, clips.maxOfOrNull { it.endedAt ?: (it.startedAt + it.durationMs) }?.coerceAtLeast(l.startedAt) ?: l.startedAt)
                }
            }
            logDao.forTripNow(t.id)
        }
        val pts = withContext(Dispatchers.IO) { dao.points(t.id) }
        val parkRows = withContext(Dispatchers.IO) { parks.forTripNow(t.id) }
        withContext(Dispatchers.IO) {
            val lastAt = pts.lastOrNull()?.time ?: t.startedAt
            // 落ちる前に開いていた出来事（GPS 不通など）は最後の点で閉じる
            for (type in TripRepository.OPEN_TYPES) dao.endEvents(t.id, type, lastAt)
        }
        withContext(Dispatchers.Main) {
            tracker = TripRepository.replay(pts, t.autoPause, TripRepository.smoothConfigOf(t))
            tracker.breakSegment()
            entity = t.copy(status = TripEntity.STATUS_OPEN)
            segment = (pts.maxOfOrNull { it.segment } ?: -1) + 1
            segmentHasPoints = false
            sinceSave = 0
            openConditions.clear()
            resetLogs()
            gpsLogCount = logs.count { it.isGps }
            videoLogCount = logs.count { it.isVideo }
            resetPositioning()
            resetParking()
            // 駐車中に落ちたなら、駐車中のまま続ける（［走行を再開］で GPS ログを始める）
            val open = parkRows.lastOrNull { it.endedAt == null }
            parkCount = parkRows.size
            parkedMsClosed = parkRows.filter { it.endedAt != null }.sumOf { it.endedAt!! - it.startedAt }
            if (open != null) {
                parked = true
                parkedWhileLogging = true
                parkId = open.id
                parkIdDeferred = CompletableDeferred(open.id)
                parkStartedAt = open.startedAt
                parkLat = open.lat
                parkLon = open.lon
                walkM = open.walkDistanceM
            }
            _line.value = TripRepository.segmentsOf(TripRepository.smoothed(pts, TripRepository.smoothConfigOf(t))).map { s -> s.map { LatLon(it.lat, it.lon) } }
            liveSmoother = null
            publish()
            saveEntity()
        }
        return Restored(t, gpsCut, videoCut)
    }

    // ---- GPS ログ ----

    /** GPS ログを始める（新しい区間から）。駐車中なら駐車を閉じてから。始めたら true。 */
    fun startGpsLog(at: Long = System.currentTimeMillis()): Boolean {
        val e = entity ?: return false
        if (gpsLogDeferred != null) return false
        if (parked) closePark(at)
        tracker.breakSegment()
        if (segmentHasPoints) {
            segment++
            segmentHasPoints = false
        }
        resetPositioning()
        gpsLogStartedAt = at
        gpsLogStartDistance = tracker.distanceM
        gpsLogPoints = 0
        gpsLogCount++
        val d = CompletableDeferred<Long>()
        gpsLogDeferred = d
        writes.trySend { d.complete(logDao.insert(DriveLogEntity(tripId = e.id, kind = DriveLogEntity.KIND_GPS, startedAt = at))) }
        publish()
        return true
    }

    /** GPS ログを閉じる（統計はこのログの点から計算して保存する）。 */
    fun stopGpsLog(at: Long = System.currentTimeMillis()): Boolean {
        val e = entity ?: return false
        val d = gpsLogDeferred ?: return false
        if (stopped) holdTick(android.os.SystemClock.elapsedRealtime(), force = true)
        stopped = false
        gpsLogDeferred = null
        tracker.breakSegment()
        resetPositioning()
        // 続いている出来事（GPS 不通など）は GPS ログと一緒に閉じる
        openConditions.toList().forEach { setCondition(it, false, at) }
        val autoPause = e.autoPause
        // 点が 1 つも無かったログ（始めてすぐ止めた・測位できなかった）は残さない
        val empty = gpsLogPoints == 0
        if (empty) gpsLogCount = (gpsLogCount - 1).coerceAtLeast(0)
        writes.trySend {
            val id = d.await()
            if (empty && dao.pointsOfLog(id).isEmpty()) {
                logDao.delete(id)
            } else {
                logDao.close(id, at)
                repo.recomputeLog(id, autoPause)
            }
        }
        saveEntity()
        publish()
        return true
    }

    // ---- 動画ログ ----

    /** 動画ログを始める（録画を始める直前に。[mode] = 録画の方式。行の id を返す）。 */
    suspend fun startVideoLog(mode: RecordMode = RecordMode.LOOP, at: Long = System.currentTimeMillis()): Long? {
        val d = withContext(Dispatchers.Main) {
            val e = entity ?: return@withContext null
            videoLogDeferred?.let { return@withContext it }
            val d = CompletableDeferred<Long>()
            videoLogDeferred = d
            videoLogCount++
            writes.trySend { d.complete(logDao.insert(DriveLogEntity(tripId = e.id, kind = DriveLogEntity.KIND_VIDEO, startedAt = at, recordMode = mode.code))) }
            if (!e.recordedVideo) entity = e.copy(recordedVideo = true)
            saveEntity()
            publish()
            d
        } ?: return null
        return d.await()
    }

    /** 動画ログを閉じる（録画が止まった）。 */
    fun endVideoLog(at: Long = System.currentTimeMillis()) {
        val d = videoLogDeferred ?: return
        videoLogDeferred = null
        writes.trySend { logDao.close(d.await(), at) }
        publish()
    }

    // ---- 駐車 ----

    /**
     * ［駐車する］: GPS ログを閉じて、駐車した位置（最後の走行の点）を記録する（ドライブは続く）。録画は呼ぶ側が先に止める。
     * 何も記録していなくても駐車できる（位置は最後に届いた位置）。
     */
    fun park(at: Long = System.currentTimeMillis()): Boolean {
        val e = entity ?: return false
        if (parked) return false
        val ll = tracker.lastAccepted?.takeIf { gpsLogDeferred != null }?.let { LatLon(it.lat, it.lon) } ?: lastLatLon()
        parkedWhileLogging = gpsLogDeferred != null
        stopGpsLog(at)
        parked = true
        parkStartedAt = at
        parkLat = ll?.lat
        parkLon = ll?.lon
        walkM = 0.0
        lastWalk = null
        _walkLine.value = emptyList()
        parkCount++
        parkId = null
        val deferred = CompletableDeferred<Long>()
        parkIdDeferred = deferred
        writes.trySend {
            val id = parks.insert(ParkEntity(tripId = e.id, startedAt = at, lat = ll?.lat, lon = ll?.lon))
            deferred.complete(id)
        }
        scope.launch(Dispatchers.Main) {
            val id = deferred.await()
            if (parked && parkIdDeferred === deferred) {
                parkId = id
                publish()
            }
        }
        saveEntity()
        publish()
        return true
    }

    /** 今の駐車の行の id（行ができるのを待つ。駐車中でなければ null）。駐車中に撮った写真の紐づけに使う。 */
    suspend fun currentParkId(): Long? = if (parked) parkIdDeferred?.await() else null

    /**
     * ［走行を再開］: 駐車を閉じる。GPS ログを記録していたときに駐車したなら、新しい GPS ログを始める（戻り値 true）。
     */
    fun resumeDrive(at: Long = System.currentTimeMillis()): Boolean {
        if (entity == null || !parked) return false
        val restart = parkedWhileLogging
        closePark(at)
        if (restart) startGpsLog(at)
        publish()
        return restart
    }

    private fun closePark(at: Long) {
        val d = parkIdDeferred
        val walk = walkM
        parkedMsClosed += (at - parkStartedAt).coerceAtLeast(0)
        parked = false
        parkedWhileLogging = false
        parkId = null
        parkIdDeferred = null
        lastWalk = null
        if (d != null) writes.trySend { parks.close(d.await(), at, walk) }
    }

    /**
     * 駐車中の徒歩の点（設定「駐車中の徒歩の軌跡も記録」が ON のときだけサービスが渡す。低頻度）。
     * 精度 50 m 以内・前の点から 10 m 以上動いたものだけ残し、徒歩の距離に足す（走行距離とは別）。
     */
    fun onWalkLocation(l: Location) {
        val e = entity ?: return
        if (!parked) return
        lastLocation = l
        if (l.hasAccuracy() && l.accuracy > WALK_MAX_ACCURACY_M) return
        val prev = lastWalk
        if (prev != null) {
            val d = jp.kjtr.drivelogger.geo.Geo.distanceM(prev.latitude, prev.longitude, l.latitude, l.longitude)
            if (d < WALK_MIN_STEP_M) return
            walkM += d
        }
        lastWalk = l
        val deferred = parkIdDeferred ?: return
        val p = l
        val walk = walkM
        writes.trySend {
            val pid = deferred.await()
            parks.insertWalk(WalkPointEntity(tripId = e.id, parkId = pid, time = p.time, lat = p.latitude, lon = p.longitude, accuracy = if (p.hasAccuracy()) p.accuracy else null))
            parks.setWalk(pid, walk)
        }
        val cur = _walkLine.value
        _walkLine.value = cur + LatLon(l.latitude, l.longitude)
        publish()
    }

    private val _walkLine = MutableStateFlow<List<LatLon>>(emptyList())
    /** 今の駐車中の徒歩の軌跡（徒歩の軌跡を記録しているときだけ）。 */
    val walkLine: StateFlow<List<LatLon>> = _walkLine

    private fun resetParking() {
        parked = false
        parkedWhileLogging = false
        parkId = null
        parkIdDeferred = null
        parkStartedAt = 0L
        parkLat = null
        parkLon = null
        walkM = 0.0
        lastWalk = null
        parkCount = 0
        parkedMsClosed = 0L
        _walkLine.value = emptyList()
    }

    private fun resetLogs() {
        gpsLogDeferred = null
        gpsLogStartedAt = 0L
        gpsLogStartDistance = 0.0
        gpsLogCount = 0
        videoLogDeferred = null
        videoLogCount = 0
    }

    /** 表示用の位置（記録していないときもサービス・画面が渡す。記録はしない）。 */
    fun onIdleLocation(l: Location) {
        if (entity == null || gpsLogDeferred != null) return
        lastLocation = l
    }

    /**
     * GPS の測位（サービスから）。GPS ログを記録していなければ使わない。融合が ON なら加速度・ジャイロと融合した位置を記録し、元の測位も点に残す。
     * 停車中は呼ばれない（サービスが停車の確かめの測位を見て、動いていたら [exitStop] してから渡す）。
     */
    fun onLocation(l: Location) {
        if (entity == null) return
        lastLocation = l
        if (gpsLogDeferred == null) return
        val el = l.elapsedRealtimeNanos / 1_000_000
        utcOffsetMs = l.time - el
        if (stopped || parked) return
        lastGpsElapsed = el
        val raw = Sample(
            timeMs = l.time,
            lat = l.latitude,
            lon = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed else null,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
        )
        val provider = TripPointEntity.providerOf(l.provider)
        pipeline.fusionEnabled = fusionEnabled()
        // Google の融合測位の位置はセンサーと融合済みなので、自前の融合を重ねない
        pipeline.externalFused = provider == TripPointEntity.PROVIDER_FUSED
        val o = pipeline.onFix(el, raw)
        val vAcc = if (android.os.Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null
        record(o.sample, o.source, o.raw, el, provider, vAcc)
    }

    /** 加速度・ジャイロ（サービスから、約 50 Hz）。融合の予測を進め、歩いているかを見る（[accelNorm] = 重力を含む加速度の大きさ）。 */
    fun onMotion(elapsedMs: Long, forwardAccel: Double?, yawRate: Double?, accelNorm: Double? = null) {
        if (entity == null || gpsLogDeferred == null || stopped || parked) return
        pipeline.fusionEnabled = fusionEnabled()
        pipeline.onMotion(elapsedMs, forwardAccel, yawRate, accelNorm)
    }

    /** 歩いている（0.8.0〜。GPS のまま記録している）。 */
    val isWalking: Boolean get() = pipeline.mode == FixPipeline.Mode.WALK

    /**
     * GPS が来ない間の推測航法（サービスの 4 回/秒から）。最後の測位から 1.5 秒以上たったら 1 秒ごとに推定の点を記録する。
     * 長く続く（加速度ありで 30 秒・なしで 8 秒）か誤差が大きくなったら打ち切る（[GpsImuFusion.estimate] が null）。
     */
    fun deadReckonTick(nowElapsed: Long) {
        if (entity == null || gpsLogDeferred == null || stopped || parked || !fusionEnabled() || lastGpsElapsed == Long.MIN_VALUE) return
        if (nowElapsed - lastGpsElapsed < 1_500 || nowElapsed - lastPointElapsed < 1_000) return
        // 融合しているとき（車の走行）だけ。歩いている・Google の融合測位・融合 OFF なら null
        val f = pipeline.estimate(nowElapsed) ?: return
        val s = Sample(utc(nowElapsed), f.lat, f.lon, f.sigmaM.toFloat(), lastLocation?.takeIf { it.hasAltitude() }?.altitude, f.speedMps.toFloat(), f.headingDeg?.toFloat())
        record(s, TripPointEntity.SOURCE_DEAD_RECKONING, null, nowElapsed)
    }

    /** 停車を始めた（測位を止める）。今の位置のまま、時刻だけ進む点を [holdTick] で残す。 */
    fun enterStop(nowElapsed: Long): Boolean {
        if (entity == null || gpsLogDeferred == null || stopped || parked) return false
        val last = tracker.lastAccepted ?: return false
        stopped = true
        holdLat = last.lat
        holdLon = last.lon
        holdAlt = last.altitudeM
        pipeline.hold(nowElapsed, holdLat, holdLon)
        holdTick(nowElapsed, force = true)
        publish()
        return true
    }

    /** 停車中の点（2 秒ごと。同じ位置・速さ 0。距離は増えず停車時間に入る）。 */
    fun holdTick(nowElapsed: Long, force: Boolean = false) {
        if (!stopped || entity == null || gpsLogDeferred == null) return
        if (!force && nowElapsed - lastPointElapsed < HOLD_INTERVAL_MS) return
        record(Sample(utc(nowElapsed), holdLat, holdLon, HOLD_ACCURACY_M, holdAlt, 0f, null), TripPointEntity.SOURCE_STOPPED, null, nowElapsed)
    }

    /** 動き出した。直前の停車の位置からの移動として続ける（融合も停車の位置から）。 */
    fun exitStop(nowElapsed: Long) {
        if (!stopped) return
        holdTick(nowElapsed, force = true)
        stopped = false
        pipeline.hold(nowElapsed, holdLat, holdLon)
        lastGpsElapsed = nowElapsed
        publish()
    }

    /** 停車中の確かめの測位 [l] が、停車の位置から動いている（30 m 以上・2 m/s 以上）。 */
    fun movedFromHold(l: Location): Boolean {
        if (!stopped) return true
        val d = jp.kjtr.drivelogger.geo.Geo.distanceM(holdLat, holdLon, l.latitude, l.longitude)
        val acc = if (l.hasAccuracy()) l.accuracy else 30f
        return (l.hasSpeed() && l.speed >= 2f && d > acc) || d > maxOf(30.0, acc * 1.5)
    }

    private fun utc(elapsed: Long): Long = elapsed + (utcOffsetMs ?: (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()))

    /** [provider] = 測位の出どころ（推測・停車中の点は不明）、[altAccuracy] = 高度の精度（0.8.0〜。獲得標高で使う）。 */
    private fun record(
        s: Sample, source: Int, raw: Sample?, elapsed: Long,
        provider: Int = TripPointEntity.PROVIDER_UNKNOWN, altAccuracy: Float? = null,
    ) {
        val e = entity ?: return
        val log = gpsLogDeferred ?: return
        when (val r = tracker.feed(s)) {
            is DriveTracker.Result.Rejected -> publish()
            is DriveTracker.Result.Accepted -> {
                lastPointElapsed = elapsed
                lastSource = source
                if (provider != TripPointEntity.PROVIDER_UNKNOWN) lastProvider = provider
                gpsLogPoints++
                if (r.newSegment && segmentHasPoints) {
                    segment++
                    segmentHasPoints = false
                }
                val seg = segment
                val newSeg = !segmentHasPoints
                segmentHasPoints = true
                val p = TripPointEntity(
                    tripId = e.id, segment = seg, time = s.timeMs, lat = s.lat, lon = s.lon, ele = s.altitudeM,
                    accuracy = s.accuracyM, deviceSpeed = s.speedMps, speed = r.smoothedSpeedMps.toFloat(),
                    bearing = tracker.courseDeg?.toFloat(),
                    rawLat = raw?.lat, rawLon = raw?.lon, rawAccuracy = raw?.accuracyM, rawSpeed = raw?.speedMps,
                    source = source, altAccuracy = altAccuracy, provider = provider,
                )
                writes.trySend { dao.insertPoint(p.copy(logId = log.await())) }
                // 記録中の線: なめらかにする設定なら前向きのフィルタだけ（遅れが出にくい）。保存する点はそのまま
                if (newSeg) liveSmoother?.reset()
                val ll = if (smoothingEnabled()) {
                    val f = liveSmoother ?: jp.kjtr.drivelogger.geo.TrackSmoother.Forward(e.vehicleKind.smoothConfig).also { liveSmoother = it }
                    f.add(jp.kjtr.drivelogger.geo.TrackSmoother.In(s.timeMs, s.lat, s.lon, s.accuracyM))
                } else LatLon(s.lat, s.lon)
                val cur = _line.value
                _line.value = if (newSeg || cur.isEmpty()) cur + listOf(listOf(ll)) else cur.dropLast(1) + listOf(cur.last() + ll)
                if (++sinceSave >= 10) {
                    sinceSave = 0
                    saveEntity()
                }
                publish()
            }
        }
    }

    /** 最後に届いた位置（出来事に位置を付ける）。 */
    fun lastLatLon(): LatLon? = (tracker.lastAccepted?.let { LatLon(it.lat, it.lon) })
        ?: lastLocation?.let { LatLon(it.latitude, it.longitude) }

    /**
     * 続く出来事（位置情報 OFF・位置が届かない）が始まった・終わった（メインスレッドから呼ぶ）。
     */
    fun setCondition(type: String, active: Boolean, at: Long = System.currentTimeMillis()) {
        val e = entity ?: return
        if (active) {
            if (!openConditions.add(type)) return
            val ll = lastLatLon()
            writes.trySend { dao.insertEvent(TripEventEntity(tripId = e.id, type = type, startedAt = at, lat = ll?.lat, lon = ll?.lon)) }
        } else {
            if (!openConditions.remove(type)) return
            writes.trySend { dao.endEvents(e.id, type, at) }
        }
    }

    /** 1 回きりの出来事（衝撃・保護・熱など）。位置は最後の点。 */
    fun addEvent(type: String, at: Long = System.currentTimeMillis(), value: Double? = null) {
        val e = entity ?: return
        val ll = lastLatLon()
        writes.trySend { dao.insertEvent(TripEventEntity(tripId = e.id, type = type, startedAt = at, endedAt = at, lat = ll?.lat, lon = ll?.lon, value = value)) }
    }

    /** 書き込みのキューを流し切る。 */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.trySend { done.complete(Unit) }
        done.await()
    }

    /**
     * ドライブを閉じる（［ドライブを終了］。記録中のログ・駐車も閉じる）。保存したドライブの id（何も記録が無ければ消して null）。
     * 録画は呼ぶ側が先に止める。
     */
    suspend fun close(): Long? {
        val e = withContext(Dispatchers.Main) {
            val now = System.currentTimeMillis()
            if (parked) closePark(now)
            stopGpsLog(now)
            endVideoLog(now)
            openConditions.toList().forEach { setCondition(it, false) }
            val e = entity ?: return@withContext null
            saveEntity()
            e
        } ?: return null
        flush()
        val done = repo.closeDrive(e.id)
        withContext(Dispatchers.Main) {
            resetPositioning()
            resetLogs()
            resetParking()
            entity = null
            _state.value = null
            _line.value = emptyList()
        }
        return done?.id
    }

    /** 開いているドライブを手放す（DB はそのまま。別のドライブを再開する前などに使う）。 */
    suspend fun detach() {
        withContext(Dispatchers.Main) {
            resetPositioning()
            resetLogs()
            resetParking()
            entity = null
            _state.value = null
            _line.value = emptyList()
        }
    }

    private fun withStats(e: TripEntity) = e.copy(
        distanceM = tracker.distanceM,
        movingMs = tracker.movingMs,
        stillMs = tracker.stillMs,
        maxSpeedMps = tracker.maxSpeedMps,
        pointCount = _line.value.sumOf { it.size },
        updatedAt = System.currentTimeMillis(),
    )

    private fun saveEntity() {
        val e = entity ?: return
        val snapshot = withStats(e)
        writes.trySend { dao.updateTrip(snapshot) }
    }

    private fun publish() {
        val e = entity ?: run { _state.value = null; return }
        val gps = gpsLogDeferred != null
        _state.value = DriveState(
            tripId = e.id,
            name = e.name,
            startedAt = e.startedAt,
            distanceM = tracker.distanceM,
            movingMs = tracker.movingMs,
            stillMs = tracker.stillMs,
            maxSpeedMps = tracker.maxSpeedMps,
            averageSpeedMps = tracker.averageSpeedMps,
            currentSpeedMps = if (gps) tracker.currentSpeedMps else 0.0,
            courseDeg = tracker.courseDeg,
            autoPaused = tracker.autoPaused,
            pointCount = _line.value.sumOf { it.size },
            lastFixAt = if (gps) tracker.lastAccepted?.timeMs else null,
            lat = if (gps) tracker.lastAccepted?.lat else null,
            lon = if (gps) tracker.lastAccepted?.lon else null,
            stopped = stopped,
            source = lastSource,
            walking = gps && pipeline.mode == FixPipeline.Mode.WALK,
            fusedProvider = gps && lastProvider == TripPointEntity.PROVIDER_FUSED,
            gpsLogging = gps,
            gpsLogStartedAt = if (gps) gpsLogStartedAt else null,
            gpsLogDistanceM = if (gps) (tracker.distanceM - gpsLogStartDistance).coerceAtLeast(0.0) else 0.0,
            gpsLogCount = gpsLogCount,
            videoLogging = videoLogDeferred != null,
            videoLogCount = videoLogCount,
            parked = parked,
            parkId = parkId,
            parkStartedAt = if (parked) parkStartedAt else null,
            parkLat = if (parked) parkLat else null,
            parkLon = if (parked) parkLon else null,
            walkDistanceM = if (parked) walkM else 0.0,
            parkCount = parkCount,
            parkedMs = parkedMsClosed,
        )
    }

    private fun resetPositioning() {
        pipeline.reset()
        lastProvider = TripPointEntity.PROVIDER_UNKNOWN
        stopped = false
        utcOffsetMs = null
        lastGpsElapsed = Long.MIN_VALUE
        lastPointElapsed = Long.MIN_VALUE
        lastSource = TripPointEntity.SOURCE_GPS
    }

    companion object {
        const val HOLD_INTERVAL_MS = 2_000L
        const val HOLD_ACCURACY_M = 5f
        /** 徒歩の点: 精度の上限と、前の点から残す距離 [m]。 */
        const val WALK_MAX_ACCURACY_M = 50f
        const val WALK_MIN_STEP_M = 10.0
    }
}
