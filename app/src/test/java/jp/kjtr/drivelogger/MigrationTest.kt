package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.AppDatabase
import jp.kjtr.drivelogger.data.LogMigration
import jp.kjtr.drivelogger.data.SqlRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room の Migration(1,2) の確認（スキーマの突き合わせ）: 書き出したスキーマ v1（0.1.0 / 0.2.0）の表を作って行を入れ、
 * Migration の SQL を流したあとの表の列（名前・型・NOT NULL・既定値）と索引が、スキーマ v2（0.3.0）と一致すること、行が残ることを確かめる。
 */
class MigrationTest {
    private val dir = File("schemas/jp.kjtr.drivelogger.data.AppDatabase")

    private fun schema(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun create(c: Connection, db: JsonObject) {
        c.createStatement().use { st ->
            for (e in db["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                st.execute(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { ix -> st.execute(ix.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
        }
    }

    private data class Col(val name: String, val type: String, val notNull: Boolean, val default: String?)

    private fun columns(c: Connection, table: String): Set<Col> {
        val out = HashSet<Col>()
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) out.add(Col(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getString("dflt_value")))
            }
        }
        return out
    }

    private fun indices(c: Connection, table: String): Set<String> {
        val out = HashSet<String>()
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs -> while (rs.next()) out.add(rs.getString("name")) }
        }
        return out.filter { !it.startsWith("sqlite_autoindex") }.toSet()
    }

    /** 今の表の列・索引が、スキーマ [db] のものと一致する（[db] の表がすべてある）。 */
    private fun assertMatches(c: Connection, db: JsonObject) {
        for (e in db["entities"]!!.jsonArray) {
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            val expected = o["fields"]!!.jsonArray.map { f ->
                val fo = f.jsonObject
                Col(fo["columnName"]!!.jsonPrimitive.content, fo["affinity"]!!.jsonPrimitive.content, fo["notNull"]!!.jsonPrimitive.boolean, fo["defaultValue"]?.jsonPrimitive?.contentOrNull)
            }.toSet()
            assertEquals("columns of $table", expected, columns(c, table))
            val expectedIx = o["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals("indices of $table", expectedIx, indices(c, table))
        }
    }

    /** 外部キー（表 → 参照先・ON DELETE）。 */
    private fun foreignKeys(c: Connection, table: String): Set<Triple<String, String, String>> {
        val out = HashSet<Triple<String, String, String>>()
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_list(`$table`)").use { rs ->
                while (rs.next()) out.add(Triple(rs.getString("from"), rs.getString("table"), rs.getString("on_delete")))
            }
        }
        return out
    }

    /**
     * Migration(2,3)（0.4.0: 写真 photo・駐車 park・徒歩の点 walk_point）: v2 の表に行を入れて SQL を流し、v3 のスキーマと突き合わせる。
     * 既存の行はそのまま、新しい表に行を入れられ、外部キー（ドライブを消すと写真・駐車・徒歩の点も消える）が効く。
     */
    @Test
    fun migration2to3MatchesSchema3AndKeepsRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(2))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 2, 0, 1, 1234.5, 60000, 1000, 20.0, 2)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, source) VALUES (1, 1, 0, 1000, 35.0, 139.0, 1)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, burnLayout) VALUES (1, 'DL_1.mp4', 1, 1000, 60000, 1000000, 0, 1280, 720, 30, 0, 1, 1, '{}')")
            }
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_2_3_SQL) st.execute(sql) }
            val v3 = schema(3)
            assertEquals(3, v3["version"]!!.jsonPrimitive.content.toInt())
            assertMatches(c, v3)
            assertEquals(setOf(Triple("tripId", "trip", "CASCADE")), foreignKeys(c, "photo"))
            assertEquals(setOf(Triple("tripId", "trip", "CASCADE")), foreignKeys(c, "park"))
            assertEquals(setOf(Triple("tripId", "trip", "CASCADE"), Triple("parkId", "park", "CASCADE")), foreignKeys(c, "walk_point"))
            c.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("INSERT INTO park (id, tripId, startedAt, endedAt, lat, lon, walkDistanceM) VALUES (1, 1, 2000, 3000, 35.0, 139.0, 120.5)")
                st.execute("INSERT INTO walk_point (tripId, parkId, time, lat, lon, accuracy) VALUES (1, 1, 2500, 35.001, 139.001, 8.0)")
                st.execute("INSERT INTO photo (tripId, uri, takenAt, lat, lon, locationSource, thumbPath, ownCapture, createdAt, captureMode, burnIn, parkId) VALUES (1, 'content://x/1', 2600, 35.001, 139.001, 'gps', NULL, 1, 2600, 'camera', 0, 1)")
                // 記録していないときの写真（tripId なし）
                st.execute("INSERT INTO photo (tripId, uri, takenAt, ownCapture, createdAt, burnIn) VALUES (NULL, 'content://x/2', 5000, 1, 5000, 0)")
                st.executeQuery("SELECT distanceM FROM trip WHERE id = 1").use { rs -> rs.next(); assertEquals(1234.5, rs.getDouble(1), 0.0) }
                st.executeQuery("SELECT source FROM trip_point WHERE id = 1").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT burnLayout FROM clip WHERE id = 1").use { rs -> rs.next(); assertEquals("{}", rs.getString(1)) }
                st.execute("DELETE FROM trip WHERE id = 1")
                st.executeQuery("SELECT COUNT(*) FROM park").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM walk_point").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
                // ドライブに紐づいた写真の行は消え、記録していないときの写真は残る（写真の本体はフォトライブラリなので消えない）
                st.executeQuery("SELECT COUNT(*) FROM photo").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            }
        }
    }

    /** 単体テスト用の [SqlRunner]（sqlite-jdbc）。 */
    private class JdbcRunner(private val c: Connection) : SqlRunner {
        private fun bind(ps: java.sql.PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }

        override fun rows(sql: String, vararg args: Any?): List<List<Any?>> = c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                val out = ArrayList<List<Any?>>()
                while (rs.next()) out.add((1..n).map { rs.getObject(it) })
                out
            }
        }

        override fun exec(sql: String, vararg args: Any?) {
            c.prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }
        }

        override fun insert(sql: String, vararg args: Any?): Long {
            exec(sql, *args)
            return c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        }
    }

    private fun migrate3to4(c: Connection) {
        c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_3_4_SQL) st.execute(sql) }
        LogMigration.run(JdbcRunner(c))
    }

    private fun q(c: Connection, sql: String): List<List<Any?>> = JdbcRunner(c).rows(sql)

    /**
     * Migration(3,4)（0.6.0: ドライブ＝GPS ログ・動画ログの入れ物）: v3 の表に 0.5.0 までのドライブを入れて SQL とデータの移行を流し、
     * v4 のスキーマと突き合わせる。既存のドライブは「閉じたドライブ＋駐車で分かれた GPS ログ＋録画した区間ごとの動画ログ」になり、
     * 点・動画はそのログに紐づく。ログの統計の合計はドライブの統計と同じ。記録中のまま落ちたドライブは開いたまま・最後のログは記録中のまま。
     */
    @Test
    fun migration3to4SplitsDrivesIntoLogs() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(3))
            c.createStatement().use { st ->
                // ドライブ 1（閉じた）: 駐車の前に 3 点・後に 3 点（東へ 1 秒 20 m）。録画した区間 1 つ・動画 2 本
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 'd1', 1000, 400000, 2, 0, 1, 0, 0, 0, 0, 6)")
                val lon0 = 139.0
                val dLon = 20.0 / (111_320.0 * Math.cos(Math.toRadians(35.0)))
                for (i in 0 until 3) st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, accuracy, source) VALUES (${i + 1}, 1, 0, ${10_000 + i * 1000}, 35.0, ${lon0 + i * dLon}, 5.0, 0)")
                st.execute("INSERT INTO park (id, tripId, startedAt, endedAt, lat, lon, walkDistanceM) VALUES (1, 1, 20000, 300000, 35.0, $lon0, 0)")
                for (i in 0 until 3) st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, accuracy, source) VALUES (${i + 4}, 1, 1, ${300_000 + i * 1000}, 35.0, ${lon0 + 0.01 + i * dLon}, 5.0, 0)")
                st.execute("INSERT INTO trip_event (id, tripId, type, startedAt, endedAt) VALUES (1, 1, 'video', 9000, 13000)")
                st.execute("INSERT INTO trip_event (id, tripId, type, startedAt, endedAt, value) VALUES (2, 1, 'impact', 11000, 11000, 2.5)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, laterOverlay) VALUES (1, 'DL_a.mp4', 1, 10500, 1000, 100, 0, 1280, 720, 30, 0, 0, 1, 1)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, laterOverlay) VALUES (2, 'DL_b.mp4', 1, 11600, 1000, 100, 1, 1280, 720, 30, 0, 0, 1, 1)")
                // ドライブに属さない動画
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, laterOverlay) VALUES (3, 'DL_c.mp4', NULL, 900000, 1000, 100, 0, 1280, 720, 30, 0, 0, 1, 0)")
                // ドライブ 2（記録中のまま落ちた）: 点 2 つ・録画中のまま
                st.execute("INSERT INTO trip (id, name, startedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (2, 'd2', 500000, 0, 0, 1, 0, 0, 0, 0, 2)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, source) VALUES (7, 2, 0, 500000, 35.0, 139.0, 0)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, source) VALUES (8, 2, 0, 501000, 35.0, ${139.0 + dLon}, 0)")
                st.execute("INSERT INTO trip_event (id, tripId, type, startedAt) VALUES (3, 2, 'video', 500500)")
                // ドライブ 3（駐車中に落ちた）: 駐車の前に点 1 つ
                st.execute("INSERT INTO trip (id, name, startedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (3, 'd3', 600000, 0, 0, 0, 0, 0, 0, 0, 1)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, source) VALUES (9, 3, 0, 600000, 35.0, 139.0, 0)")
                st.execute("INSERT INTO park (id, tripId, startedAt, lat, lon, walkDistanceM) VALUES (2, 3, 610000, 35.0, 139.0, 0)")
            }
            migrate3to4(c)
            val v4 = schema(4)
            assertEquals(4, v4["version"]!!.jsonPrimitive.content.toInt())
            assertMatches(c, v4)
            assertEquals(setOf(Triple("tripId", "trip", "CASCADE")), foreignKeys(c, "drive_log"))

            // ドライブ 1: GPS ログ 2 本（駐車の前後）・動画ログ 1 本
            val logs1 = q(c, "SELECT id, kind, startedAt, endedAt, distanceM, pointCount FROM drive_log WHERE tripId = 1 ORDER BY kind, startedAt")
            assertEquals(3, logs1.size)
            val (g1, g2, v1) = logs1
            assertEquals(listOf(0L, 10_000L, 12_000L), listOf(g1[1], g1[2], g1[3]).map { (it as Number).toLong() })
            assertEquals(listOf(0L, 300_000L, 302_000L), listOf(g2[1], g2[2], g2[3]).map { (it as Number).toLong() })
            assertEquals(3, (g1[5] as Number).toInt())
            assertEquals(40.0, (g1[4] as Number).toDouble(), 1.0)
            assertEquals(40.0, (g2[4] as Number).toDouble(), 1.0)
            assertEquals(listOf(1L, 9_000L, 13_000L), listOf(v1[1], v1[2], v1[3]).map { (it as Number).toLong() })
            // 点はログに、動画は動画ログに紐づく。ドライブに属さない動画はそのまま
            assertEquals(listOf(g1[0], g1[0], g1[0], g2[0], g2[0], g2[0]).map { (it as Number).toLong() }, q(c, "SELECT logId FROM trip_point WHERE tripId = 1 ORDER BY id").map { (it[0] as Number).toLong() })
            assertEquals(listOf((v1[0] as Number).toLong(), (v1[0] as Number).toLong(), null), q(c, "SELECT logId FROM clip ORDER BY id").map { (it[0] as Number?)?.toLong() })
            // 出来事 video は動画ログにしたので消え、ほかの出来事は残る
            assertEquals(listOf("impact"), q(c, "SELECT type FROM trip_event WHERE tripId = 1").map { it[0] })
            assertEquals(400_000L, (q(c, "SELECT updatedAt FROM trip WHERE id = 1")[0][0] as Number).toLong())

            // ドライブ 2: 開いたまま。GPS ログ・動画ログとも記録中（endedAt = null）→ 次に開いたとき続けるか聞く
            assertEquals(0, (q(c, "SELECT status FROM trip WHERE id = 2")[0][0] as Number).toInt())
            val logs2 = q(c, "SELECT kind, endedAt FROM drive_log WHERE tripId = 2 ORDER BY kind")
            assertEquals(listOf(listOf<Any?>(0L, null), listOf<Any?>(1L, null)), logs2.map { listOf((it[0] as Number).toLong(), it[1]) })
            // ドライブ 3: 駐車中に落ちた → GPS ログは駐車の前で閉じている
            val logs3 = q(c, "SELECT kind, endedAt FROM drive_log WHERE tripId = 3")
            assertEquals(1, logs3.size)
            assertEquals(600_000L, (logs3[0][1] as Number).toLong())
            // ドライブを消すとログも消える
            c.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("DELETE FROM trip WHERE id = 1")
            }
            assertEquals(0, (q(c, "SELECT COUNT(*) FROM drive_log WHERE tripId = 1")[0][0] as Number).toInt())
        }
    }

    /**
     * Migration(4,5)（0.7.0: 録画の方式 clip.recordMode・drive_log.recordMode）: v4 の表に 0.6.0 のドライブ・動画ログ・動画を入れて SQL を流し、
     * v5 のスキーマと突き合わせる。既存の動画・動画ログはループ（0）として残り、長時間録画（1）の行を足せる。
     */
    @Test
    fun migration4to5KeepsClipsAsLoop() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(4))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount, updatedAt) VALUES (1, 'd', 1000, 9000, 2, 0, 1, 0, 0, 0, 0, 0, 9000)")
                st.execute("INSERT INTO drive_log (id, tripId, kind, startedAt, endedAt, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 1, 1, 1000, 9000, 0, 0, 0, 0, 0)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, laterOverlay, logId) VALUES (1, 'DL_a.mp4', 1, 1000, 8000, 100, 1, 1280, 720, 30, 0, 0, 1, 1, 1)")
            }
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_4_5_SQL) st.execute(sql) }
            val v5 = schema(5)
            assertEquals(5, v5["version"]!!.jsonPrimitive.content.toInt())
            assertMatches(c, v5)
            assertEquals(listOf(listOf<Any?>(0L, 1L, 1L)), q(c, "SELECT recordMode, protected, logId FROM clip").map { r -> r.map { (it as Number).toLong() } })
            assertEquals(0L, (q(c, "SELECT recordMode FROM drive_log WHERE id = 1")[0][0] as Number).toLong())
            c.createStatement().use { st ->
                st.execute("INSERT INTO drive_log (tripId, kind, startedAt, endedAt, distanceM, movingMs, stillMs, maxSpeedMps, pointCount, recordMode) VALUES (1, 1, 10000, 20000, 0, 0, 0, 0, 0, 1)")
                st.execute("INSERT INTO clip (fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status, laterOverlay, logId, recordMode) VALUES ('DL_b.mp4', 1, 10000, 600000, 100, 0, 1920, 1080, 30, 0, 0, 1, 1, 2, 1)")
            }
            assertEquals(1, (q(c, "SELECT COUNT(*) FROM clip WHERE recordMode = 1")[0][0] as Number).toInt())
        }
    }

    /**
     * Migration(5,6)（0.8.0: 点の高度の精度 trip_point.altAccuracy・測位の出どころ trip_point.provider）: v5 の表に 0.7.0 の点を入れて SQL を流し、
     * v6 のスキーマと突き合わせる。既存の点は高度の精度なし・出どころ 0（不明）のまま残り、新しい列の入った点を足せる。
     */
    @Test
    fun migration5to6AddsPointColumns() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(5))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount, updatedAt) VALUES (1, 'd', 1000, 9000, 2, 0, 0, 10, 8000, 0, 2, 2, 9000)")
                st.execute("INSERT INTO drive_log (id, tripId, kind, startedAt, endedAt, distanceM, movingMs, stillMs, maxSpeedMps, pointCount, recordMode) VALUES (1, 1, 0, 1000, 9000, 10, 8000, 0, 2, 2, 0)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, ele, accuracy, rawLat, rawLon, rawAccuracy, source, logId) VALUES (1, 1, 0, 1000, 35.0, 139.0, 40.5, 3.0, 35.00001, 139.00001, 6.0, 1, 1)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, ele, accuracy, source, logId) VALUES (2, 1, 0, 2000, 35.0001, 139.0, 41.0, 5.0, 0, 1)")
            }
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_5_6_SQL) st.execute(sql) }
            val v6 = schema(6)
            assertEquals(6, v6["version"]!!.jsonPrimitive.content.toInt())
            assertMatches(c, v6)
            // 既存の点: 値はそのまま、高度の精度なし・出どころ不明
            assertEquals(
                listOf(listOf<Any?>(1L, null, 0L, 1L), listOf<Any?>(2L, null, 0L, 0L)),
                q(c, "SELECT id, altAccuracy, provider, source FROM trip_point ORDER BY id").map { r -> listOf((r[0] as Number).toLong(), r[1], (r[2] as Number).toLong(), (r[3] as Number).toLong()) },
            )
            assertEquals(40.5, (q(c, "SELECT ele FROM trip_point WHERE id = 1")[0][0] as Number).toDouble(), 0.0)
            // 既存のドライブの乗り物は車（0）
            assertEquals(0L, (q(c, "SELECT vehicle FROM trip WHERE id = 1")[0][0] as Number).toLong())
            assertEquals(6.0, (q(c, "SELECT rawAccuracy FROM trip_point WHERE id = 1")[0][0] as Number).toDouble(), 0.0)
            // 0.8.0 の点（Google の融合測位 = 3、高度の精度 4.5 m）
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip_point (tripId, segment, time, lat, lon, ele, accuracy, source, logId, altAccuracy, provider) VALUES (1, 1, 3000, 35.0002, 139.0, 42.0, 4.0, 0, 1, 4.5, 3)")
            }
            assertEquals(listOf(listOf<Any?>(4.5, 3L)), q(c, "SELECT altAccuracy, provider FROM trip_point WHERE time = 3000").map { r -> listOf((r[0] as Number).toDouble(), (r[1] as Number).toLong()) })
        }
    }

    /** 0.1.0 / 0.2.0（v1）から 0.8.0（v6）へ一度に上げる（1→2→3→4→5→6）。 */
    @Test
    fun migration1to6() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(1))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 3000, 2, 0, 0, 10.0, 1000, 0, 2.0, 1)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon, ele) VALUES (1, 1, 0, 1000, 35.0, 139.0, 12.0)")
                for (sql in AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL) st.execute(sql)
            }
            migrate3to4(c)
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_4_5_SQL + AppDatabase.MIGRATION_5_6_SQL) st.execute(sql) }
            assertMatches(c, schema(6))
            assertEquals(listOf(listOf<Any?>(12.0, 0L)), q(c, "SELECT ele, provider FROM trip_point").map { r -> listOf((r[0] as Number).toDouble(), (r[1] as Number).toLong()) })
        }
    }

    /** 0.1.0 / 0.2.0（v1）から 0.7.0（v5）へ一度に上げる（1→2→3→4→5）。 */
    @Test
    fun migration1to5() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(1))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 3000, 2, 0, 1, 10.0, 1000, 0, 2.0, 1)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon) VALUES (1, 1, 0, 1000, 35.0, 139.0)")
                st.execute("INSERT INTO trip_event (id, tripId, type, startedAt, endedAt) VALUES (1, 1, 'video', 1000, 3000)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status) VALUES (1, 'DL_1.mp4', 1, 1200, 1500, 1000, 0, 1280, 720, 30, 0, 1, 1)")
                for (sql in AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL) st.execute(sql)
            }
            migrate3to4(c)
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_4_5_SQL) st.execute(sql) }
            assertMatches(c, schema(5))
            assertEquals(listOf(0L), q(c, "SELECT recordMode FROM drive_log WHERE kind = 1").map { (it[0] as Number).toLong() })
            assertEquals(listOf(0L), q(c, "SELECT recordMode FROM clip").map { (it[0] as Number).toLong() })
        }
    }

    /** 0.1.0 / 0.2.0（v1）から 0.6.0（v4）へ一度に上げる（1→2→3→4）。 */
    @Test
    fun migration1to4() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(1))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, endedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 3000, 2, 0, 0, 10.0, 1000, 0, 2.0, 2)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon) VALUES (1, 1, 0, 1000, 35.0, 139.0)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon) VALUES (2, 1, 0, 3000, 35.0, 139.0001)")
                for (sql in AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL) st.execute(sql)
            }
            migrate3to4(c)
            assertMatches(c, schema(4))
            assertEquals(1, (q(c, "SELECT COUNT(*) FROM drive_log WHERE tripId = 1 AND kind = 0")[0][0] as Number).toInt())
            assertEquals(0, (q(c, "SELECT COUNT(*) FROM trip_point WHERE logId IS NULL")[0][0] as Number).toInt())
        }
    }

    /** 0.1.0 / 0.2.0（v1）から 0.4.0（v3）へ一度に上げる（1→2→3）。 */
    @Test
    fun migration1to3() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(1))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 2, 0, 0, 10.0, 1000, 0, 2.0, 1)")
                for (sql in AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL) st.execute(sql)
            }
            assertMatches(c, schema(3))
            c.createStatement().use { st -> st.executeQuery("SELECT name FROM trip WHERE id = 1").use { rs -> rs.next(); assertEquals("t", rs.getString(1)) } }
        }
    }

    @Test
    fun migration1to2MatchesSchema2AndKeepsRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(1))
            c.createStatement().use { st ->
                st.execute("INSERT INTO trip (id, name, startedAt, status, autoPause, recordedVideo, distanceM, movingMs, stillMs, maxSpeedMps, pointCount) VALUES (1, 't', 1000, 2, 0, 1, 1234.5, 60000, 1000, 20.0, 2)")
                st.execute("INSERT INTO trip_point (id, tripId, segment, time, lat, lon) VALUES (1, 1, 0, 1000, 35.0, 139.0)")
                st.execute("INSERT INTO clip (id, fileName, tripId, startedAt, durationMs, sizeBytes, protected, width, height, fps, hasAudio, burnIn, status) VALUES (1, 'DL_1.mp4', 1, 1000, 60000, 1000000, 1, 1280, 720, 30, 0, 1, 1)")
            }
            c.createStatement().use { st -> for (sql in AppDatabase.MIGRATION_1_2_SQL) st.execute(sql) }

            val v2 = schema(2)
            assertEquals(2, v2["version"]!!.jsonPrimitive.content.toInt())
            for (e in v2["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                val expected = o["fields"]!!.jsonArray.map { f ->
                    val fo = f.jsonObject
                    Col(fo["columnName"]!!.jsonPrimitive.content, fo["affinity"]!!.jsonPrimitive.content, fo["notNull"]!!.jsonPrimitive.boolean, fo["defaultValue"]?.jsonPrimitive?.contentOrNull)
                }.toSet()
                assertEquals("columns of $table", expected, columns(c, table))
                val expectedIx = o["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet() ?: emptySet()
                assertEquals("indices of $table", expectedIx, indices(c, table))
            }

            // 行はそのまま、新しい列は null / 既定値
            c.createStatement().use { st ->
                st.executeQuery("SELECT lat, rawLat, source FROM trip_point WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals(35.0, rs.getDouble("lat"), 0.0)
                    assertNull(rs.getObject("rawLat"))
                    assertEquals(0, rs.getInt("source"))
                }
                st.executeQuery("SELECT fileName, burnLayout, protected FROM clip WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals("DL_1.mp4", rs.getString("fileName"))
                    assertNull(rs.getString("burnLayout"))
                    assertEquals(1, rs.getInt("protected"))
                }
                st.executeQuery("SELECT distanceM FROM trip WHERE id = 1").use { rs -> rs.next(); assertEquals(1234.5, rs.getDouble(1), 0.0) }
            }
        }
    }
}
