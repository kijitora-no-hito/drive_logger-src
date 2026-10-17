package jp.kjtr.drivelogger.dashcam

/**
 * 録画の方式（0.7.0〜。DESIGN.md §3.11）。[code] は DB（`clip.recordMode`・`drive_log.recordMode`）に入れる値。
 * - [LOOP]: ループ録画（ドラレコ）。1/3/5 分ごとのファイル、容量の上限を超えたら保護していない古いものから消す。
 * - [LONG]: 長時間録画（消さない）。一定の長さ（既定 10 分）で分けて保存し、自動では消さない。端末の空きが 1 GB を切ったら止める。
 */
enum class RecordMode(val code: Int) {
    LOOP(0),
    LONG(1),
    ;

    companion object {
        fun of(code: Int?): RecordMode = if (code == LONG.code) LONG else LOOP
    }
}

/** 長時間録画の空き容量の様子。 */
data class LongStorageStatus(
    /** 端末の空き [byte]。 */
    val freeBytes: Long,
    /** 止めるまでに録れるおおよその時間 [時間]（空き − 残す量 を 1 時間の見積もりで割る）。 */
    val hoursLeft: Double,
    /** 空きが少ない（残り [LongRecordingPolicy.LOW_HOURS] 時間未満）。画面・通知で警告する。 */
    val low: Boolean,
    /** 空きが残す量（[LongRecordingPolicy.MIN_FREE_BYTES]）を下回った。録画を止める（GPS 記録は続ける）。 */
    val stop: Boolean,
)

/**
 * 長時間録画の空き容量の判定（純ロジック。単体テスト: LongRecordingTest。DESIGN.md §3.11）。
 *
 * - 長時間録画のファイルはループの容量の上限・自動削除の対象外（[LoopStoragePolicy] は [StoredClip.longRec] を数えない・消さない）。
 *   その代わり、端末の空きが [MIN_FREE_BYTES]（1 GB）を下回ったら録画を止める。判定はファイルを始めるたびと 30 秒ごと
 *   （4K 60fps でも 30 秒で約 200 MB なので、1 GB の余裕の中で止まる）。
 * - 長時間録画の間はループの動画も消さない（空きを作るために他の動画を消すことはしない）。
 */
object LongRecordingPolicy {
    /** 端末に残しておく空き（これを下回ったら止める）。 */
    const val MIN_FREE_BYTES = 1L shl 30
    /** これより残りが短いと「空きが少ない」の警告。 */
    const val LOW_HOURS = 0.5
    /** 始めるのに要る空き（残す量＋5 分ぶん）。これより少なければ始めない。 */
    const val START_MARGIN_MS = 5 * 60_000L

    /** 1 時間あたりの見積もり [byte]（ループと同じ見積もり＝ビットレート × 1.1）。 */
    fun bytesPerHour(quality: VideoQuality, fps: Int, audio: Boolean): Long =
        estimateSegmentBytes(quality, fps, 3_600_000L, audio).coerceAtLeast(1)

    /** 空き [freeBytes] で録れるおおよその時間 [時間]（[MIN_FREE_BYTES] を残す）。 */
    fun hoursLeft(freeBytes: Long, bytesPerHour: Long): Double =
        (freeBytes - MIN_FREE_BYTES).coerceAtLeast(0).toDouble() / bytesPerHour.coerceAtLeast(1)

    fun check(freeBytes: Long, bytesPerHour: Long): LongStorageStatus {
        val h = hoursLeft(freeBytes, bytesPerHour)
        return LongStorageStatus(freeBytes = freeBytes, hoursLeft = h, low = h < LOW_HOURS, stop = freeBytes < MIN_FREE_BYTES)
    }

    /** 始められるか（残す量＋[START_MARGIN_MS] ぶんの空きがある）。 */
    fun canStart(freeBytes: Long, bytesPerHour: Long): Boolean =
        freeBytes >= MIN_FREE_BYTES + bytesPerHour * START_MARGIN_MS / 3_600_000L
}
