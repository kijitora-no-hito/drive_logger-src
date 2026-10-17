package jp.kjtr.drivelogger.stats

import jp.kjtr.drivelogger.geo.Geo
import kotlin.math.cos
import kotlin.math.max

/** 緯度経度の矩形（南・西・北・東）。 */
data class CellRect(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun intersects(o: CellRect) = south < o.north && o.south < north && west < o.east && o.west < east
}

/**
 * 開拓したマス（[GridCells]、1 km 四方）の地図の表示（純ロジック。単体テスト: CellShapesTest）。
 *
 * - マスの番号 → 緯度経度の矩形（[GridCells.cellOf] の逆）。
 * - 同じ行（緯度の帯）で隣り合うマスは 1 つの横長の矩形にまとめる（行ごとに経度の刻みが違うので、行をまたいではまとめない）。
 *   マスが多くても描く図形の数が減る。
 * - 表示範囲に入るものだけ描く（[visible]。上限 [MAX_SHAPES]）。
 * - 期間の中で初めて開拓したマス（[firstSeen]）を別の色にできる。
 */
object CellShapes {
    private const val KM = 1000.0
    /** 一度に描く図形の上限（多すぎると地図が重くなる）。 */
    const val MAX_SHAPES = 1500

    fun rowOf(cell: Long): Long = cell shr 32
    fun colOf(cell: Long): Long = (cell and 0xffffffffL).toInt().toLong()

    /** マス 1 つの矩形。 */
    fun rectOf(cell: Long): CellRect = rowRect(rowOf(cell), colOf(cell), colOf(cell))

    /** 行 [row] の列 [c0]〜[c1]（両端を含む）の矩形。 */
    fun rowRect(row: Long, c0: Long, c1: Long): CellRect {
        val south = row * KM / Geo.METERS_PER_DEG_LAT
        val north = (row + 1) * KM / Geo.METERS_PER_DEG_LAT
        val centerLat = (row + 0.5) * KM / Geo.METERS_PER_DEG_LAT
        val mPerLon = max(1.0, Geo.EARTH_RADIUS_M * Math.PI / 180.0 * cos(Math.toRadians(centerLat)))
        return CellRect(south, c0 * KM / mPerLon, north, (c1 + 1) * KM / mPerLon)
    }

    /** 同じ行で隣り合うマスをまとめた矩形（行の順・西から）。 */
    fun merge(cells: Collection<Long>): List<CellRect> {
        val out = ArrayList<CellRect>()
        for ((row, list) in cells.groupBy { rowOf(it) }.toSortedMap()) {
            val cols = list.map { colOf(it) }.sorted()
            var start = cols.first()
            var prev = start
            for (c in cols.drop(1)) {
                if (c == prev + 1) {
                    prev = c
                    continue
                }
                if (c == prev) continue
                out.add(rowRect(row, start, prev))
                start = c
                prev = c
            }
            out.add(rowRect(row, start, prev))
        }
        return out
    }

    /** 表示範囲 [view] に入る矩形だけ（上限 [limit]）。 */
    fun visible(rects: List<CellRect>, view: CellRect, limit: Int = MAX_SHAPES): List<CellRect> =
        rects.asSequence().filter { it.intersects(view) }.take(limit).toList()

    /**
     * マスごとに、初めて走ったドライブの開始時刻（[trips] は全期間。開始の早い順に見る）。
     * 期間の中で初めて開拓したマス = この時刻が期間に入るもの。
     */
    fun firstSeen(trips: List<TripFacts>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (t in trips.sortedBy { it.startedAt }) for (c in t.cells) out.putIfAbsent(c, t.startedAt)
        return out
    }

    /** 期間 [range] の中で初めて開拓したマス（[cells] は期間内に走ったマス）。 */
    fun newIn(cells: Set<Long>, first: Map<Long, Long>, range: TimeRange): Set<Long> =
        cells.filterTo(HashSet()) { c -> first[c]?.let { it in range } == true }

    /** ドライブ [tripStartedAt] で初めて走ったマス（同じ時刻に始まった別のドライブは無い前提）。 */
    fun newInTrip(tripCells: Set<Long>, first: Map<Long, Long>, tripStartedAt: Long): Set<Long> =
        tripCells.filterTo(HashSet()) { first[it] == tripStartedAt }

    /**
     * ドライブの詳細の地図に出す「このドライブで初めて走ったマス」（0.4.1〜）。[rects] は描く矩形（隣り合うマスをまとめたもの）、
     * [count] は**マスの数**（まとめる前。0.4.0 は矩形の数を出していて少なく見えた）。
     */
    data class TripNewCells(val rects: List<CellRect>, val count: Int)

    fun newCellsOfTrip(tripCells: Set<Long>, first: Map<Long, Long>, tripStartedAt: Long): TripNewCells {
        val fresh = newInTrip(tripCells, first, tripStartedAt)
        return TripNewCells(merge(fresh), fresh.size)
    }
}
