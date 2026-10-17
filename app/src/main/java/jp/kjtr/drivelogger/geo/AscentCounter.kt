package jp.kjtr.drivelogger.geo

/**
 * 獲得標高（登りの合計）。標高を指数移動平均で平滑化し、直近の底から [thresholdM] 以上
 * 上がったときだけその分を足す（GPS 高度の数 m の揺れを登りとして数えない）。
 *
 * [alpha] = 1 なら平滑化なし（GPX ルートの DEM 由来の標高など、もともと滑らかな値向け）。
 */
class AscentCounter(
    private val thresholdM: Double = DEFAULT_THRESHOLD_M,
    private val alpha: Double = DEFAULT_ALPHA,
) {
    var ascentM: Double = 0.0
        private set

    private var smoothed: Double? = null
    private var ref: Double? = null

    fun add(elevationM: Double) {
        if (elevationM.isNaN() || elevationM.isInfinite()) return
        val prev = smoothed
        val s = if (prev == null) elevationM else prev + alpha * (elevationM - prev)
        smoothed = s
        val r = ref
        if (r == null) {
            ref = s
            return
        }
        if (s - r >= thresholdM) {
            ascentM += s - r
            ref = s
        } else if (s < r) {
            ref = s
        }
    }

    /** 区間の切れ目（一時停止の後など）。合計は残し、平滑化と基準だけやり直す。 */
    fun breakSegment() {
        smoothed = null
        ref = null
    }

    companion object {
        const val DEFAULT_THRESHOLD_M = 4.0
        const val DEFAULT_ALPHA = 0.3

        /** GPX ルートの獲得標高（区間ごとに数えて合計）。 */
        fun forRoute(segments: List<List<Double?>>): Double {
            val c = AscentCounter(thresholdM = 3.0, alpha = 0.5)
            for (seg in segments) {
                c.breakSegment()
                for (e in seg) if (e != null) c.add(e)
            }
            return c.ascentM
        }
    }
}
