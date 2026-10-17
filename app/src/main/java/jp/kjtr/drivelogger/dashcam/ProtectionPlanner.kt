package jp.kjtr.drivelogger.dashcam

/** 保護の対象（今のファイル・1 つ前・次のファイル）。 */
data class ProtectTargets(val current: Boolean, val previous: Boolean, val next: Boolean)

/**
 * 保護する範囲（純ロジック。単体テスト: ProtectionPlannerTest。DESIGN.md §3.5）。
 *
 * 出来事の前後それぞれ [marginMs]（既定 30 秒）以上を残す: 今のファイルと 1 つ前は必ず、
 * 今のファイルの残り（予定の終わりまで）が [marginMs] 未満なら次のファイルも保護する。
 * 録画中のファイルが無い（切り替えの間）ときは、1 つ前（いま仕上がったファイル）と次のファイルを保護する。
 */
object ProtectionPlanner {
    const val DEFAULT_MARGIN_MS = 30_000L

    fun targets(eventAt: Long, currentStartedAt: Long?, segmentMs: Long, marginMs: Long = DEFAULT_MARGIN_MS): ProtectTargets {
        if (currentStartedAt == null) return ProtectTargets(current = false, previous = true, next = true)
        val plannedEnd = currentStartedAt + segmentMs
        val remaining = plannedEnd - eventAt
        return ProtectTargets(current = true, previous = true, next = remaining < marginMs)
    }
}
