package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.stats.CellRect
import jp.kjtr.drivelogger.stats.CellShapes
import jp.kjtr.drivelogger.stats.GridCells
import jp.kjtr.drivelogger.stats.TimeRange
import jp.kjtr.drivelogger.stats.TripFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 開拓したマスの地図の表示（マスの矩形・隣り合うマスをまとめる・表示範囲・初めて開拓したマス）。 */
class CellShapesTest {

    @Test
    fun rectContainsItsPointsAndIsAboutOneKm() {
        for ((lat, lon) in listOf(35.6812 to 139.7671, 43.0621 to 141.3544, -33.86 to 151.21, 35.0 to -120.5)) {
            val c = GridCells.cellOf(lat, lon)
            val r = CellShapes.rectOf(c)
            assertTrue("$lat,$lon in $r", lat >= r.south && lat < r.north && lon >= r.west && lon < r.east)
            // 縦横 ほぼ 1 km
            assertEquals(1000.0, (r.north - r.south) * Geo.METERS_PER_DEG_LAT, 1.0)
            val mid = (r.north + r.south) / 2
            assertEquals(1000.0, Geo.distanceM(mid, r.west, mid, r.east), 15.0)
            // 矩形の中の点は同じマス
            assertEquals(c, GridCells.cellOf((r.south + r.north) / 2, (r.west + r.east) / 2))
        }
    }

    @Test
    fun mergesAdjacentCellsInARow() {
        val c0 = GridCells.cellOf(35.68, 139.76)
        val row = CellShapes.rowOf(c0)
        val col = CellShapes.colOf(c0)
        fun cell(r: Long, c: Long) = (r shl 32) xor (c and 0xffffffffL)
        // 行 row: 列 col..col+3 と col+5（離れている）、行 row+1: col と col+1
        val cells = listOf(cell(row, col), cell(row, col + 1), cell(row, col + 2), cell(row, col + 3), cell(row, col + 5), cell(row + 1, col), cell(row + 1, col + 1))
        val merged = CellShapes.merge(cells.shuffled(java.util.Random(3)))
        assertEquals(3, merged.size)
        // 1 つ目: 行 row の 4 マスが 1 つの矩形（西端 = 最初のマスの西、東端 = 4 つ目のマスの東）
        assertEquals(CellShapes.rectOf(cell(row, col)).west, merged[0].west, 1e-12)
        assertEquals(CellShapes.rectOf(cell(row, col + 3)).east, merged[0].east, 1e-12)
        assertEquals(CellShapes.rectOf(cell(row, col + 5)), merged[1])
        assertEquals(CellShapes.rectOf(cell(row + 1, col)).west, merged[2].west, 1e-12)
        // 同じマスが 2 回あっても 1 つ
        assertEquals(1, CellShapes.merge(listOf(c0, c0)).size)
        // 経度の負のマス（西経）もまとめられる
        val w = GridCells.cellOf(35.0, -120.5)
        val w2 = cell(CellShapes.rowOf(w), CellShapes.colOf(w) + 1)
        assertEquals(1, CellShapes.merge(listOf(w, w2)).size)
        assertTrue(CellShapes.merge(emptyList()).isEmpty())
    }

    @Test
    fun visibleFiltersByViewAndLimit() {
        // 東京から 100 km 東へ 1 列に並んだマス（まとめると 1 つ）と、ばらばらのマス
        val base = GridCells.cellOf(35.68, 139.76)
        val row = CellShapes.rowOf(base)
        val col = CellShapes.colOf(base)
        val scattered = (0 until 50).map { i -> (row + i * 2 shl 32) xor ((col + i * 2) and 0xffffffffL) }
        val rects = CellShapes.merge(scattered)
        assertEquals(50, rects.size)
        val view = CellRect(35.6, 139.6, 35.8, 139.9)
        val v = CellShapes.visible(rects, view)
        assertTrue(v.isNotEmpty() && v.size < 50)
        assertTrue(v.all { it.intersects(view) })
        assertEquals(5, CellShapes.visible(rects, CellRect(-90.0, -180.0, 90.0, 180.0), limit = 5).size)
    }

    @Test
    fun firstExploredCells() {
        val a = GridCells.cellOf(35.0, 139.0)
        val b = GridCells.cellOf(35.1, 139.1)
        val c = GridCells.cellOf(35.2, 139.2)
        val day = 86_400_000L
        fun trip(id: Long, start: Long, vararg cells: Long) = TripFacts(id, "t$id", start, start + 3_600_000, 0.0, 0, 0.0, cells = cells.toSet())
        val trips = listOf(trip(2, 10 * day, b, c), trip(1, 1 * day, a, b), trip(3, 20 * day, a, c))
        val first = CellShapes.firstSeen(trips)
        assertEquals(1 * day, first[a])
        assertEquals(1 * day, first[b])
        assertEquals(10 * day, first[c])
        // 期間 5〜25 日に走ったマスは a, b, c。その中で初めて開拓したのは c だけ
        assertEquals(setOf(c), CellShapes.newIn(setOf(a, b, c), first, TimeRange(5 * day, 25 * day)))
        // ドライブ 2 で初めて走ったマス
        assertEquals(setOf(c), CellShapes.newInTrip(setOf(b, c), first, 10 * day))
        assertEquals(setOf(a, b), CellShapes.newInTrip(setOf(a, b), first, 1 * day))
    }

    @Test
    fun tripNewCellCountIsCellsNotRects() {
        // 1 行に並んだ 4 マス + 離れた 1 マス = 5 マス（まとめると矩形 2 つ）。数はマスの数で出す（0.4.0 は矩形の数 2 を出していた）
        val c0 = GridCells.cellOf(35.68, 139.76)
        val row = CellShapes.rowOf(c0)
        val col = CellShapes.colOf(c0)
        fun cell(r: Long, c: Long) = (r shl 32) xor (c and 0xffffffffL)
        val fresh = setOf(cell(row, col), cell(row, col + 1), cell(row, col + 2), cell(row, col + 3), cell(row + 2, col))
        val old = cell(row, col + 4)
        val day = 86_400_000L
        val trips = listOf(
            TripFacts(1, "old", 1 * day, 1 * day + 3_600_000, 0.0, 0, 0.0, cells = setOf(old)),
            TripFacts(2, "new", 2 * day, 2 * day + 3_600_000, 0.0, 0, 0.0, cells = fresh + old),
        )
        val first = CellShapes.firstSeen(trips)
        val r = CellShapes.newCellsOfTrip(fresh + old, first, 2 * day)
        assertEquals(2, r.rects.size)
        assertEquals(5, r.count)
        // 初めてのマスが無いドライブ
        val none = CellShapes.newCellsOfTrip(setOf(old), first, 2 * day)
        assertEquals(0, none.count)
        assertTrue(none.rects.isEmpty())
    }
}
