package jp.kjtr.drivelogger.dashcam

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.StatFs
import android.util.Log
import jp.kjtr.drivelogger.data.AppDatabase
import jp.kjtr.drivelogger.data.ClipEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画ファイル（アプリ専用の外部領域 `Movies/dashcam/`）と clip 表。容量の管理・保護・削除（DESIGN.md §3.2・§3.4）。
 * DB の書き込みは呼ぶ側のコルーチンで（IO に切り替える）。
 */
class ClipStore(private val context: Context, private val db: AppDatabase) {
    private val dao = db.clips()

    fun all(): Flow<List<ClipEntity>> = dao.all()
    fun forTrip(tripId: Long): Flow<List<ClipEntity>> = dao.forTrip(tripId)
    fun clip(id: Long): Flow<ClipEntity?> = dao.clip(id)
    suspend fun clipNow(id: Long) = withContext(Dispatchers.IO) { dao.clipNow(id) }
    suspend fun allNow() = withContext(Dispatchers.IO) { dao.allNow() }

    /** 保存先（無ければ作る。`.nomedia` でギャラリーに出さない）。外部領域が使えなければ内部の files/。 */
    fun dir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")
        val d = File(base, DIR_NAME)
        if (!d.isDirectory) d.mkdirs()
        val nomedia = File(d, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return d
    }

    fun fileOf(c: ClipEntity): File = File(dir(), c.fileName)

    /** 次のファイル（`DL_yyyyMMdd_HHmmss.mp4`。同じ秒に重なれば `_2` など）。 */
    fun newFile(atMs: Long): File {
        val d = dir()
        val base = "DL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(atMs))
        var f = File(d, "$base.mp4")
        var n = 2
        while (f.exists()) f = File(d, "${base}_${n++}.mp4")
        return f
    }

    /** 端末の空き [byte]（保存先のボリューム）。 */
    fun freeBytes(): Long = runCatching { StatFs(dir().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    suspend fun insert(c: ClipEntity): Long = withContext(Dispatchers.IO) { dao.insert(c) }

    suspend fun update(c: ClipEntity) = withContext(Dispatchers.IO) { dao.update(c) }

    suspend fun byFile(name: String): ClipEntity? = withContext(Dispatchers.IO) { dao.byFile(name) }

    suspend fun setProtected(id: Long, on: Boolean, reason: String?) = withContext(Dispatchers.IO) {
        dao.setProtected(id, on, if (on) reason else null, if (on) System.currentTimeMillis() else null)
    }

    /** ファイルと行を消す。 */
    suspend fun delete(c: ClipEntity) = withContext(Dispatchers.IO) {
        runCatching { fileOf(c).delete() }
        dao.delete(c.id)
        thumbFile(c).delete()
    }

    /**
     * 容量の判定をして、消すべきファイルを消す（[activeFile] = 録画中のファイル。大きさはファイルから読む）。
     * 結果（警告など）を返す。
     */
    suspend fun enforce(limitBytes: Long, protectedLimitBytes: Long, nextBytes: Long, activeFile: String?): StoragePlan = withContext(Dispatchers.IO) {
        val rows = dao.allNow()
        val clips = rows.map { c ->
            val active = c.fileName == activeFile
            val size = if (active || c.status == ClipEntity.STATUS_RECORDING) File(dir(), c.fileName).length() else c.sizeBytes
            StoredClip(c.id, c.startedAt, size, c.protected, active || c.status == ClipEntity.STATUS_RECORDING, longRec = c.isLong)
        }
        val plan = LoopStoragePolicy.plan(clips, limitBytes, protectedLimitBytes, nextBytes, freeBytes())
        val byId = rows.associateBy { it.id }
        for (d in plan.delete) {
            val c = byId[d.id] ?: continue
            Log.i(TAG, "loop delete ${c.fileName} (${c.sizeBytes} B)")
            runCatching { File(dir(), c.fileName).delete() }
            dao.delete(c.id)
            thumbFile(c).delete()
        }
        plan
    }

    /**
     * 長時間録画のファイルの合計 [byte]（0.7.0〜。録画中のファイルはファイルから読む）。ループの上限には入らない。
     * [logId] を渡せばその動画ログ（今の長時間録画）の分だけ。
     */
    suspend fun longBytes(logId: Long? = null): Long = withContext(Dispatchers.IO) {
        dao.allNow().filter { it.isLong && (logId == null || it.logId == logId) }.sumOf { c ->
            if (c.status == ClipEntity.STATUS_RECORDING) File(dir(), c.fileName).length() else c.sizeBytes
        }
    }

    /**
     * 起動時の突き合わせ: 録画中のまま残った行（落ちた）は、ファイルがあれば仕上がったものとして長さを読み直す（無ければ消す）。
     * 行の無いファイルは取り込み、ファイルの無い行は消す。
     */
    suspend fun reconcile(recordingFile: String?) = withContext(Dispatchers.IO) {
        val d = dir()
        val rows = dao.allNow()
        val names = HashSet<String>()
        for (c in rows) {
            names.add(c.fileName)
            if (c.fileName == recordingFile) continue
            val f = File(d, c.fileName)
            if (!f.exists() || f.length() == 0L) {
                if (c.status != ClipEntity.STATUS_RECORDING || !f.exists()) {
                    dao.delete(c.id)
                    runCatching { f.delete() }
                }
                continue
            }
            if (c.status == ClipEntity.STATUS_RECORDING) {
                val dur = durationOf(f)
                dao.update(c.copy(status = if (dur > 0) ClipEntity.STATUS_DONE else ClipEntity.STATUS_FAILED, durationMs = dur, sizeBytes = f.length(), endedAt = c.startedAt + dur))
            }
        }
        d.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") && it.name !in names && it.name != recordingFile }?.forEach { f ->
            val started = parseStart(f.name) ?: f.lastModified()
            val dur = durationOf(f)
            if (f.length() > 0) {
                runCatching {
                    dao.insert(ClipEntity(fileName = f.name, tripId = null, startedAt = started, endedAt = started + dur, durationMs = dur, sizeBytes = f.length(), status = ClipEntity.STATUS_DONE))
                }
            }
        }
    }

    /** サムネイルの置き場（cache/thumbs/）。 */
    fun thumbFile(c: ClipEntity): File = File(File(context.cacheDir, "thumbs").apply { mkdirs() }, c.fileName.removeSuffix(".mp4") + ".jpg")

    companion object {
        private const val TAG = "DL-Clips"
        const val DIR_NAME = "dashcam"

        fun durationOf(f: File): Long = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }.getOrDefault(0L)

        /** 動画の正立したときの幅と高さ（回転のメタデータを反映。読めなければ 0, 0）。 */
        fun uprightSizeOf(f: File): Pair<Int, Int> = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 == 90) h to w else w to h
            } finally {
                r.release()
            }
        }.getOrDefault(0 to 0)

        /** `DL_20261013_140327(.._2).mp4` → 開始時刻（端末のタイムゾーン）。 */
        fun parseStart(name: String): Long? {
            val m = Regex("""DL_(\d{8}_\d{6})""").find(name) ?: return null
            return runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse(m.groupValues[1])?.time }.getOrNull()
        }
    }
}
