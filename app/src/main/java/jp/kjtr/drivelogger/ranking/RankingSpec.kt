package jp.kjtr.drivelogger.ranking

import jp.kjtr.drivelogger.stats.CellShapes
import jp.kjtr.drivelogger.stats.DriveSummaries
import jp.kjtr.drivelogger.stats.TimeRange
import jp.kjtr.drivelogger.stats.TripFacts
import java.time.Instant
import java.time.ZonedDateTime

/**
 * ドライブロガーの板（ranking_server の src/apps.ts の `drive` と同じ id）と、手元の記録から送る値の計算（純ロジック。RankingTest）。
 *
 * **ランキングに送るのは [compute] が返す数値だけ**（板ごとの累計と今月の分。位置・経路・マスの場所・ドライブの名前・動画は送らない）。
 * 送る本文は RankingApi の PutBody（名前・platform・appVersion・month・values・monthValues）で、値はここで作る。
 *
 * - distance: 走行距離 [m]。まとめ（全期間）と同じ = 閉じたドライブの距離（GPS ログの合計）の合計。
 *   乗り物（車 / 自転車 / 徒歩）で分けない・駐車中の徒歩の軌跡は入らない（まとめの距離と同じ）。
 *   今月 = ドライブの開始が今月（日本時間）のドライブの距離の合計。
 * - cells: 開拓したマス（1 km 四方、[jp.kjtr.drivelogger.stats.GridCells]）の数。まとめと同じマス。
 *   今月 = その月に初めて走ったマスの数（まとめの「初めて」と同じ: マスを初めて走ったドライブの開始が今月）。
 */
object RankingSpec {
    const val APP_ID = "drive"

    val BOARDS = listOf(
        RankBoard("distance", "走行距離", "km", divisor = 1000, decimals = 1),
        RankBoard("cells", "開拓したマス", "マス"),
    )

    /** 設定の説明に出す「送る数値」。 */
    const val SENT_VALUES = "走行距離と開拓したマス（1 km 四方）の数（累計と今月の分）"

    /** 設定の説明に出す「送らないもの」。 */
    const val NOT_SENT = "位置・座標・走った経路・マスの場所・動画・写真・ドライブの名前"

    /** サーバの上限（API.md §4。超えると bad-value で何も残らないので、ここで頭打ちにする）。 */
    const val MAX_DISTANCE_ALL = 10_000_000_000L
    const val MAX_DISTANCE_MONTH = 100_000_000L
    const val MAX_CELLS = 10_000_000L

    /** 日本時間の今月 [月初, 翌月初)。 */
    fun monthRange(nowMs: Long): TimeRange {
        val d = Instant.ofEpochMilli(nowMs).atZone(RankingRules.JST)
        val start = ZonedDateTime.of(d.year, d.monthValue, 1, 0, 0, 0, 0, RankingRules.JST)
        return TimeRange(start.toInstant().toEpochMilli(), start.plusMonths(1).toInstant().toEpochMilli())
    }

    /** [trips] = 閉じたドライブすべて（まとめと同じもの。cells はまとめと同じマス）。 */
    fun compute(trips: List<TripFacts>, nowMs: Long): RankValues {
        val month = RankingRules.monthOf(nowMs)
        val range = monthRange(nowMs)
        // 全期間はまとめの計算そのもの（数字が食い違わないように）
        val all = DriveSummaries.summarize(trips)
        // まとめの画面の期間の選び方と同じ（ドライブの開始が期間に入る）
        val inMonth = trips.filter { it.startedAt in range }
        val monthDistance = DriveSummaries.summarize(inMonth).distanceM
        val first = CellShapes.firstSeen(trips)
        val monthCells = CellShapes.newIn(inMonth.flatMapTo(HashSet()) { it.cells }, first, range).size
        return RankValues(
            month = month,
            values = mapOf(
                "distance" to meters(all.distanceM, MAX_DISTANCE_ALL),
                "cells" to all.cells.toLong().coerceIn(0, MAX_CELLS),
            ),
            monthValues = mapOf(
                "distance" to meters(monthDistance, MAX_DISTANCE_MONTH),
                "cells" to monthCells.toLong().coerceIn(0, MAX_CELLS),
            ),
        )
    }

    private fun meters(m: Double, max: Long): Long =
        if (m.isNaN() || m <= 0) 0 else Math.round(m).coerceIn(0, max)
}
