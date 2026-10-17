package jp.kjtr.drivelogger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trip WHERE status = 2 ORDER BY startedAt DESC")
    fun finished(): Flow<List<TripEntity>>

    /** 開いているものも含めて全部（一覧。開いているものが先頭）。 */
    @Query("SELECT * FROM trip ORDER BY (status != 2) DESC, startedAt DESC")
    fun all(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip WHERE id = :id")
    fun trip(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun tripNow(id: Long): TripEntity?

    /** 開いているドライブ（同時に 1 本まで。0.6.0〜。0.5.0 までは「記録の途中で終わったもの」）。 */
    @Query("SELECT * FROM trip WHERE status != 2 ORDER BY startedAt DESC LIMIT 1")
    suspend fun openDrive(): TripEntity?

    @Query("SELECT * FROM trip WHERE status != 2 ORDER BY startedAt DESC")
    suspend fun openDrives(): List<TripEntity>

    @Query("UPDATE trip SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int, at: Long)

    @Query("SELECT * FROM trip_point WHERE logId = :logId ORDER BY id")
    suspend fun pointsOfLog(logId: Long): List<TripPointEntity>

    @Query("DELETE FROM trip_point WHERE logId = :logId")
    suspend fun deletePointsOfLog(logId: Long)

    @Query("SELECT MAX(segment) FROM trip_point WHERE tripId = :tripId")
    suspend fun maxSegment(tripId: Long): Int?

    @Query("SELECT * FROM trip WHERE status = 2 ORDER BY startedAt")
    suspend fun allFinished(): List<TripEntity>

    @Insert
    suspend fun insertTrip(t: TripEntity): Long

    @Update
    suspend fun updateTrip(t: TripEntity)

    @Query("UPDATE trip SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Insert
    suspend fun insertPoint(p: TripPointEntity): Long

    @Insert
    suspend fun insertPoints(list: List<TripPointEntity>)

    @Query("SELECT * FROM trip_point WHERE tripId = :tripId ORDER BY id")
    suspend fun points(tripId: Long): List<TripPointEntity>

    /** 動画の再生と同期するための点（どのトリップでも。時刻の順）。 */
    @Query("SELECT * FROM trip_point WHERE time BETWEEN :from AND :to ORDER BY time, id")
    suspend fun pointsBetween(from: Long, to: Long): List<TripPointEntity>

    @Query("SELECT COUNT(*) FROM trip_point WHERE tripId = :tripId")
    suspend fun pointCount(tripId: Long): Int

    @Insert
    suspend fun insertEvent(e: TripEventEntity): Long

    @Query("UPDATE trip_event SET endedAt = :at WHERE tripId = :tripId AND type = :type AND endedAt IS NULL")
    suspend fun endEvents(tripId: Long, type: String, at: Long)

    @Query("SELECT * FROM trip_event WHERE tripId = :tripId ORDER BY startedAt, id")
    fun events(tripId: Long): Flow<List<TripEventEntity>>

    @Query("SELECT * FROM trip_event WHERE tripId = :tripId ORDER BY startedAt, id")
    suspend fun eventsNow(tripId: Long): List<TripEventEntity>
}

@Dao
interface ClipDao {
    @Query("SELECT * FROM clip ORDER BY startedAt DESC")
    fun all(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clip ORDER BY startedAt")
    suspend fun allNow(): List<ClipEntity>

    @Query("SELECT * FROM clip WHERE tripId = :tripId ORDER BY startedAt")
    fun forTrip(tripId: Long): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clip WHERE id = :id")
    fun clip(id: Long): Flow<ClipEntity?>

    @Query("SELECT * FROM clip WHERE id = :id")
    suspend fun clipNow(id: Long): ClipEntity?

    @Query("SELECT * FROM clip WHERE fileName = :name")
    suspend fun byFile(name: String): ClipEntity?

    @Insert
    suspend fun insert(c: ClipEntity): Long

    @Update
    suspend fun update(c: ClipEntity)

    @Query("UPDATE clip SET protected = :on, protectReason = :reason, protectedAt = :at WHERE id = :id")
    suspend fun setProtected(id: Long, on: Boolean, reason: String?, at: Long?)

    @Query("DELETE FROM clip WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM clip WHERE logId = :logId ORDER BY startedAt")
    suspend fun forLogNow(logId: Long): List<ClipEntity>

    /** ドライブを消すとき、動画は残して動画ログとの紐づけだけ外す（ドライブとの紐づけは外部キーで null になる）。 */
    @Query("UPDATE clip SET logId = NULL WHERE tripId = :tripId")
    suspend fun unlinkLogsOfTrip(tripId: Long)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(l: DriveLogEntity): Long

    @Update
    suspend fun update(l: DriveLogEntity)

    @Query("SELECT * FROM drive_log WHERE id = :id")
    suspend fun logNow(id: Long): DriveLogEntity?

    @Query("SELECT * FROM drive_log WHERE tripId = :tripId ORDER BY startedAt, id")
    fun forTrip(tripId: Long): Flow<List<DriveLogEntity>>

    @Query("SELECT * FROM drive_log WHERE tripId = :tripId ORDER BY startedAt, id")
    suspend fun forTripNow(tripId: Long): List<DriveLogEntity>

    @Query("UPDATE drive_log SET endedAt = :at WHERE id = :id")
    suspend fun close(id: Long, at: Long)

    @Query("DELETE FROM drive_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(p: PhotoEntity): Long

    @Query("DELETE FROM photo WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM photo WHERE tripId = :tripId ORDER BY takenAt, id")
    fun forTrip(tripId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photo WHERE tripId = :tripId ORDER BY takenAt, id")
    suspend fun forTripNow(tripId: Long): List<PhotoEntity>

    /** トリップに紐づいた写真すべて（まとめの地図の写真の位置）。 */
    @Query("SELECT * FROM photo WHERE tripId IS NOT NULL ORDER BY takenAt, id")
    fun linked(): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photo WHERE uri = :uri AND tripId = :tripId")
    suspend fun countUri(uri: String, tripId: Long): Int

    /** 同じトリップで、撮影時刻が [from]〜[to] の写真の数（Photo Picker の URI は元の URI と違うので時刻でも重複を見る）。 */
    @Query("SELECT COUNT(*) FROM photo WHERE tripId = :tripId AND takenAt BETWEEN :from AND :to")
    suspend fun countNear(tripId: Long, from: Long, to: Long): Int
}

@Dao
interface ParkDao {
    @Insert
    suspend fun insert(p: ParkEntity): Long

    @Query("UPDATE park SET endedAt = :at, walkDistanceM = :walkM WHERE id = :id")
    suspend fun close(id: Long, at: Long, walkM: Double)

    @Query("UPDATE park SET walkDistanceM = :walkM WHERE id = :id")
    suspend fun setWalk(id: Long, walkM: Double)

    /** 終わっていない駐車を [at] で閉じる（落ちて再開・終了して保存したとき）。 */
    @Query("UPDATE park SET endedAt = :at WHERE tripId = :tripId AND endedAt IS NULL")
    suspend fun closeOpen(tripId: Long, at: Long)

    @Query("SELECT * FROM park WHERE tripId = :tripId ORDER BY startedAt, id")
    fun forTrip(tripId: Long): Flow<List<ParkEntity>>

    @Query("SELECT * FROM park WHERE tripId = :tripId ORDER BY startedAt, id")
    suspend fun forTripNow(tripId: Long): List<ParkEntity>

    @Insert
    suspend fun insertWalk(p: WalkPointEntity): Long

    @Query("SELECT * FROM walk_point WHERE tripId = :tripId ORDER BY time, id")
    suspend fun walkForTrip(tripId: Long): List<WalkPointEntity>
}

/*
 * 版の履歴（公開後は消さずに Migration を足す。fallbackToDestructiveMigration は使わない）
 *   1: 0.1.0 / 0.2.0（トリップ・点・出来事・動画）
 *   2: 0.3.0（clip.burnLayout＝焼き込みの配置、trip_point の元の GPS の値と点の出どころ）
 *   3: 0.4.0（写真 photo、駐車・寄り道の区間 park、駐車中の徒歩の点 walk_point）
 *   4: 0.6.0（ドライブの中の GPS ログ・動画ログ drive_log、trip_point.logId・clip.logId・trip.updatedAt。データの移行は LogMigration）
 *   5: 0.7.0（録画の方式 clip.recordMode・drive_log.recordMode。0 = ループ / 1 = 長時間）
 *   6: 0.8.0（点の高度の精度 trip_point.altAccuracy・測位の出どころ trip_point.provider・ドライブの乗り物 trip.vehicle）
 */
@Database(
    entities = [
        TripEntity::class, TripPointEntity::class, TripEventEntity::class, ClipEntity::class, PhotoEntity::class,
        ParkEntity::class, WalkPointEntity::class, DriveLogEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun clips(): ClipDao
    abstract fun photos(): PhotoDao
    abstract fun parks(): ParkDao
    abstract fun logs(): LogDao

    companion object {
        /** 1 → 2 の SQL（単体テスト MigrationTest が v1 のスキーマに流して v2 のスキーマと突き合わせる）。 */
        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE `clip` ADD COLUMN `burnLayout` TEXT",
            "ALTER TABLE `trip_point` ADD COLUMN `rawLat` REAL",
            "ALTER TABLE `trip_point` ADD COLUMN `rawLon` REAL",
            "ALTER TABLE `trip_point` ADD COLUMN `rawAccuracy` REAL",
            "ALTER TABLE `trip_point` ADD COLUMN `rawSpeed` REAL",
            "ALTER TABLE `trip_point` ADD COLUMN `source` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_1_2_SQL) db.execSQL(sql)
            }
        }

        /**
         * 2 → 3 の SQL: 写真の表 photo、駐車・寄り道の区間 park、駐車中の徒歩の点 walk_point と索引（既存の表は変えない）。
         * 単体テスト MigrationTest がスキーマ v3 と突き合わせる。
         */
        val MIGRATION_2_3_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `photo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `uri` TEXT NOT NULL, " +
                "`takenAt` INTEGER NOT NULL, `lat` REAL, `lon` REAL, `locationSource` TEXT, `thumbPath` TEXT, `ownCapture` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `captureMode` TEXT, `burnIn` INTEGER NOT NULL, `parkId` INTEGER, " +
                "FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_photo_tripId` ON `photo` (`tripId`)",
            "CREATE INDEX IF NOT EXISTS `index_photo_takenAt` ON `photo` (`takenAt`)",
            "CREATE TABLE IF NOT EXISTS `park` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER, `lat` REAL, `lon` REAL, `walkDistanceM` REAL NOT NULL, " +
                "FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_park_tripId` ON `park` (`tripId`)",
            "CREATE TABLE IF NOT EXISTS `walk_point` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `parkId` INTEGER NOT NULL, " +
                "`time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracy` REAL, " +
                "FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`parkId`) REFERENCES `park`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_walk_point_tripId` ON `walk_point` (`tripId`)",
            "CREATE INDEX IF NOT EXISTS `index_walk_point_parkId` ON `walk_point` (`parkId`)",
            // 表示の入れ方（あとから重ねる＝1）
            "ALTER TABLE `clip` ADD COLUMN `laterOverlay` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_2_3_SQL) db.execSQL(sql)
            }
        }

        /**
         * 3 → 4 の SQL（0.6.0）: GPS ログ・動画ログの表 drive_log、点・動画のログの紐づけ、ドライブの最終更新。
         * この後に [LogMigration.run] で既存のドライブをログに分ける（単体テスト MigrationTest がスキーマ v4 と突き合わせる）。
         */
        val MIGRATION_3_4_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `drive_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `distanceM` REAL NOT NULL, `movingMs` INTEGER NOT NULL, `stillMs` INTEGER NOT NULL, " +
                "`maxSpeedMps` REAL NOT NULL, `pointCount` INTEGER NOT NULL, " +
                "FOREIGN KEY(`tripId`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_drive_log_tripId` ON `drive_log` (`tripId`)",
            "ALTER TABLE `trip` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `trip_point` ADD COLUMN `logId` INTEGER",
            "CREATE INDEX IF NOT EXISTS `index_trip_point_logId` ON `trip_point` (`logId`)",
            "ALTER TABLE `clip` ADD COLUMN `logId` INTEGER",
            "CREATE INDEX IF NOT EXISTS `index_clip_logId` ON `clip` (`logId`)",
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_3_4_SQL) db.execSQL(sql)
                LogMigration.run(SupportSqlRunner(db))
            }
        }

        /**
         * 4 → 5 の SQL（0.7.0）: 録画の方式（0 = ループ / 1 = 長時間）を動画ファイルと動画ログに。既存の動画・動画ログはループ（既定 0）。
         * 単体テスト MigrationTest がスキーマ v5 と突き合わせる。
         */
        val MIGRATION_4_5_SQL = listOf(
            "ALTER TABLE `clip` ADD COLUMN `recordMode` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `drive_log` ADD COLUMN `recordMode` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_4_5_SQL) db.execSQL(sql)
            }
        }

        /**
         * 5 → 6 の SQL（0.8.0）: 点の高度の精度 trip_point.altAccuracy と測位の出どころ trip_point.provider（0 = 不明＝既存の点）、
         * ドライブの乗り物 trip.vehicle（0 = 車＝既存のドライブ）。
         * 単体テスト MigrationTest がスキーマ v6 と突き合わせる。
         */
        val MIGRATION_5_6_SQL = listOf(
            "ALTER TABLE `trip_point` ADD COLUMN `altAccuracy` REAL",
            "ALTER TABLE `trip_point` ADD COLUMN `provider` INTEGER NOT NULL DEFAULT 0",
            // 乗り物（0 = 車。既存のドライブは車）
            "ALTER TABLE `trip` ADD COLUMN `vehicle` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_5_6_SQL) db.execSQL(sql)
            }
        }

        /** 版を上げたらここに Migration を足す。 */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "drive_logger.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/** Migration の中の [SqlRunner]（Room の SupportSQLiteDatabase）。 */
class SupportSqlRunner(private val db: SupportSQLiteDatabase) : SqlRunner {
    override fun rows(sql: String, vararg args: Any?): List<List<Any?>> {
        val out = ArrayList<List<Any?>>()
        db.query(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    (0 until c.columnCount).map { i ->
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    },
                )
            }
        }
        return out
    }

    override fun exec(sql: String, vararg args: Any?) {
        db.execSQL(sql, args)
    }

    override fun insert(sql: String, vararg args: Any?): Long {
        db.execSQL(sql, args)
        return db.query("SELECT last_insert_rowid()").use { c -> c.moveToFirst(); c.getLong(0) }
    }
}
