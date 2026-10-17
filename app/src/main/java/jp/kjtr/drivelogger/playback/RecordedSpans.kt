package jp.kjtr.drivelogger.playback

/** 録画していた時間帯 [startMs, endMs]。 */
data class TimeSpan(val startMs: Long, val endMs: Long) {
    operator fun contains(t: Long) = t in startMs..endMs
}

/**
 * 軌跡のうち、録画していた時間帯に入る部分（純ロジック。単体テスト: RecordedSpansTest）。
 * トリップの詳細の地図で「録画した区間」を太い線で重ねるのに使う。
 */
object RecordedSpans {
    /**
     * [segments]（時刻の順の点の区間）から、[spans] のどれかに入る点が続くところを 1 本ずつ取り出す。
     * 終わっていない時間帯は endMs に Long.MAX_VALUE を入れておく。
     */
    fun <T> pick(segments: List<List<T>>, spans: List<TimeSpan>, time: (T) -> Long): List<List<T>> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMs }
        val out = ArrayList<List<T>>()
        for (seg in segments) {
            var cur = ArrayList<T>()
            for (p in seg) {
                val t = time(p)
                if (sorted.any { t in it }) {
                    cur.add(p)
                } else if (cur.isNotEmpty()) {
                    out.add(cur)
                    cur = ArrayList()
                }
            }
            if (cur.isNotEmpty()) out.add(cur)
        }
        return out.filter { it.size >= 2 }
    }

    /** 合計の録画時間 [ms]（重なりは 1 回だけ数える）。 */
    fun totalMs(spans: List<TimeSpan>): Long {
        var total = 0L
        var curStart = Long.MIN_VALUE
        var curEnd = Long.MIN_VALUE
        for (s in spans.sortedBy { it.startMs }) {
            if (s.startMs > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = s.startMs
                curEnd = s.endMs
            } else if (s.endMs > curEnd) {
                curEnd = s.endMs
            }
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }
}
