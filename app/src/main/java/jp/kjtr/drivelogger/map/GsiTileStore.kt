package jp.kjtr.drivelogger.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 地理院タイルの取得とキャッシュ（DESIGN.md §3.7）。
 *
 * - ディスク: `cacheDir/gsi_tiles/<種類>/<z>/<x>/<y>.<拡張子>`。上限 [TileCachePolicy.LIMIT_BYTES]（超えたら取得の古い順に消す）。
 *   取得から [TileCachePolicy.MAX_AGE_MS] は取り直さない。無いタイル（404）は 0 バイトのファイルで覚える（同じ期間）。
 * - メモリ: デコードしたタイル（RGB_565）を LRU で 16 MB まで。
 * - 通信: 同時に 2 本まで（2 本のスレッド）。User-Agent はアプリ名。取れなかったタイルは 60 秒試さない。続けて失敗したら 30 秒休む。
 *   [fetchAllowed] が false（地図の焼き込みが OFF）・ネットワークが無いときは通信しない（ディスクにあるものだけ使う）。
 * - [tile] は描く側（焼き込みのスレッド・画面）から呼ぶ。無ければ null を返して裏で読み込み/取得を始める。読めたら [version] が増える。
 */
class GsiTileStore(
    private val context: Context,
    private val userAgent: String,
    private val fetchAllowed: () -> Boolean,
) : TileSource {
    private val dir = File(context.cacheDir, "gsi_tiles")
    private val mem = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    /** 0 バイトの印（そこにはタイルが無い）を覚えたもの。 */
    private val empty = ConcurrentHashMap.newKeySet<String>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val pending = ArrayDeque<TileKey>()
    private val lock = Any()
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "dl-gsi-tiles").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var consecutiveFailures = 0
    private var pauseUntil = 0L
    private val totalBytes = AtomicLong(-1)
    private val fetchedCount = AtomicLong()

    private val _version = MutableStateFlow(0)
    /** タイルが読めるたびに増える（描く側が作り直すきっかけ）。 */
    val version: StateFlow<Int> = _version

    // 先読みの間引き
    private var lastPlanKey: String? = null
    private var lastPlanAt = 0L

    /** 描く側から。メモリにあれば返す。無ければ null（裏で読み込み・取得を始める）。 */
    override val tileVersion: Int get() = _version.value

    override fun tile(key: TileKey): Bitmap? {
        val k = key.path
        mem.get(k)?.let { return it }
        if (k in empty) return null
        enqueue(key, front = true)
        return null
    }

    /**
     * 走行中の先読み（現在地のまわり・進む向き）。表示範囲の外接円の半径 [viewRadiusPx]（世界ピクセル）。
     * 中心のタイルが変わる・向きが大きく変わる・15 秒たったときだけ計画し直す。
     */
    fun prefetch(layer: GsiLayer, z: Int, lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double?, viewRadiusPx: Double) {
        val now = System.currentTimeMillis()
        val (tx, ty) = TileMath.tileOf(lat, lon, z)
        val hBucket = headingDeg?.let { ((it + 360) % 360 / 45).toInt() } ?: -1
        val moving = (speedKmh ?: 0.0) >= TilePrefetch.MOVING_KMH
        val planKey = "${layer.id}/$z/$tx/$ty/$hBucket/$moving"
        if (planKey == lastPlanKey && now - lastPlanAt < 15_000) return
        lastPlanKey = planKey
        lastPlanAt = now
        val plan = TilePrefetch.plan(lat, lon, headingDeg, speedKmh, z, viewRadiusPx)
        synchronized(lock) {
            // まだ始めていない古い場所の先読みは捨てる（描く側が求めたものは残す）
            pending.removeAll { p ->
                val k = p.path
                if (k in prefetchKeys) {
                    prefetchKeys.remove(k)
                    queued.remove(k)
                    true
                } else {
                    false
                }
            }
        }
        for ((x, y) in plan) {
            val key = TileKey(layer, z, x, y)
            if (mem.get(key.path) != null || key.path in empty) continue
            enqueue(key, front = false)
        }
    }

    /**
     * 書き出し（あとから重ねる表示の地図）の前に、走った範囲のタイルをそろえる（0.4.0〜）。キャッシュにあればそれを、無ければ
     * 地理院の作法どおりの間隔で取りに行く（地図を入れる設定が OFF なら取りに行かない＝キャッシュだけ）。
     * [timeoutMs] 待っても来ないものは灰色のまま書き出す。そろったタイルの数と、求めた数を返す。
     */
    suspend fun warmAlong(
        layer: GsiLayer, z: Int, samples: List<Triple<Double, Double, Double?>>, viewRadiusPx: Double, timeoutMs: Long, maxTiles: Int = 600,
    ): Pair<Int, Int> {
        val keys = LinkedHashSet<TileKey>()
        for ((lat, lon, heading) in samples) {
            for ((x, y) in TilePrefetch.plan(lat, lon, heading, 0.0, z, viewRadiusPx)) {
                keys.add(TileKey(layer, z, x, y))
                if (keys.size >= maxTiles) break
            }
            if (keys.size >= maxTiles) break
        }
        fun ready(k: TileKey) = mem.get(k.path) != null || k.path in empty
        keys.forEach { if (!ready(it)) enqueue(it, front = false) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && keys.any { !ready(it) && failedUntil[it.path] == null }) {
            kotlinx.coroutines.delay(250)
        }
        return keys.count { ready(it) } to keys.size
    }

    /** 先読みで入れたもの（描く側が求めたら前へ移す）。 */
    private val prefetchKeys = ConcurrentHashMap.newKeySet<String>()

    private fun enqueue(key: TileKey, front: Boolean) {
        val k = key.path
        val until = failedUntil[k]
        if (until != null && System.currentTimeMillis() < until) return
        synchronized(lock) {
            if (!queued.add(k)) {
                // 描く側が求めたら前へ
                if (front && k in prefetchKeys) {
                    prefetchKeys.remove(k)
                    pending.remove(key)
                    pending.addFirst(key)
                }
                return
            }
            if (front) pending.addFirst(key) else {
                pending.addLast(key)
                prefetchKeys.add(k)
            }
        }
        workers.execute { work() }
    }

    private fun work() {
        val key = synchronized(lock) { pending.removeFirstOrNull() } ?: return
        val k = key.path
        try {
            load(key)
        } catch (e: Throwable) {
            Log.w(TAG, "tile $k: ${e.javaClass.simpleName} ${e.message}")
            failedUntil[k] = System.currentTimeMillis() + TileCachePolicy.RETRY_MS
        } finally {
            prefetchKeys.remove(k)
            queued.remove(k)
        }
    }

    private fun load(key: TileKey) {
        val k = key.path
        val f = File(dir, k)
        val now = System.currentTimeMillis()
        if (f.isFile) {
            val fresh = TileCachePolicy.isFresh(f.lastModified(), now)
            if (f.length() == 0L) {
                if (fresh) {
                    empty.add(k)
                    return
                }
            } else {
                decode(f)?.let { put(k, it) }
                if (fresh) return
            }
        }
        if (!canFetch(now)) {
            // 取れない（OFF・オフライン・休み中）。しばらく描く側から求められても見に行かない
            if (mem.get(k) == null) failedUntil[k] = now + 5_000
            return
        }
        fetch(key, f)
    }

    private fun canFetch(now: Long): Boolean {
        if (!fetchAllowed()) return false
        if (now < pauseUntil) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { cm.activeNetwork != null }.getOrDefault(false)
    }

    private fun fetch(key: TileKey, f: File) {
        val k = key.path
        val conn = URL(key.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            val code = conn.responseCode
            when {
                code == 404 -> {
                    // そこにはタイルが無い（海・範囲外）。0 バイトで覚える
                    writeFile(f, ByteArray(0))
                    empty.add(k)
                    onFetchResult(true)
                }
                code in 200..299 -> {
                    val bytes = conn.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_TILE_BYTES) error("too large")
                        }
                        out.toByteArray()
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions()) ?: error("decode failed")
                    writeFile(f, bytes)
                    put(k, bmp)
                    fetchedCount.incrementAndGet()
                    onFetchResult(true)
                }
                else -> {
                    failedUntil[k] = System.currentTimeMillis() + TileCachePolicy.RETRY_MS
                    onFetchResult(false)
                }
            }
        } catch (e: Exception) {
            failedUntil[k] = System.currentTimeMillis() + TileCachePolicy.RETRY_MS
            onFetchResult(false)
            Log.w(TAG, "fetch ${key.path}: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun onFetchResult(ok: Boolean) {
        synchronized(lock) {
            if (ok) {
                consecutiveFailures = 0
            } else if (++consecutiveFailures >= 3) {
                consecutiveFailures = 0
                pauseUntil = System.currentTimeMillis() + 30_000
            }
        }
    }

    private fun writeFile(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        val old = if (f.isFile) f.length() else 0L
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        f.setLastModified(System.currentTimeMillis())
        addBytes(bytes.size - old)
    }

    private fun addBytes(delta: Long) {
        if (totalBytes.get() < 0) totalBytes.set(scan().sumOf { it.bytes }) else totalBytes.addAndGet(delta)
        if (totalBytes.get() > TileCachePolicy.LIMIT_BYTES) enforce()
    }

    /** 上限を超えた分を古い順に消す。 */
    @Synchronized
    private fun enforce() {
        val entries = scan()
        val del = TileCachePolicy.evict(entries, TileCachePolicy.LIMIT_BYTES)
        var freed = 0L
        for (e in del) {
            if (File(dir, e.path).delete()) freed += e.bytes
            mem.remove(e.path)
            empty.remove(e.path)
        }
        totalBytes.set(entries.sumOf { it.bytes } - freed)
        Log.i(TAG, "cache evicted ${del.size} tiles (${freed / 1024} KB)")
    }

    private fun scan(): List<TileCachePolicy.Entry> {
        if (!dir.isDirectory) return emptyList()
        val base = dir.path.length + 1
        return dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { TileCachePolicy.Entry(it.path.substring(base).replace(File.separatorChar, '/'), it.length(), it.lastModified()) }
            .toList()
    }

    private fun decode(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.path, decodeOptions()) }.getOrNull()

    private fun decodeOptions() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    private fun put(k: String, bmp: Bitmap) {
        mem.put(k, bmp)
        _version.value = _version.value + 1
    }

    /** キャッシュの大きさ [byte]（設定画面）。 */
    suspend fun usageBytes(): Long = withContext(Dispatchers.IO) {
        val n = scan().sumOf { it.bytes }
        totalBytes.set(n)
        n
    }

    /** キャッシュを消す（設定画面）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { pending.clear() }
        mem.evictAll()
        empty.clear()
        failedUntil.clear()
        runCatching { dir.deleteRecursively() }
        totalBytes.set(0)
        _version.value = _version.value + 1
    }

    /** この起動で取得したタイルの数（確認用のログ）。 */
    val fetched: Long get() = fetchedCount.get()

    companion object {
        private const val TAG = "DL-Tiles"
        private const val MAX_TILE_BYTES = 1024 * 1024
    }
}
