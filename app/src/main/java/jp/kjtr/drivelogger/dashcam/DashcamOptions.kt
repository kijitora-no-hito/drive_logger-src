package jp.kjtr.drivelogger.dashcam

/** 録画の画質。[bitrateBps] は 30fps のときのビットレート（60fps は 1.5 倍）。容量の見積もりに使うので固定で指定する。 */
enum class VideoQuality(val label: String, val shortSide: Int, val bitrateBps: Int) {
    HD("720p", 720, 6_000_000),
    FHD("1080p", 1080, 12_000_000),
    UHD("4K", 2160, 35_000_000),
    ;

    fun bitrateFor(fps: Int): Int = if (fps >= 60) bitrateBps * 3 / 2 else bitrateBps
}

/** 焼き込みの位置。 */
enum class BurnInPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** 焼き込みの文字の大きさ（短い辺に対する 1 行の高さの割合）。地図の窓の大きさ（短い辺に対する一辺の割合）にも使う。 */
enum class BurnInSize(val lineRatio: Float, val mapRatio: Float) {
    SMALL(0.030f, 0.28f),
    MEDIUM(0.040f, 0.36f),
    LARGE(0.055f, 0.46f),
}

/** 衝撃の感度。[thresholdG] = これ以上の衝撃（重力を除いた加速度の大きさ）で保護する。 */
enum class ImpactSensitivity(val thresholdG: Double?) {
    OFF(null),
    LOW(2.5),
    MEDIUM(1.8),
    HIGH(1.2),
}

/** 1 ファイルの見積もりの大きさ [byte]（ビットレート × 長さ × 1.1。音声は 128 kbps）。 */
fun estimateSegmentBytes(quality: VideoQuality, fps: Int, segmentMs: Long, audio: Boolean): Long {
    val bps = quality.bitrateFor(fps).toLong() + if (audio) 128_000L else 0L
    return (bps / 8.0 * (segmentMs / 1000.0) * 1.1).toLong()
}
