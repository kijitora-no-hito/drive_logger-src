package jp.kjtr.drivelogger.ranking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * ランキングの「開拓したマス」を数えるための、記録ごとのマスの覚え（端末の中だけ。noBackupFilesDir のファイル。送らない）。
 * 記録が千本を超えても、アプリを開くたびに全部の点を読み直さないように、記録の鍵（点の数・距離・なめらかさ）が同じなら覚えたマスを使う。
 * 自転車ルートロガー・ドライブロガーで同じ形（パッケージだけ違う）。
 */
class RankingCellCache(private val file: File) {
    private val lock = Mutex()
    private var mem: HashMap<Long, Pair<String, LongArray>>? = null

    /**
     * [items]（記録の id と鍵）のマス。覚えていない・鍵が違う記録だけ [compute] で数える。
     * [items] に無い記録の覚えは捨てる（消した記録）。
     */
    suspend fun cells(items: List<Pair<Long, String>>, compute: suspend (Long) -> Set<Long>): Map<Long, Set<Long>> = lock.withLock {
        val m = mem ?: withContext(Dispatchers.IO) { load() }.also { mem = it }
        var changed = false
        val out = HashMap<Long, Set<Long>>(items.size * 2)
        for ((id, key) in items) {
            val hit = m[id]
            val arr = if (hit != null && hit.first == key) hit.second else {
                val a = compute(id).toLongArray()
                m[id] = key to a
                changed = true
                a
            }
            out[id] = arr.toHashSet()
        }
        val keep = items.mapTo(HashSet()) { it.first }
        if (m.keys.retainAll(keep)) changed = true
        if (changed) withContext(Dispatchers.IO) { save(m) }
        out
    }

    private fun load(): HashMap<Long, Pair<String, LongArray>> =
        runCatching { BufferedInputStream(file.inputStream()).use { read(it) } }.getOrNull() ?: HashMap()

    private fun save(m: Map<Long, Pair<String, LongArray>>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            BufferedOutputStream(tmp.outputStream()).use { write(it, m) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val MAGIC = 0x524b4331 // "RKC1"

        fun write(out: OutputStream, m: Map<Long, Pair<String, LongArray>>) {
            val d = DataOutputStream(out)
            d.writeInt(MAGIC)
            d.writeInt(m.size)
            for ((id, v) in m) {
                d.writeLong(id)
                d.writeUTF(v.first)
                d.writeInt(v.second.size)
                for (c in v.second) d.writeLong(c)
            }
            d.flush()
        }

        /** 読めなければ null（形が違う・途中で切れた）。 */
        fun read(input: InputStream): HashMap<Long, Pair<String, LongArray>>? = runCatching {
            val d = DataInputStream(input)
            if (d.readInt() != MAGIC) return null
            val n = d.readInt()
            if (n < 0 || n > 10_000_000) return null
            val m = HashMap<Long, Pair<String, LongArray>>(n * 2)
            repeat(n) {
                val id = d.readLong()
                val key = d.readUTF()
                val k = d.readInt()
                if (k < 0 || k > 10_000_000) return null
                m[id] = key to LongArray(k) { d.readLong() }
            }
            m
        }.getOrNull()
    }
}
