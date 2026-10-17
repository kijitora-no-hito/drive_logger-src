package jp.kjtr.drivelogger.gpx

import java.util.Locale

/**
 * GPX 1.1 の書き出し。要素の順は GPX 1.1 のスキーマどおり（metadata → wpt → rte → trk、
 * wpt の中は ele → time → name → desc → sym → type）。
 * `rte` として書くのは [GpxTrack.isRoute] のものだけ（区間はつなげて 1 本の rte にする）。
 */
object GpxWriter {
    const val CREATOR = "CycleRouteLogger (jp.kjtr.drivelogger)"

    fun write(out: Appendable, data: GpxData, metadataTimeMs: Long? = null) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"").append(escape(CREATOR)).append("\"")
        out.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        out.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        out.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        if (data.name != null || data.desc != null || metadataTimeMs != null) {
            out.append("  <metadata>\n")
            data.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
            data.desc?.let { out.append("    <desc>").append(escape(it)).append("</desc>\n") }
            metadataTimeMs?.let { out.append("    <time>").append(GpxTime.format(it)).append("</time>\n") }
            out.append("  </metadata>\n")
        }
        for (w in data.waypoints) {
            out.append("  <wpt").append(latLonAttrs(w.lat, w.lon)).append(">\n")
            w.ele?.let { out.append("    <ele>").append(fmtEle(it)).append("</ele>\n") }
            w.timeMs?.let { out.append("    <time>").append(GpxTime.format(it)).append("</time>\n") }
            w.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
            w.desc?.let { out.append("    <desc>").append(escape(it)).append("</desc>\n") }
            w.sym?.let { out.append("    <sym>").append(escape(it)).append("</sym>\n") }
            w.type?.let { out.append("    <type>").append(escape(it)).append("</type>\n") }
            out.append("  </wpt>\n")
        }
        for (t in data.tracks.filter { it.isRoute }) {
            out.append("  <rte>\n")
            t.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
            for (seg in t.segments) for (p in seg) writePoint(out, "rtept", p, "    ")
            out.append("  </rte>\n")
        }
        for (t in data.tracks.filter { !it.isRoute }) {
            out.append("  <trk>\n")
            t.name?.let { out.append("    <name>").append(escape(it)).append("</name>\n") }
            for (seg in t.segments) {
                out.append("    <trkseg>\n")
                for (p in seg) writePoint(out, "trkpt", p, "      ")
                out.append("    </trkseg>\n")
            }
            out.append("  </trk>\n")
        }
        out.append("</gpx>\n")
    }

    private fun writePoint(out: Appendable, tag: String, p: GpxPoint, indent: String) {
        out.append(indent).append('<').append(tag).append(latLonAttrs(p.lat, p.lon))
        if (p.ele == null && p.timeMs == null) {
            out.append("/>\n")
            return
        }
        out.append('>')
        p.ele?.let { out.append("<ele>").append(fmtEle(it)).append("</ele>") }
        p.timeMs?.let { out.append("<time>").append(GpxTime.format(it)).append("</time>") }
        out.append("</").append(tag).append(">\n")
    }

    private fun latLonAttrs(lat: Double, lon: Double): String =
        " lat=\"" + String.format(Locale.ROOT, "%.7f", lat) + "\" lon=\"" + String.format(Locale.ROOT, "%.7f", lon) + "\""

    private fun fmtEle(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c < ' ' && c != '\n' && c != '\t' && c != '\r') Unit else sb.append(c)
            }
        }
        return sb.toString()
    }
}
