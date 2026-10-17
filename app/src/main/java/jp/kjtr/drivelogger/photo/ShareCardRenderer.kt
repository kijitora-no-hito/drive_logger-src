package jp.kjtr.drivelogger.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import jp.kjtr.drivelogger.geo.Geo
import jp.kjtr.drivelogger.geo.LatLon
import kotlin.math.max
import kotlin.math.min

/** SNS 用の画像の中身。 */
class CardContent(
    val title: String,
    /** タイトルの下の小さい行（日付など）。 */
    val subtitle: String,
    /** 統計（ラベル, 値）。5 つまで。 */
    val stats: List<Pair<String, String>>,
    /** 右下のアプリ名（null なら出さない）。 */
    val appName: String?,
    /**
     * 地図のスナップショット（Google マップ。ロゴ・著作権表示を含む）。null なら [route] を白地に描く代替表示。
     * 地図の枠と同じ縦横比で撮ってあること（切り取らずにそのまま縮める）。
     */
    val mapBitmap: Bitmap?,
    /** スナップショットの中のピンの位置（0〜1 に正規化）。[photos] と同じ順。位置の無い写真は null。 */
    val pinsOnSnapshot: List<PointF?>,
    /** 代替表示用の軌跡（区間ごと）。 */
    val route: List<List<LatLon>>,
    /** 代替表示用の写真の位置。 */
    val photoLocations: List<LatLon?>,
    val photos: List<Bitmap?>,
    /** 駐車の位置（P の印）。スナップショットの中の位置（0〜1）と、代替表示用の緯度経度。 */
    val parksOnSnapshot: List<PointF> = emptyList(),
    val parkLocations: List<LatLon> = emptyList(),
)

/** [ShareCardLayout] に従って 1 枚の画像を描く（自転車ルートロガーの ShareCardRenderer を車向けの色に）。 */
object ShareCardRenderer {
    private const val ACCENT = 0xFF1565C0.toInt()
    private const val ROUTE = 0xFF1E88E5.toInt()
    private const val TEXT = 0xFF1B1B1B.toInt()
    private const val SUB = 0xFF616161.toInt()
    private const val PARK = 0xFF6A1B9A.toInt()

    fun render(layout: CardLayout, c: CardContent): Bitmap {
        val w = layout.size.width
        val h = layout.size.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        val landscape = layout.size == CardSize.LANDSCAPE
        val k = if (landscape) 0.72f else 1f

        // タイトルと副題
        val titlePaint = text(48f * k, TEXT, bold = true)
        val subPaint = text(26f * k, SUB)
        val t = layout.title
        cv.drawText(ellipsize(c.title, titlePaint, t.width), t.left, t.top + titlePaint.textSize, titlePaint)
        cv.drawText(ellipsize(c.subtitle, subPaint, t.width), t.left, t.bottom - subPaint.descent(), subPaint)

        // 地図
        val m = layout.map
        val mapRect = RectF(m.left, m.top, m.right, m.bottom)
        val radius = 18f * k
        cv.save()
        cv.clipPath(Path().apply { addRoundRect(mapRect, radius, radius, Path.Direction.CW) })
        val pins: List<PointF?>
        val parkPins: List<PointF>
        if (c.mapBitmap != null) {
            // 切り取らない（Google のロゴと著作権表示を残す）。縦横比が同じなのでそのまま縮める
            cv.drawBitmap(c.mapBitmap, Rect(0, 0, c.mapBitmap.width, c.mapBitmap.height), mapRect, Paint(Paint.FILTER_BITMAP_FLAG))
            pins = c.pinsOnSnapshot.map { p -> p?.let { PointF(m.left + it.x * m.width, m.top + it.y * m.height) } }
            parkPins = c.parksOnSnapshot.map { PointF(m.left + it.x * m.width, m.top + it.y * m.height) }
        } else {
            val all = drawFallbackMap(cv, mapRect, c.route, c.photoLocations + c.parkLocations, k)
            pins = all.take(c.photoLocations.size)
            parkPins = all.drop(c.photoLocations.size).filterNotNull()
        }
        cv.restore()
        cv.drawRoundRect(mapRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x22000000 })

        // 地図のピン（番号）
        val pinR = (min(m.width, m.height) * 0.04f).coerceIn(16f, 26f)
        // 駐車の位置（P）。写真の番号より下に
        for (p in parkPins) if (mapRect.contains(p.x, p.y)) parkBadge(cv, p.x, p.y, pinR * 0.9f)
        // 近くで撮った写真のピンが重なるときは、横にずらして番号が読めるようにする
        val placed = ArrayList<PointF>()
        pins.forEachIndexed { i, p ->
            if (p == null || !mapRect.contains(p.x, p.y)) return@forEachIndexed
            val q = PointF(p.x, p.y)
            var tries = 0
            while (placed.any { kotlin.math.hypot((it.x - q.x).toDouble(), (it.y - q.y).toDouble()) < pinR * 2 } && tries++ < 6) {
                q.x += pinR * 2 + 2f
                if (q.x > mapRect.right - pinR) q.x = p.x - (pinR * 2 + 2f) * tries
            }
            placed.add(q)
            badge(cv, q.x, q.y, pinR, i + 1)
        }

        // 写真
        layout.photos.forEachIndexed { i, b ->
            val r = RectF(b.left, b.top, b.right, b.bottom)
            cv.save()
            cv.clipPath(Path().apply { addRoundRect(r, 12f * k, 12f * k, Path.Direction.CW) })
            val photo = c.photos.getOrNull(i)
            if (photo != null) drawCenterCrop(cv, photo, r) else cv.drawRect(r, Paint().apply { color = 0xFFE0E0E0.toInt() })
            cv.restore()
            badge(cv, r.left + pinR + 8f, r.top + pinR + 8f, pinR, i + 1)
        }

        // 統計
        val s = layout.stats
        val n = c.stats.size.coerceAtLeast(1)
        val cellW = s.width / n
        val labelPaint = text(24f * k, SUB)
        val valuePaint = text((if (n >= 5) 38f else 44f) * k, TEXT, bold = true)
        cv.drawLine(s.left, s.top, s.right, s.top, Paint().apply { color = 0x1F000000; strokeWidth = 2f })
        c.stats.forEachIndexed { i, (label, value) ->
            val x = s.left + i * cellW + 4f
            val y = s.top + 12f * k + labelPaint.textSize
            cv.drawText(ellipsize(label, labelPaint, cellW - 8f), x, y, labelPaint)
            cv.drawText(ellipsize(value, valuePaint, cellW - 8f), x, y + 10f * k + valuePaint.textSize, valuePaint)
        }

        // アプリ名
        layout.appLabel?.let { a ->
            val p = text(22f * k, 0xFF9E9E9E.toInt()).apply { textAlign = Paint.Align.RIGHT }
            if (c.appName != null) cv.drawText(ellipsize(c.appName, p, a.width), a.right, a.bottom - p.descent(), p)
        }
        return bmp
    }

    /** キーが通らない・地図を使わないとき: 白地に軌跡だけ（北が上・正距円筒）。写真の位置（ピンの場所）を返す。 */
    private fun drawFallbackMap(cv: Canvas, r: RectF, route: List<List<LatLon>>, photoLocs: List<LatLon?>, k: Float): List<PointF?> {
        cv.drawRect(r, Paint().apply { color = 0xFFF2F4F7.toInt() })
        // 薄い方眼
        val grid = Paint().apply { color = 0x0F000000; strokeWidth = 1f }
        var gx = r.left
        while (gx < r.right) { cv.drawLine(gx, r.top, gx, r.bottom, grid); gx += 60f * k }
        var gy = r.top
        while (gy < r.bottom) { cv.drawLine(r.left, gy, r.right, gy, grid); gy += 60f * k }
        val all = route.flatten() + photoLocs.filterNotNull()
        if (all.isEmpty()) return photoLocs.map { null }
        val lat0 = all.first().lat
        val mLon = Geo.metersPerDegLon(lat0)
        val mLat = Geo.metersPerDegLon(0.0)
        fun xy(p: LatLon) = Pair((p.lon - all.first().lon) * mLon, (p.lat - lat0) * mLat)
        val vs = all.map { xy(it) }
        val minX = vs.minOf { it.first }; val maxX = vs.maxOf { it.first }
        val minY = vs.minOf { it.second }; val maxY = vs.maxOf { it.second }
        val pad = 40f * k
        val sx = (r.width() - 2 * pad) / max(maxX - minX, 1.0).toFloat()
        val sy = (r.height() - 2 * pad) / max(maxY - minY, 1.0).toFloat()
        val sc = min(sx, sy)
        val ox = r.left + (r.width() - (maxX - minX).toFloat() * sc) / 2
        val oy = r.top + (r.height() - (maxY - minY).toFloat() * sc) / 2
        fun px(p: LatLon): PointF {
            val v = xy(p)
            return PointF(ox + (v.first - minX).toFloat() * sc, oy + (maxY - v.second).toFloat() * sc)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8f * k; color = ROUTE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (seg in route) {
            if (seg.size < 2) continue
            val path = Path()
            seg.forEachIndexed { i, p -> val q = px(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            cv.drawPath(path, line)
        }
        route.firstOrNull()?.firstOrNull()?.let { dot(cv, px(it), 10f * k, 0xFF2E7D32.toInt()) }
        route.lastOrNull()?.lastOrNull()?.let { dot(cv, px(it), 10f * k, 0xFFC62828.toInt()) }
        return photoLocs.map { it?.let(::px) }
    }

    private fun dot(cv: Canvas, p: PointF, r: Float, color: Int) {
        cv.drawCircle(p.x, p.y, r + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        cv.drawCircle(p.x, p.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun parkBadge(cv: Canvas, x: Float, y: Float, r: Float) {
        val rect = RectF(x - r, y - r, x + r, y + r)
        cv.drawRoundRect(RectF(rect.left - 3f, rect.top - 3f, rect.right + 3f, rect.bottom + 3f), r * 0.4f, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        cv.drawRoundRect(rect, r * 0.35f, r * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PARK })
        val p = text(r * 1.3f, Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
        cv.drawText("P", x, y - (p.ascent() + p.descent()) / 2, p)
    }

    private fun badge(cv: Canvas, x: Float, y: Float, r: Float, n: Int) {
        cv.drawCircle(x, y, r + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        val p = text(r * 1.15f, Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
        cv.drawText(n.toString(), x, y - (p.ascent() + p.descent()) / 2, p)
    }

    private fun drawCenterCrop(cv: Canvas, b: Bitmap, dst: RectF) {
        val scale = max(dst.width() / b.width, dst.height() / b.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sl = (b.width - sw) / 2
        val st = (b.height - sh) / 2
        cv.drawBitmap(b, Rect(sl.toInt(), st.toInt(), (sl + sw).toInt(), (st + sh).toInt()), dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }
}
