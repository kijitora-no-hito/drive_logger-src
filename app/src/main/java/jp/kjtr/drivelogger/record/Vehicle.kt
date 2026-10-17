package jp.kjtr.drivelogger.record

/**
 * 乗り物（0.8.0〜 設定「乗り物」。既定 車）。感度・融合・軌跡のなめらかさを乗り物に合わせる（DESIGN.md §4.8）。
 *
 * - [impactScale] / [impactMinMs] / [impactVibrationK]: 衝撃の検知（しきい値の倍率・続く時間・振動に合わせて足す倍率）。
 *   自転車は段差の振動が大きいので、しきい値 1.6 倍・60 ms 以上続く（車は 40 ms）。どちらも直近の振動の 2 倍を足す。徒歩は検知しない。
 * - [imuFusion]: GPS と加速度・ジャイロの融合（車向けのモデル）を使う。自転車・徒歩は使わない（端末の向き・揺れが車と違う）。
 * - [smoothAccel]: 軌跡のなめらかさ（[jp.kjtr.drivelogger.geo.TrackSmoother] の加速度の雑音 [m/s²]。小さいほどなめらか、大きいほど角に追従）。
 * - [maxSpeedMps]: なめらかにするときの外れ値（これより速く跳んだ点は使わない）。
 */
enum class Vehicle(
    val impactScale: Double,
    val impactMinMs: Long,
    val impactVibrationK: Double,
    val impactEnabled: Boolean,
    val imuFusion: Boolean,
    val smoothAccel: Double,
    val maxSpeedMps: Double,
) {
    CAR(1.0, 40, 2.0, true, true, 2.0, 70.0),
    BICYCLE(1.6, 60, 2.0, true, false, 1.2, 25.0),
    WALK(1.0, 40, 2.0, false, false, 0.6, 8.0),
    ;

    /** 軌跡をなめらかにする設定。 */
    val smoothConfig: jp.kjtr.drivelogger.geo.SmoothConfig get() = jp.kjtr.drivelogger.geo.SmoothConfig(accel = smoothAccel, maxSpeedMps = maxSpeedMps)

    companion object {
        /** DB の値（trip.vehicle＝順番）から。知らない値は車。 */
        fun of(code: Int): Vehicle = entries.getOrNull(code) ?: CAR
    }
}
