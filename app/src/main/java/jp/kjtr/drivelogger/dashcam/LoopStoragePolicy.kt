package jp.kjtr.drivelogger.dashcam

/**
 * 容量の判定に使う動画 1 本（[active] = 録画中のファイル）。
 * [longRec] = 長時間録画のファイル（0.7.0〜）。ループの上限の計算に入れず、消さない（[LoopStoragePolicy]）。
 */
data class StoredClip(
    val id: Long,
    val startedAt: Long,
    val sizeBytes: Long,
    val protected: Boolean,
    val active: Boolean = false,
    val longRec: Boolean = false,
)

/** 判定の結果。 */
data class StoragePlan(
    /** 消すもの（古い順）。 */
    val delete: List<StoredClip>,
    /** 保護の合計が上限を超えている。 */
    val protectedOverLimit: Boolean,
    /** 消しても端末の空きが少ない（次のファイル＋余裕を下回る）。 */
    val lowDeviceSpace: Boolean,
    /** 消せる物を消しても、次のファイルが入らない（録画を止める）。 */
    val cannotRecord: Boolean,
    /** 消した後の通常（保護していない）ファイルの合計。 */
    val normalBytesAfter: Long,
    val protectedBytes: Long,
)

/**
 * ループ録画の容量の管理（純ロジック。単体テスト: LoopStoragePolicyTest。DESIGN.md §3.4）。
 *
 * - 上限 [limitBytes] のうち、通常のファイルに使える量 = limit − min(保護の合計, [protectedLimitBytes])。
 *   次のファイルの見積もり [nextBytes] を足して超えるなら、保護されていない古いファイルから消す。
 * - 録画中（[StoredClip.active]）と保護したファイルは消さない。
 * - 保護の合計が上限を超えても保護したものは消さず、[StoragePlan.protectedOverLimit] で知らせるだけ。
 * - 端末の空き [freeBytes] が「次のファイル＋[minFreeBytes]」を下回るときも古い通常ファイルから消す。
 * - 長時間録画のファイル（[StoredClip.longRec]、0.7.0〜）は**はじめから数に入れない**: 上限 L・保護の合計に入れず、消す候補にもしない
 *   （長時間録画が何 GB あっても、ループの動画を余分に消さない）。端末の空きにだけは（実際に場所を取るので）効く。
 */
object LoopStoragePolicy {

    fun plan(
        all: List<StoredClip>,
        limitBytes: Long,
        protectedLimitBytes: Long,
        nextBytes: Long,
        freeBytes: Long,
        minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    ): StoragePlan {
        val clips = all.filter { !it.longRec }
        val protectedBytes = clips.filter { it.protected }.sumOf { it.sizeBytes }
        val normal = clips.filter { !it.protected }
        var normalBytes = normal.sumOf { it.sizeBytes }
        val budget = (limitBytes - minOf(protectedBytes, protectedLimitBytes)).coerceAtLeast(0)
        var free = freeBytes
        val deletable = normal.filter { !it.active }.sortedWith(compareBy<StoredClip> { it.startedAt }.thenBy { it.id })
        val delete = ArrayList<StoredClip>()
        for (c in deletable) {
            val overBudget = normalBytes + nextBytes > budget
            val lowFree = free < nextBytes + minFreeBytes
            if (!overBudget && !lowFree) break
            delete.add(c)
            normalBytes -= c.sizeBytes
            free += c.sizeBytes
        }
        return StoragePlan(
            delete = delete,
            protectedOverLimit = protectedBytes > protectedLimitBytes,
            lowDeviceSpace = free < nextBytes + minFreeBytes,
            cannotRecord = free < nextBytes,
            normalBytesAfter = normalBytes,
            protectedBytes = protectedBytes,
        )
    }

    /** 端末に残しておく空き（1 GB）。 */
    const val DEFAULT_MIN_FREE_BYTES = 1L shl 30
}
