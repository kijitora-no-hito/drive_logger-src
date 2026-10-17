package jp.kjtr.drivelogger.stats

import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.geo.Geo

/**
 * 獲得標高（登りの合計。純ロジック。単体テスト: ElevationGainTest）。0.8.0〜 まとめ・ドライブの詳細はすべてこれで数える。
 * 記録には生の高度を残し、表示のたびに計算する（古い記録も新しい数え方で出る）。
 *
 * 1. 使う点: 高度があり、測位の点（GPS / 融合。推測航法・停車中の点は除く）で、ネットワーク位置ではなく、
 *    高度の精度（verticalAccuracy）が [MAX_ALT_ACCURACY_M] 以内。高度の精度が無い点（0.7.0 までの記録など）は水平の精度が [MAX_H_ACCURACY_M] 以内。
 * 2. ならし: 区間ごとに、前後 2 点ずつの中央値（跳びを消す）→ 前後 [HALF_WINDOW_MS] かつ走った距離で前後 [HALF_WINDOW_M] の移動平均
 *    （歩く速さでは時間で、車の速さでは距離で窓が決まる。車の短い坂をならしすぎない）。
 * 3. 数え方（ヒステリシス）: 直近の底から [THRESHOLD_M] 以上上がったときだけその分を足し、そこを新しい基準にする。下がったら底を下げる。
 *    GPS の高度の数 m の揺れ（ならした後も残る数分のゆっくりしたずれ）は数えない。10 m 未満の小さな上り下りも数えない。
 *    （合成データ: 雑音 σ 5 m＋ずれ σ 4 m の平地 1 時間で平均 14 m（0.7.0 の数え方は 1400 m）、100 m の坂で 100〜110 m。ElevationGainTest）
 *
 * 限界: GPS の高度は水平より 1.5〜2 倍悪く、数分かけてゆっくり 10 m 前後ずれることがある。平地でも数 m〜十数 m 出ることがある（目安）。
 */
object ElevationGain {
    const val THRESHOLD_M = 10.0
    const val HALF_WINDOW_MS = 60_000L
    const val HALF_WINDOW_M = 300.0
    const val MAX_ALT_ACCURACY_M = 15f
    const val MAX_H_ACCURACY_M = 20f

    /** 高度の 1 点（[usable] = 獲得標高に使える）。 */
    data class P(val timeMs: Long, val ele: Double, val distM: Double = 0.0)

    /** 点（id の順）から獲得標高 [m]。区間・ログの境目では数え直す（間の上り下りは数えない）。 */
    fun of(points: List<TripPointEntity>): Double = TripRepository.segmentsOf(points).sumOf { seg ->
        // 区間の初めからの距離（使わない点の分も含めて）
        var d = 0.0
        val out = ArrayList<P>()
        for (i in seg.indices) {
            if (i > 0) d += Geo.distanceM(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
            val p = seg[i]
            if (usable(p)) out.add(P(p.time, p.ele!!, d))
        }
        ofSegment(out)
    }

    /** 獲得標高に使える点か。 */
    fun usable(p: TripPointEntity): Boolean {
        val e = p.ele ?: return false
        if (e.isNaN() || e.isInfinite()) return false
        if (p.source != TripPointEntity.SOURCE_GPS && p.source != TripPointEntity.SOURCE_FUSED) return false
        if (p.provider == TripPointEntity.PROVIDER_NETWORK) return false
        val v = p.altAccuracy
        if (v != null) return v <= MAX_ALT_ACCURACY_M
        // 高度の精度が無い（0.7.0 までの点）: 水平の精度で代える（融合した点は元の GPS の精度）
        val h = (if (p.source == TripPointEntity.SOURCE_FUSED) p.rawAccuracy else p.accuracy) ?: return true
        return h <= MAX_H_ACCURACY_M
    }

    /** 1 区間（時刻の順）の獲得標高。 */
    fun ofSegment(points: List<P>, threshold: Double = THRESHOLD_M, halfWindowMs: Long = HALF_WINDOW_MS): Double {
        if (points.size < 2) return 0.0
        val smooth = smooth(points, halfWindowMs)
        var gain = 0.0
        var ref = smooth[0]
        for (i in 1 until smooth.size) {
            val s = smooth[i]
            if (s - ref >= threshold) {
                gain += s - ref
                ref = s
            } else if (s < ref) {
                ref = s
            }
        }
        return gain
    }

    /** 中央値（前後 2 点）→ 時間の移動平均（前後 [HALF_WINDOW_MS]）。 */
    fun smooth(points: List<P>, halfWindowMs: Long = HALF_WINDOW_MS): DoubleArray {
        val n = points.size
        val med = DoubleArray(n) { i ->
            val a = maxOf(0, i - 2)
            val b = minOf(n - 1, i + 2)
            val w = (a..b).map { points[it].ele }.sorted()
            w[w.size / 2]
        }
        // 時間の窓の移動平均（2 つの添字を進める）
        val out = DoubleArray(n)
        var lo = 0
        var hi = -1
        var sum = 0.0
        for (i in 0 until n) {
            val t = points[i].timeMs
            val d = points[i].distM
            // 時刻も距離も増えるだけなので、窓は連続した範囲
            while (hi + 1 < n && points[hi + 1].timeMs <= t + halfWindowMs && points[hi + 1].distM <= d + HALF_WINDOW_M) { hi++; sum += med[hi] }
            while (points[lo].timeMs < t - halfWindowMs || points[lo].distM < d - HALF_WINDOW_M) { sum -= med[lo]; lo++ }
            out[i] = sum / (hi - lo + 1)
        }
        return out
    }
}
