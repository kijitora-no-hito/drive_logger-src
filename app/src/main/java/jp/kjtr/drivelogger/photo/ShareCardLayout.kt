package jp.kjtr.drivelogger.photo

import kotlin.math.ceil

/** SNS 用の画像の大きさ。 */
enum class CardSize(val width: Int, val height: Int) {
    /** 正方形（Instagram など）。 */
    SQUARE(1080, 1080),
    /** 縦（Instagram の縦長 4:5）。 */
    PORTRAIT(1080, 1350),
    /** 横（X・Facebook のリンク画像 1.91:1）。 */
    LANDSCAPE(1200, 630),
}

/** 長方形（px、右・下は含まない）。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun inside(w: Int, h: Int) = left >= 0 && top >= 0 && right <= w && bottom <= h && width > 0 && height > 0
}

/** 各要素の位置。[photos] の i 番目が写真の番号 i + 1（地図のピンも同じ番号）。 */
data class CardLayout(
    val size: CardSize,
    val title: Box,
    val map: Box,
    val photos: List<Box>,
    val stats: Box,
    val appLabel: Box?,
) {
    fun all(): List<Box> = listOfNotNull(title, map, stats, appLabel) + photos
}

/**
 * SNS 用の画像のレイアウト計算（純ロジック。自転車ルートロガーの ShareCardLayout と同じ）。上にタイトル、下に統計（とアプリ名）、間に地図と写真。
 * 縦長・正方形は地図の下に写真（3 枚まで 1 段、4〜6 枚は 2 段）、横長は地図の右に写真（3 枚まで 1 列、4〜6 枚は 2 列）。
 */
object ShareCardLayout {
    const val MAX_PHOTOS = 6

    fun compute(size: CardSize, photoCount: Int, showAppName: Boolean): CardLayout {
        val n = photoCount.coerceIn(0, MAX_PHOTOS)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val landscape = size == CardSize.LANDSCAPE
        val m = if (landscape) 28f else 40f
        val gap = if (landscape) 14f else 20f
        val titleH = when (size) { CardSize.SQUARE -> 96f; CardSize.PORTRAIT -> 110f; CardSize.LANDSCAPE -> 64f }
        val footerH = when (size) { CardSize.SQUARE -> 150f; CardSize.PORTRAIT -> 170f; CardSize.LANDSCAPE -> 74f }

        val title = Box(m, m, w - m, m + titleH)
        val footerTop = h - m - footerH
        val labelW = if (landscape) 260f else 360f
        val labelH = if (landscape) 28f else 34f
        val appLabel = if (showAppName) Box(w - m - labelW, h - m - labelH, w - m, h - m) else null
        val stats = if (showAppName) {
            if (landscape) Box(m, footerTop, w - m - labelW - gap, h - m) else Box(m, footerTop, w - m, h - m - labelH - 8f)
        } else Box(m, footerTop, w - m, h - m)

        val content = Box(m, title.bottom + gap, w - m, footerTop - gap)
        if (n == 0) return CardLayout(size, title, content, emptyList(), stats, appLabel)

        val photos = ArrayList<Box>()
        val map: Box
        if (landscape) {
            val cols = if (n <= 3) 1 else 2
            val rows = ceil(n / cols.toDouble()).toInt()
            val photoW = content.width * (if (cols == 1) 0.30f else 0.40f)
            map = Box(content.left, content.top, content.right - photoW - gap, content.bottom)
            val cellW = (photoW - gap * (cols - 1)) / cols
            val cellH = (content.height - gap * (rows - 1)) / rows
            for (i in 0 until n) {
                val c = i % cols
                val r = i / cols
                val l = map.right + gap + c * (cellW + gap)
                val t = content.top + r * (cellH + gap)
                photos.add(Box(l, t, l + cellW, t + cellH))
            }
        } else {
            val rows = if (n <= 3) 1 else 2
            val cols = ceil(n / rows.toDouble()).toInt()
            val share = when {
                size == CardSize.SQUARE && rows == 1 -> 0.30f
                size == CardSize.SQUARE -> 0.42f
                rows == 1 -> 0.32f
                else -> 0.44f
            }
            val photoH = content.height * share
            map = Box(content.left, content.top, content.right, content.bottom - photoH - gap)
            val cellW = (content.width - gap * (cols - 1)) / cols
            val cellH = (photoH - gap * (rows - 1)) / rows
            for (i in 0 until n) {
                val r = i / cols
                val c = i % cols
                // 下の段の数が少ないときは中央に寄せる
                val inRow = if (r == rows - 1) n - cols * (rows - 1) else cols
                val offset = (cols - inRow) * (cellW + gap) / 2
                val l = content.left + offset + c * (cellW + gap)
                val t = map.bottom + gap + r * (cellH + gap)
                photos.add(Box(l, t, l + cellW, t + cellH))
            }
        }
        return CardLayout(size, title, map, photos, stats, appLabel)
    }
}

/** SNS 用の画像に出す統計の項目（車向け）。 */
enum class CardStat { DISTANCE, ELAPSED, AVG, MAX, STILL }

/** 統計の材料（保存したトリップか、記録中の「ここまで」）。 */
data class CardTripStats(
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val stillMs: Long,
    val maxSpeedMps: Double,
) {
    /** 平均（トリップの一覧と同じ: 距離 / 走行時間）[m/s]。 */
    val avgSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
}

/**
 * SNS 用の画像に出す統計を選ぶ（純ロジック）。
 * - [hideMax]: 最高速度を出さない（常に）。
 * - [hideOverThreshold]: 0.3.0 の「速度を隠す」と同じ考え方で、しきい値 [thresholdKmh] を超えた速度の項目（最高・平均）を出さない。
 *   低い数字に置き換えることはせず、項目ごと入れない。
 */
object CardStats {
    val ALL = listOf(CardStat.DISTANCE, CardStat.ELAPSED, CardStat.AVG, CardStat.MAX, CardStat.STILL)

    fun pick(s: CardTripStats, hideMax: Boolean, hideOverThreshold: Boolean, thresholdKmh: Int): List<CardStat> = ALL.filter { st ->
        when (st) {
            CardStat.MAX -> !hideMax && !(hideOverThreshold && over(s.maxSpeedMps, thresholdKmh))
            CardStat.AVG -> !(hideOverThreshold && over(s.avgSpeedMps, thresholdKmh))
            else -> true
        }
    }

    /** しきい値で隠れる項目があるか（画面の注意）。 */
    fun hiddenByThreshold(s: CardTripStats, thresholdKmh: Int): List<CardStat> = buildList {
        if (over(s.avgSpeedMps, thresholdKmh)) add(CardStat.AVG)
        if (over(s.maxSpeedMps, thresholdKmh)) add(CardStat.MAX)
    }

    /** 画像に出す数字（km/h 小数 1 桁）で比べる（表示が「60.0」なのに隠れる、ということが無いように）。 */
    private fun over(mps: Double, thresholdKmh: Int): Boolean = Math.round(mps * 3.6 * 10) / 10.0 > thresholdKmh
}
