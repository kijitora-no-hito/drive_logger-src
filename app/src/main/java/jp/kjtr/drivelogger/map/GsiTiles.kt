package jp.kjtr.drivelogger.map

import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 地理院タイルの種類（https://maps.gsi.go.jp/development/ichiran.html）。
 * 動画に焼き込む地図はこれだけ（Google マップは規約上、動画に焼き込まない）。出典「出典：国土地理院（地理院タイル）」を一緒に焼き込む。
 */
enum class GsiLayer(val id: String, val ext: String) {
    STD("std", "png"),
    PALE("pale", "png"),
    PHOTO("seamlessphoto", "jpg"),
}

/** 焼き込む地図に重ねる軌跡。[meters] = 直近何 m か（null = 全体、0 = 描かない）。 */
enum class MapTrack(val meters: Double?) {
    NONE(0.0),
    RECENT_1KM(1_000.0),
    RECENT_5KM(5_000.0),
    ALL(null),
}

/** タイル 1 枚。[x] は 0 until 2^z に折り返した値。 */
data class TileKey(val layer: GsiLayer, val z: Int, val x: Int, val y: Int) {
    val url: String get() = "https://cyberjapandata.gsi.go.jp/xyz/${layer.id}/$z/$x/$y.${layer.ext}"

    /** キャッシュの相対パス（`std/15/29102/12903.png`）。 */
    val path: String get() = "${layer.id}/$z/$x/$y.${layer.ext}"
}

/**
 * Web メルカトルのタイル座標（純ロジック。単体テスト: GsiTilesTest）。
 * 「世界ピクセル」= ズーム z で世界全体を 256·2^z 四方にしたときの座標（x は東向き、y は南向き）。
 */
object TileMath {
    const val TILE = 256
    private const val MAX_LAT = 85.05112878

    fun worldSize(z: Int): Double = TILE.toDouble() * (1 shl z)

    fun worldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)

    fun worldY(lat: Double, z: Int): Double {
        val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(z)
    }

    fun lonOf(worldX: Double, z: Int): Double = worldX / worldSize(z) * 360.0 - 180.0

    fun latOf(worldY: Double, z: Int): Double = atan(sinh(PI * (1.0 - 2.0 * worldY / worldSize(z)))) * 180.0 / PI

    /** 緯度経度を含むタイル（x, y）。 */
    fun tileOf(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = floor(worldX(lon, z) / TILE).toInt().coerceIn(0, n - 1)
        val y = floor(worldY(lat, z) / TILE).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** 1 世界ピクセルが何 m か（その緯度で）。 */
    fun metersPerPixel(lat: Double, z: Int): Double = cos(lat * PI / 180.0) * 2 * PI * Geo.EARTH_RADIUS_M / worldSize(z)

    /**
     * 中心（世界ピクセル [cx], [cy]）から半径 [radiusPx] の円にかかるタイル（中心に近い順）。
     * x は折り返す（日付変更線をまたいでも同じ）。y が範囲外のタイルは無い。
     * 戻り値の [Tile.dx] は折り返す前の x（描く位置の計算に使う）。
     */
    fun tilesInRadius(cx: Double, cy: Double, radiusPx: Double, z: Int): List<Tile> {
        val n = 1 shl z
        val x0 = floor((cx - radiusPx) / TILE).toInt()
        val x1 = floor((cx + radiusPx) / TILE).toInt()
        val y0 = floor((cy - radiusPx) / TILE).toInt().coerceAtLeast(0)
        val y1 = floor((cy + radiusPx) / TILE).toInt().coerceAtMost(n - 1)
        val out = ArrayList<Tile>()
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                // タイルの四角と円が重なるか（四角の中で中心にいちばん近い点までの距離）
                val nx = cx.coerceIn(tx * TILE.toDouble(), (tx + 1) * TILE.toDouble())
                val ny = cy.coerceIn(ty * TILE.toDouble(), (ty + 1) * TILE.toDouble())
                val d2 = (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)
                if (d2 > radiusPx * radiusPx) continue
                val ccx = (tx + 0.5) * TILE - cx
                val ccy = (ty + 0.5) * TILE - cy
                out.add(Tile(((tx % n) + n) % n, ty, tx, ccx * ccx + ccy * ccy))
            }
        }
        out.sortBy { it.dist2 }
        return out
    }

    /** [x]・[y] はタイル番号（x は折り返し済み）、[dx] は描く位置用の折り返す前の x、[dist2] は中心からの距離² [px²]。 */
    data class Tile(val x: Int, val y: Int, val dx: Int, val dist2: Double)
}

/**
 * 走行中の先読みの範囲（純ロジック。単体テスト: GsiTilesTest）。
 * 「必要な範囲だけ」: 今の表示範囲（回転しても欠けない円）＋縁 1/2 タイル、動いていれば進む向きに表示範囲 1 つ分先の円。
 * 1 回に [MAX_TILES] 枚まで（中心に近い順）。
 */
object TilePrefetch {
    const val MAX_TILES = 40

    fun plan(lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double?, z: Int, viewRadiusPx: Double): List<Pair<Int, Int>> {
        val cx = TileMath.worldX(lon, z)
        val cy = TileMath.worldY(lat, z)
        val r = viewRadiusPx + TileMath.TILE / 2.0
        val seen = LinkedHashSet<Pair<Int, Int>>()
        TileMath.tilesInRadius(cx, cy, r, z).forEach { seen.add(it.x to it.y) }
        if (headingDeg != null && (speedKmh ?: 0.0) >= MOVING_KMH) {
            val h = headingDeg * PI / 180.0
            val d = viewRadiusPx * 2.0
            val ax = cx + sin(h) * d
            val ay = cy - cos(h) * d
            TileMath.tilesInRadius(ax, ay, viewRadiusPx, z).forEach { seen.add(it.x to it.y) }
        }
        return seen.take(MAX_TILES)
    }

    /** これ以上で「動いている」（進む向きの先も読む）。 */
    const val MOVING_KMH = 10.0
}

/**
 * タイルのディスクキャッシュの上限と削除（純ロジック。単体テスト: GsiTilesTest）。
 * 上限を超えたら、取得した時刻の古い順に、[targetRatio] まで減らす。
 */
object TileCachePolicy {
    /** 上限 [byte]（200 MB）。 */
    const val LIMIT_BYTES = 200L * 1024 * 1024
    /** 取得してからこの間は取り直さない（地理院タイルの更新は頻繁ではない）。 */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** 取れなかったタイルを次に試すまで。 */
    const val RETRY_MS = 60_000L

    data class Entry(val path: String, val bytes: Long, val fetchedAt: Long)

    /** 消すもの（古い順）。合計が [limitBytes] 以下なら空。 */
    fun evict(entries: List<Entry>, limitBytes: Long, targetRatio: Double = 0.9): List<Entry> {
        var total = entries.sumOf { it.bytes }
        if (total <= limitBytes) return emptyList()
        val target = (limitBytes * targetRatio).toLong()
        val out = ArrayList<Entry>()
        for (e in entries.sortedWith(compareBy<Entry> { it.fetchedAt }.thenBy { it.path })) {
            if (total <= target) break
            out.add(e)
            total -= e.bytes
        }
        return out
    }

    fun isFresh(fetchedAt: Long, now: Long): Boolean = now - fetchedAt in 0 until MAX_AGE_MS
}

/** 地図の表示（純ロジック。単体テスト: GsiTilesTest）。 */
object MapGeometry {
    /**
     * 世界ピクセルの差（[dx] 東, [dy] 南）を、窓の中心からの画面の差に（[scale] 倍）。
     * [headingUp] なら進行方向 [headingDeg] が上になるよう回す（Canvas.rotate(-heading) と同じ）。
     */
    fun toWindow(dx: Double, dy: Double, headingDeg: Double?, headingUp: Boolean, scale: Double = 1.0): Pair<Double, Double> {
        if (!headingUp || headingDeg == null) return dx * scale to dy * scale
        val h = headingDeg * PI / 180.0
        val c = cos(h)
        val s = sin(h)
        return (dx * c + dy * s) * scale to (-dx * s + dy * c) * scale
    }

    /** 窓の一辺 [s] px のとき、タイルを何倍で描くか（解像度が違っても同じくらいの範囲が見えるように）。 */
    fun tileScale(s: Int): Float = (s / 300f).coerceIn(0.75f, 2.5f)

    /** 回しても角が欠けない、窓の外接円の半径（世界ピクセル）。 */
    fun viewRadiusPx(s: Int): Double = s / 2.0 * sqrt(2.0) / tileScale(s)

    /** 窓の一辺 [px]（短い辺 × 割合）。 */
    fun windowSize(ratio: Float, width: Int, height: Int): Int = (minOf(width, height) * ratio).toInt().coerceAtLeast(64)
}

/** 走った軌跡の直近 [meters] m（null = 全体、0 以下 = なし）。区間の並びは保つ。 */
object TrackTail {
    fun recent(segments: List<List<LatLon>>, meters: Double?): List<List<LatLon>> {
        if (meters == null) return segments
        if (meters <= 0.0) return emptyList()
        var left = meters
        val out = ArrayList<List<LatLon>>()
        for (si in segments.indices.reversed()) {
            val seg = segments[si]
            if (seg.isEmpty()) continue
            var start = seg.size - 1
            while (start > 0) {
                val d = Geo.distanceM(seg[start], seg[start - 1])
                if (d > left + 0.01) break
                left -= d
                start--
            }
            out.add(seg.subList(start, seg.size))
            if (start > 0) break
            if (left <= 0.0) break
        }
        out.reverse()
        return out
    }
}
