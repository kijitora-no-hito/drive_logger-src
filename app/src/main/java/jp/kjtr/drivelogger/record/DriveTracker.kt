package jp.kjtr.drivelogger.record

import jp.kjtr.drivelogger.geo.Bearing
import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.geo.LatLon
import kotlin.math.max

/** 測位 1 回分（記録・再計算の入力）。 */
data class Sample(
    /** UTC の時刻 [ms]（Location.time）。 */
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    /** 端末が出した速度（GPS のドップラー）[m/s]。無ければ null。 */
    val speedMps: Float? = null,
    /** 端末が出した進行方向 [度]。無ければ null。 */
    val bearingDeg: Float? = null,
)

/** 車向けの既定値（DESIGN.md §4）。 */
data class TrackerConfig(
    /** 信号待ちも走行時間に入れるので既定 OFF。 */
    val autoPause: Boolean = false,
    /** これより精度の悪い点は捨てる [m]。 */
    val maxAccuracyM: Float = 30f,
    /** 前の点からこれより速く跳んだ点は捨てる [m/s]（250 km/h）。 */
    val maxSpeedMps: Double = 250.0 / 3.6,
    /** この速さ未満を「止まっている」とみなす [m/s]（5 km/h）。 */
    val stillSpeedMps: Double = 5.0 / 3.6,
    /** 止まってからこの時間で自動一時停止にする [ms]（ON のとき。信号待ちを考えて長め）。 */
    val autoPauseDelayMs: Long = 60_000,
    /** 距離はこの長さ以上動いたときにまとめて足す（止まっているときの GPS の揺れを数えない）[m]。 */
    val minStepM: Double = 5.0,
    /** 跳びとして捨てた点がこれだけ続いたら、そこから測り直す（最初の点が外れ値だったとき用）。 */
    val jumpResetCount: Int = 5,
)

/**
 * 走行時間・距離に数えた 1 回分（点と点の間）。[DriveTracker.onCounted] に、数えた順に渡す。
 * すべての [dtMs] の和が [DriveTracker.movingMs]、[distM] の和が [DriveTracker.distanceM] にちょうど一致する。
 */
data class CountedStep(
    val timeMs: Long,
    val dtMs: Long,
    val distM: Double,
    /** この点での速さ（直近 3 回の中央値）[m/s]。 */
    val speedMps: Double,
)

/**
 * 走行中の統計（純ロジック。単体テスト: DriveTrackerTest）。自転車ルートロガーの RideTracker を車向けにしたもの。
 *
 * - 精度 [TrackerConfig.maxAccuracyM] 超・前の点から 250 km/h 超の跳び・時刻が戻った点は捨てる。
 * - 距離: 採用点（anchor）から [TrackerConfig.minStepM] 以上動いたら足して anchor を進める。
 * - 速さ: 端末の速度、無ければ 5 秒前の点からの移動距離÷時間。直近 3 回の中央値。
 * - 停車時間 [stillMs]: 数えた時間のうち、速さが 5 km/h 未満だった分（自動一時停止 OFF でも数える）。
 * - 自動一時停止（ON のとき）: 5 km/h 未満のまま 60 秒たったら、止まり始めからの時間と距離を数えない。
 * - 進行方向 [courseDeg]: 端末の方位（動いているとき）、無ければ 10 m 以上離れた前の点からの方位。
 *
 * 保存した点（採用された点だけ）を同じ順に流し直すと同じ統計になる（落ちたときの再開・詳細の区間）。
 */
class DriveTracker(private val config: TrackerConfig = TrackerConfig()) {

    enum class Reject { INACCURATE, DUPLICATE, JUMP }

    sealed interface Result {
        data class Accepted(val newSegment: Boolean, val smoothedSpeedMps: Double) : Result
        data class Rejected(val reason: Reject) : Result
    }

    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var stillMs = 0L
        private set
    var maxSpeedMps = 0.0
        private set
    var currentSpeedMps = 0.0
        private set
    var autoPaused = false
        private set
    var acceptedCount = 0
        private set
    var lastAccepted: Sample? = null
        private set
    /** 進行方向 [度]（分からなければ null）。 */
    var courseDeg: Double? = null
        private set

    var onCounted: ((CountedStep) -> Unit)? = null

    private val pendingSteps = ArrayList<CountedStep>()
    private var anchor: Sample? = null
    private var courseAnchor: Sample? = null
    private var lastMovingAt = 0L
    private var pendingMs = 0L
    private var pendingM = 0.0
    private var jumpStreak = 0
    private val speeds = ArrayDeque<Double>()
    private val recent = ArrayDeque<Sample>()

    fun feed(s: Sample): Result {
        val acc = s.accuracyM
        if (acc != null && acc > config.maxAccuracyM) return Result.Rejected(Reject.INACCURATE)
        val last = lastAccepted
        if (last == null) {
            startFrom(s)
            return Result.Accepted(newSegment = true, smoothedSpeedMps = 0.0)
        }
        val dtMs = s.timeMs - last.timeMs
        if (dtMs <= 0) return Result.Rejected(Reject.DUPLICATE)
        val d = Geo.distanceM(last.lat, last.lon, s.lat, s.lon)
        val v = d / (dtMs / 1000.0)
        if (v > config.maxSpeedMps) {
            jumpStreak++
            if (jumpStreak >= config.jumpResetCount) {
                startFrom(s)
                return Result.Accepted(newSegment = true, smoothedSpeedMps = 0.0)
            }
            return Result.Rejected(Reject.JUMP)
        }
        jumpStreak = 0
        acceptedCount++

        recent.addLast(s)
        while (recent.size > 2 && s.timeMs - recent[1].timeMs >= SPEED_BASELINE_MS) recent.removeFirst()
        val base = recent.first()
        val windowed = if (base !== s && s.timeMs > base.timeMs) {
            Geo.distanceM(base.lat, base.lon, s.lat, s.lon) / ((s.timeMs - base.timeMs) / 1000.0)
        } else {
            v
        }
        val device = s.speedMps?.toDouble()?.takeIf { it >= 0.0 && it <= config.maxSpeedMps }
        // 端末が 0 と言っていても、5 秒で 10 m 以上動いていれば位置の方を信じる（エミュレータの geo fix など）
        val inst = if (device == null || (device < config.stillSpeedMps && windowed >= BOGUS_ZERO_MPS)) windowed else device
        speeds.addLast(inst)
        while (speeds.size > SPEED_WINDOW) speeds.removeFirst()
        currentSpeedMps = median(speeds)
        val moving = currentSpeedMps >= config.stillSpeedMps
        updateCourse(s)

        val listener = onCounted
        if (!config.autoPause) {
            movingMs += dtMs
            if (!moving) stillMs += dtMs
            val step = takeStep(s)
            distanceM += step
            if (moving) maxSpeedMps = max(maxSpeedMps, currentSpeedMps)
            listener?.invoke(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
        } else if (moving) {
            if (autoPaused) {
                autoPaused = false
                movingMs += dtMs
            } else {
                movingMs += pendingMs + dtMs
                stillMs += pendingMs
                distanceM += pendingM
                if (listener != null) pendingSteps.forEach(listener)
            }
            pendingSteps.clear()
            val step = takeStep(s)
            distanceM += step
            pendingMs = 0
            pendingM = 0.0
            lastMovingAt = s.timeMs
            maxSpeedMps = max(maxSpeedMps, currentSpeedMps)
            listener?.invoke(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
        } else if (!autoPaused) {
            pendingMs += dtMs
            val step = takeStep(s)
            pendingM += step
            if (listener != null) pendingSteps.add(CountedStep(s.timeMs, dtMs, step, currentSpeedMps))
            if (s.timeMs - lastMovingAt >= config.autoPauseDelayMs) {
                autoPaused = true
                pendingMs = 0
                pendingM = 0.0
                pendingSteps.clear()
            }
        }
        lastAccepted = s
        return Result.Accepted(newSegment = false, smoothedSpeedMps = currentSpeedMps)
    }

    private fun updateCourse(s: Sample) {
        val dev = s.bearingDeg
        // 端末の速度が 0 なのに方位 0 を返すもの（エミュレータの geo fix など）は使わない
        val devSpeedOk = s.speedMps == null || s.speedMps >= COURSE_MIN_SPEED_MPS
        if (dev != null && !dev.isNaN() && devSpeedOk && currentSpeedMps >= COURSE_MIN_SPEED_MPS) {
            courseDeg = Bearing.normalize(dev.toDouble())
            courseAnchor = s
            return
        }
        val a = courseAnchor ?: run { courseAnchor = s; return }
        if (Geo.distanceM(a.lat, a.lon, s.lat, s.lon) >= COURSE_MIN_STEP_M) {
            courseDeg = Bearing.initialDeg(LatLon(a.lat, a.lon), LatLon(s.lat, s.lon))
            courseAnchor = s
        }
    }

    private fun takeStep(s: Sample): Double {
        val a = anchor ?: run { anchor = s; return 0.0 }
        val step = Geo.distanceM(a.lat, a.lon, s.lat, s.lon)
        if (step < config.minStepM) return 0.0
        anchor = s
        return step
    }

    private fun startFrom(s: Sample) {
        lastAccepted = s
        anchor = s
        courseAnchor = s
        lastMovingAt = s.timeMs
        pendingMs = 0
        pendingM = 0.0
        pendingSteps.clear()
        jumpStreak = 0
        autoPaused = false
        speeds.clear()
        recent.clear()
        recent.addLast(s)
        currentSpeedMps = 0.0
        acceptedCount++
    }

    /** 区間の切れ目（落ちて再開した後など）。次の点から新しい区間として測る。 */
    fun breakSegment() {
        lastAccepted = null
        anchor = null
        courseAnchor = null
        pendingMs = 0
        pendingM = 0.0
        pendingSteps.clear()
        jumpStreak = 0
        autoPaused = false
        speeds.clear()
        recent.clear()
        currentSpeedMps = 0.0
    }

    /** 最後の点から時間がたって速さが分からないとき（表示用）。 */
    fun speedAt(nowMs: Long): Double {
        val last = lastAccepted ?: return 0.0
        return if (nowMs - last.timeMs > STALE_MS) 0.0 else currentSpeedMps
    }

    val averageSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    /** 停車を除いた平均 [m/s]。 */
    val averageRunningSpeedMps: Double get() {
        val run = movingMs - stillMs
        return if (run > 0) distanceM / (run / 1000.0) else 0.0
    }

    companion object {
        const val SPEED_WINDOW = 3
        const val SPEED_BASELINE_MS = 5_000L
        const val STALE_MS = 5_000L
        /** 端末の速度が 0 でも、位置からの速さがこれ以上なら動いているとみなす [m/s]（7.2 km/h）。 */
        const val BOGUS_ZERO_MPS = 2.0
        /** 端末の方位を進行方向として信じる速さ [m/s]（7.2 km/h）。 */
        const val COURSE_MIN_SPEED_MPS = 2.0
        /** 位置から進行方向を求めるときの最短の距離 [m]。 */
        const val COURSE_MIN_STEP_M = 10.0

        fun median(values: Collection<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}
