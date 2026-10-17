package jp.kjtr.drivelogger.gpx

import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * GPX 1.0 / 1.1 のストリーミング解析（XmlPullParser）。
 *
 * - 読むもの: `trk/trkseg/trkpt`・`rte/rtept`・`wpt`、各点の `ele`・`time`、`name`（trk / rte / wpt / metadata）、
 *   wpt の `desc`・`type`・`sym`。`extensions` は中身ごと読み飛ばす。
 * - 名前空間は見ない（1.0 と 1.1 で URI が違うだけで要素名は同じ）。
 * - 点が [maxPoints] に達したら、そこまでの点を 1 つおきに間引き、以後も同じ間隔で取る（形を保ったまま上限に収める）。
 *   各区間の始点・終点は残す。
 * - 途中で壊れていた（XML の誤り・途中で切れている）ときは、読めた点があればそこまでを返す（[GpxData.partial]）。
 * - [maxBytes] を超える入力は読まない（[GpxException.Reason.TOO_LARGE]）。
 *
 * 実際の XmlPullParser はアプリでは `android.util.Xml.newPullParser()`、単体テストでは kxml2 を渡す。
 */
class GpxParser(
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
    private val maxWaypoints: Int = DEFAULT_MAX_WAYPOINTS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private class Seg {
        val pts = ArrayList<GpxPoint>()
        var pendingLast: GpxPoint? = null
    }

    private class Trk(val isRoute: Boolean) {
        var name: String? = null
        val segs = ArrayList<Seg>()
    }

    private class TooLargeException : IOException("too large")

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw TooLargeException()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw TooLargeException()
            }
            return n
        }
    }

    fun parse(parser: XmlPullParser, input: InputStream): GpxData {
        val tracks = ArrayList<Trk>()
        val waypoints = ArrayList<GpxWaypoint>()
        var curTrack: Trk? = null
        var curSeg: Seg? = null
        var total = 0
        var stride = 1L
        var counter = 0L
        var decimated = false
        var waypointsTruncated = false
        var docName: String? = null
        var docDesc: String? = null
        var version: String? = null
        var sawRoot = false

        // 点の組み立て中の値
        var pointKind = 0 // 0: なし 1: trkpt/rtept 2: wpt
        var pLat = Double.NaN
        var pLon = Double.NaN
        var pEle: Double? = null
        var pTime: Long? = null
        var pName: String? = null
        var pDesc: String? = null
        var pType: String? = null
        var pSym: String? = null

        val stack = ArrayList<String>()
        val text = StringBuilder()
        var collecting = false

        fun halve() {
            for (t in tracks) for (s in t.segs) {
                val src = s.pts
                if (src.size <= 2) continue
                val dst = ArrayList<GpxPoint>(src.size / 2 + 2)
                for (i in src.indices) if (i % 2 == 0 || i == src.size - 1) dst.add(src[i])
                src.clear()
                src.addAll(dst)
            }
            total = tracks.sumOf { t -> t.segs.sumOf { it.pts.size } }
            stride *= 2
            decimated = true
        }

        fun endSeg() {
            val s = curSeg ?: return
            s.pendingLast?.let {
                s.pts.add(it)
                total++
            }
            s.pendingLast = null
            curSeg = null
            if (total >= maxPoints) halve()
        }

        fun addPoint(p: GpxPoint) {
            val t = curTrack ?: return
            val s = curSeg ?: Seg().also { t.segs.add(it); curSeg = it }
            val first = s.pts.isEmpty() && s.pendingLast == null
            counter++
            if (first || counter % stride == 0L) {
                s.pts.add(p)
                s.pendingLast = null
                total++
                if (total >= maxPoints) halve()
            } else {
                s.pendingLast = p
            }
        }

        fun textValue(): String? = text.toString().trim().takeIf { it.isNotEmpty() }

        var broken: Throwable? = null
        try {
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            parser.setInput(LimitedInputStream(input, maxBytes), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = localName(parser.name)
                        if (stack.isEmpty()) {
                            if (name != "gpx") throw GpxException(GpxException.Reason.NOT_GPX)
                            sawRoot = true
                        }
                        stack.add(name)
                        when (name) {
                            "gpx" -> version = attr(parser, "version")
                            "trk" -> {
                                curTrack = Trk(false).also { tracks.add(it) }
                                curSeg = null
                            }
                            "rte" -> {
                                curTrack = Trk(true).also { tracks.add(it) }
                                curSeg = Seg().also { curTrack!!.segs.add(it) }
                            }
                            "trkseg" -> {
                                val t = curTrack
                                if (t != null) {
                                    endSeg()
                                    curSeg = Seg().also { t.segs.add(it) }
                                }
                            }
                            "trkpt", "rtept", "wpt" -> {
                                pointKind = if (name == "wpt") 2 else 1
                                pLat = attr(parser, "lat")?.trim()?.toDoubleOrNull() ?: Double.NaN
                                pLon = attr(parser, "lon")?.trim()?.toDoubleOrNull() ?: Double.NaN
                                pEle = null; pTime = null; pName = null; pDesc = null; pType = null; pSym = null
                            }
                            "extensions" -> {
                                skipSubtree(parser)
                                stack.removeAt(stack.size - 1)
                            }
                            "ele", "time", "name", "desc", "type", "sym" -> {
                                text.setLength(0)
                                collecting = true
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        if (collecting) parser.text?.let { text.append(it) }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else localName(parser.name)
                        val parent = stack.lastOrNull()
                        when (name) {
                            "ele" -> if (pointKind != 0) pEle = textValue()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                            "time" -> if (pointKind != 0) pTime = GpxTime.parse(textValue())
                            "name" -> when {
                                pointKind != 0 -> pName = textValue()
                                parent == "trk" || parent == "rte" -> curTrack?.let { if (it.name == null) it.name = textValue() }
                                parent == "metadata" || parent == "gpx" -> if (docName == null) docName = textValue()
                            }
                            "desc" -> when {
                                pointKind != 0 -> pDesc = textValue()
                                parent == "metadata" || parent == "gpx" -> if (docDesc == null) docDesc = textValue()
                            }
                            "type" -> if (pointKind != 0) pType = textValue()
                            "sym" -> if (pointKind != 0) pSym = textValue()
                            "trkpt", "rtept" -> {
                                if (validLatLon(pLat, pLon)) addPoint(GpxPoint(pLat, pLon, pEle, pTime))
                                pointKind = 0
                            }
                            "wpt" -> {
                                if (validLatLon(pLat, pLon)) {
                                    if (waypoints.size < maxWaypoints) {
                                        waypoints.add(GpxWaypoint(pLat, pLon, pEle, pTime, pName, pDesc, pType, pSym))
                                    } else {
                                        waypointsTruncated = true
                                    }
                                }
                                pointKind = 0
                            }
                            "trkseg" -> endSeg()
                            "rte", "trk" -> {
                                endSeg()
                                curTrack = null
                            }
                        }
                        collecting = false
                    }
                }
                event = parser.next()
            }
        } catch (e: GpxException) {
            throw e
        } catch (e: TooLargeException) {
            throw GpxException(GpxException.Reason.TOO_LARGE, e)
        } catch (e: Exception) {
            // XmlPullParserException / IOException / 想定外の値など。読めたところまでは使う。
            broken = e
        }
        endSeg()

        val outTracks = tracks.mapNotNull { t ->
            val segs = t.segs.map { it.pts.toList() }.filter { it.isNotEmpty() }
            if (segs.isEmpty()) null else GpxTrack(t.name, segs, t.isRoute)
        }
        if (outTracks.isEmpty() && waypoints.isEmpty()) {
            when {
                !sawRoot -> throw GpxException(GpxException.Reason.NOT_GPX, broken)
                broken != null -> throw GpxException(GpxException.Reason.BROKEN, broken)
                else -> throw GpxException(GpxException.Reason.EMPTY)
            }
        }
        return GpxData(
            name = docName,
            desc = docDesc,
            tracks = outTracks,
            waypoints = waypoints.toList(),
            version = version,
            decimated = decimated,
            partial = broken != null,
            waypointsTruncated = waypointsTruncated,
        )
    }

    private fun localName(n: String?): String {
        val s = n ?: return ""
        val i = s.indexOf(':')
        return if (i >= 0) s.substring(i + 1) else s
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (localName(parser.getAttributeName(i)) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun skipSubtree(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun validLatLon(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0

    companion object {
        /** 1 ファイルの点の上限（超えたら間引く）。 */
        const val DEFAULT_MAX_POINTS = 200_000
        const val DEFAULT_MAX_WAYPOINTS = 2_000
        /** 読む大きさの上限（64 MB）。 */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    }
}
