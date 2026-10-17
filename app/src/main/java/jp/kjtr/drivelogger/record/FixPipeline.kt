package jp.kjtr.drivelogger.record

import jp.kjtr.drivelogger.data.TripPointEntity

/**
 * 測位 1 回をどう記録するか（純ロジック。単体テスト: WalkFusionTest）。0.8.0〜 DESIGN.md §4.7。
 *
 * - [Mode.FUSED]: 車の走行。0.3.0 からの GPS と加速度・ジャイロの融合（[GpsImuFusion]）。GPS が来ない間は推測航法。
 * - [Mode.WALK]: 歩いている（[WalkDetector]＝10 km/h 未満で歩行の周期的な揺れ）。**加速度による推測をせず、測位をそのまま**記録する
 *   （端末の向きが歩くたびに変わり、重力が「前後の加速度」に漏れて推測航法が暴れるため）。推測航法の点も出さない。
 * - [Mode.RAW]: 設定で融合 OFF。
 * - [Mode.EXTERNAL]: Google の融合測位（FLP）を使っている。FLP がセンサーと融合済みなので、自前の融合を重ねない（二重にしない）。
 *
 * 融合から外れたら融合の状態は捨て、戻ったら次の GPS からやり直す。
 */
class FixPipeline(
    private val fusion: GpsImuFusion = GpsImuFusion(),
    val walk: WalkDetector = WalkDetector(),
) {
    enum class Mode { FUSED, WALK, RAW, EXTERNAL }

    /** 記録する点（[raw] は融合したときの元の測位）。 */
    data class Out(val sample: Sample, val source: Int, val raw: Sample?)

    var fusionEnabled = true
    var externalFused = false
    private var lastMode: Mode? = null

    val mode: Mode get() = when {
        externalFused -> Mode.EXTERNAL
        !fusionEnabled -> Mode.RAW
        walk.walking -> Mode.WALK
        else -> Mode.FUSED
    }

    /** 加速度・ジャイロ（約 50 Hz）。[accelNorm] = 重力を含む加速度の大きさ（歩行の判定）。 */
    fun onMotion(tMs: Long, forwardAccel: Double?, yawRate: Double?, accelNorm: Double?) {
        accelNorm?.let { walk.onAccel(tMs, it) }
        if (sync() == Mode.FUSED) fusion.onImu(tMs, forwardAccel, yawRate)
    }

    /** 測位 1 回（[tMs] = elapsedRealtime の ms）。 */
    fun onFix(tMs: Long, raw: Sample): Out {
        raw.speedMps?.takeIf { it >= 0f }?.let { walk.onSpeed(tMs, it.toDouble()) }
        if (sync() != Mode.FUSED) return Out(raw, TripPointEntity.SOURCE_GPS, null)
        val f = fusion.onGps(tMs, raw.lat, raw.lon, raw.accuracyM, raw.speedMps, raw.bearingDeg)
        val s = raw.copy(lat = f.lat, lon = f.lon, accuracyM = f.sigmaM.toFloat(), speedMps = f.speedMps.toFloat(), bearingDeg = f.headingDeg?.toFloat())
        return Out(s, TripPointEntity.SOURCE_FUSED, raw)
    }

    /** GPS が来ない間の推測（融合しているときだけ）。 */
    fun estimate(tMs: Long): FusedFix? = if (sync() == Mode.FUSED) fusion.estimate(tMs) else null

    /** 停車: 融合しているときは停車の位置に止める。 */
    fun hold(tMs: Long, lat: Double, lon: Double) {
        if (sync() == Mode.FUSED) fusion.hold(tMs, lat, lon)
    }

    /** GPS ログの始め・終わりなど。歩行の判定も初めから。 */
    fun reset() {
        fusion.reset()
        walk.reset()
        lastMode = null
    }

    private fun sync(): Mode {
        val m = mode
        if (m != lastMode) {
            if (m != Mode.FUSED) fusion.reset()
            lastMode = m
        }
        return m
    }
}
