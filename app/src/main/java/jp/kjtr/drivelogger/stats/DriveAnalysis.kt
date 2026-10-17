package jp.kjtr.drivelogger.stats

import jp.kjtr.drivelogger.record.CountedStep
import jp.kjtr.drivelogger.record.DriveTracker
import jp.kjtr.drivelogger.record.Sample
import jp.kjtr.drivelogger.record.TrackerConfig
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** 区間（スプリット）1 つ。[startM]〜[endM] はトリップの始めからの走行距離 [m]。 */
data class Split(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double,
    /** 最後の端数の区間。 */
    val partial: Boolean,
) {
    val distanceM: Double get() = endM - startM
    val avgSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
}

/** 速度の bin 1 つ。[fromKmh] 以上 [toKmh] 未満。 */
data class SpeedBin(val fromKmh: Double, val toKmh: Double, val timeMs: Long, val distanceM: Double)

data class SpeedHistogram(
    val binKmh: Double,
    val bins: List<SpeedBin>,
    val totalMs: Long,
    val totalM: Double,
) {
    val avgKmh: Double get() = if (totalMs > 0) totalM / (totalMs / 1000.0) * 3.6 else 0.0
}

/**
 * トリップの詳細の区間の表・速度の分布（純ロジック。単体テスト: DriveAnalysisTest）。自転車ルートロガーの RideAnalysis と同じ考え方。
 *
 * 保存した点を [DriveTracker] に流し直し、走行時間・距離に数えた分（[CountedStep]）をそのまま使う。
 * 区間の時間・分布の時間の合計がトリップの走行時間に、区間の距離の合計がトリップの距離にちょうど一致する。
 */
class DriveAnalysis(
    val steps: List<CountedStep>,
    val distanceM: Double,
    val movingMs: Long,
    val stillMs: Long,
    val maxSpeedMps: Double,
) {
    /** [unitM] ごとの区間（5 km なら 5000）。区切りが点と点の間にあるときは距離で線形補間して時間を分ける。 */
    fun splits(unitM: Double): List<Split> {
        require(unitM > 0)
        val out = ArrayList<Split>()
        var d = 0.0
        var t = 0.0
        var startM = 0.0
        var startT = 0.0
        var next = unitM
        var maxV = 0.0
        var any = false

        fun close(endM: Double, endT: Double, partial: Boolean) {
            out.add(Split(out.size, startM, endM, endT.roundToLong() - startT.roundToLong(), maxV, partial))
            startM = endM
            startT = endT
            maxV = 0.0
            any = false
        }

        for (s in steps) {
            var remD = s.distM
            var remT = s.dtMs.toDouble()
            while (remD > 0 && d + remD >= next - EPS_M) {
                val part = (next - d).coerceIn(0.0, remD)
                val partT = remT * (part / remD)
                t += partT
                maxV = max(maxV, s.speedMps)
                close(next, t, partial = false)
                d = next
                remD -= part
                remT -= partT
                next += unitM
            }
            d += remD.coerceAtLeast(0.0)
            t += remT
            if (remD > 0 || remT > 0) {
                maxV = max(maxV, s.speedMps)
                any = true
            }
        }
        if (d - startM > EPS_M) {
            close(d, t, partial = true)
        } else if (any && out.isNotEmpty()) {
            val last = out.removeAt(out.size - 1)
            out.add(last.copy(movingMs = t.roundToLong() - (startT.roundToLong() - last.movingMs), maxSpeedMps = max(last.maxSpeedMps, maxV)))
        }
        return out
    }

    /** 速度の分布（既定 10 km/h ごと）。bin は下端を含み上端を含まない。0 から最高の bin まで並べる。 */
    fun histogram(binKmh: Double = 10.0): SpeedHistogram {
        require(binKmh > 0)
        val time = HashMap<Int, Long>()
        val dist = HashMap<Int, Double>()
        var top = -1
        var totalMs = 0L
        var totalM = 0.0
        for (s in steps) {
            val i = binIndex(s.speedMps * 3.6, binKmh)
            time[i] = (time[i] ?: 0L) + s.dtMs
            dist[i] = (dist[i] ?: 0.0) + s.distM
            top = max(top, i)
            totalMs += s.dtMs
            totalM += s.distM
        }
        val bins = (0..top).map { i -> SpeedBin(i * binKmh, (i + 1) * binKmh, time[i] ?: 0L, dist[i] ?: 0.0) }
        return SpeedHistogram(binKmh, bins, totalMs, totalM)
    }

    companion object {
        private const val EPS_M = 1e-6

        fun binIndex(kmh: Double, binKmh: Double): Int = floor(kmh / binKmh + 1e-9).toInt().coerceAtLeast(0)

        /** 区間ごとの点から作る（区間の間で [DriveTracker.breakSegment]。`TripRepository.replay` と同じ流し方）。 */
        fun from(segments: List<List<Sample>>, autoPause: Boolean): DriveAnalysis {
            val tr = DriveTracker(TrackerConfig(autoPause = autoPause))
            val steps = ArrayList<CountedStep>()
            tr.onCounted = { steps.add(it) }
            segments.forEachIndexed { k, seg ->
                if (k > 0) tr.breakSegment()
                for (s in seg) tr.feed(s)
            }
            return DriveAnalysis(steps, tr.distanceM, tr.movingMs, tr.stillMs, tr.maxSpeedMps)
        }

        fun extremes(splits: List<Split>): Pair<Int, Int>? {
            val full = splits.filter { !it.partial && it.movingMs > 0 }
            if (full.size < 2) return null
            val fast = full.maxBy { it.avgSpeedMps }.index
            val slow = full.minBy { it.avgSpeedMps }.index
            return if (fast == slow) null else fast to slow
        }

        /** 目盛りの間隔（1・2・5 × 10^n）。 */
        fun niceStep(maxValue: Double, maxTicks: Int = 4): Double {
            if (maxValue <= 0) return 1.0
            var mag = 1.0
            while (mag * 10 <= maxValue) mag *= 10
            while (mag > maxValue) mag /= 10
            for (m in listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0)) {
                val step = mag * m
                if (maxValue / step <= maxTicks) return step
            }
            return mag * 10
        }
    }
}
