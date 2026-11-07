package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.BackupManifest
import jp.kjtr.drivelogger.ranking.PutBody
import jp.kjtr.drivelogger.ranking.RankBoard
import jp.kjtr.drivelogger.ranking.RankValues
import jp.kjtr.drivelogger.ranking.RankingApi
import jp.kjtr.drivelogger.ranking.RankingBackup
import jp.kjtr.drivelogger.ranking.RankingCellCache
import jp.kjtr.drivelogger.ranking.RankingRules
import jp.kjtr.drivelogger.ranking.RankingSpec
import jp.kjtr.drivelogger.stats.CellShapes
import jp.kjtr.drivelogger.stats.GridCells
import jp.kjtr.drivelogger.stats.TripFacts
import jp.kjtr.drivelogger.stats.DriveSummaries
import jp.kjtr.drivelogger.stats.SummaryPeriod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.util.TimeZone

/** オンラインランキング（0.9.0）。共通の決まりは基準点めぐり・神社めぐりの RankingTest と同じ。 */
class RankingTest {
    private fun jst(s: String): Long = ZonedDateTime.parse("$s+09:00[Asia/Tokyo]").toInstant().toEpochMilli()

    // ---------------- 共通の決まり ----------------

    @Test
    fun monthIsJapanTime() {
        // 2026-10-31 15:00 UTC = 2026-11-01 00:00 JST
        assertEquals("2026-11", RankingRules.monthOf(ZonedDateTime.parse("2026-10-31T15:00:00Z").toInstant().toEpochMilli()))
        assertEquals("2026-10", RankingRules.monthOf(ZonedDateTime.parse("2026-10-31T14:59:59Z").toInstant().toEpochMilli()))
        assertEquals("2027-01", RankingRules.monthOf(jst("2027-01-01T00:00:00")))
        assertEquals("2026年11月", RankingRules.monthLabel("2026-11"))
        val r = RankingSpec.monthRange(jst("2026-11-07T12:00:00"))
        assertEquals(jst("2026-11-01T00:00:00"), r.fromMs)
        assertEquals(jst("2026-12-01T00:00:00"), r.toMs)
    }

    @Test
    fun nameRulesMatchServer() {
        assertEquals("たびびと", RankingRules.cleanName("  たびびと　"))
        assertNull(RankingRules.cleanName(""))
        assertNull(RankingRules.cleanName("   "))
        assertEquals("123456789012", RankingRules.cleanName("123456789012"))
        assertNull(RankingRules.cleanName("1234567890123"))
        // 絵文字（サロゲートペア）は 1 文字
        assertEquals(12, RankingRules.nameLength("😀".repeat(12)))
        assertTrue(RankingRules.cleanName("😀".repeat(12)) != null)
        assertNull(RankingRules.cleanName("😀".repeat(13)))
        // 制御文字・ゼロ幅・書式の文字
        assertNull(RankingRules.cleanName("a\u0007b"))
        assertNull(RankingRules.cleanName("a​b"))
        assertNull(RankingRules.cleanName("a‮b"))
        // NFC（か + 濁点 → が）
        assertEquals("が", RankingRules.cleanName("が"))
        // 途中の空白はそのまま
        assertEquals("a b", RankingRules.cleanName("a b"))
    }

    @Test
    fun sendOnOpenAndRetry() {
        val now = jst("2026-11-07T12:00:00")
        assertTrue(RankingRules.shouldSendOnOpen(pending = true, sameAsLast = true, lastSentAt = now - 1000, now = now))
        assertTrue(RankingRules.shouldSendOnOpen(pending = false, sameAsLast = false, lastSentAt = now - 1000, now = now))
        assertFalse(RankingRules.shouldSendOnOpen(pending = false, sameAsLast = true, lastSentAt = now - 59 * 60_000L, now = now))
        assertTrue(RankingRules.shouldSendOnOpen(pending = false, sameAsLast = true, lastSentAt = now - 60 * 60_000L, now = now))
        assertTrue(RankingRules.shouldSendOnOpen(pending = false, sameAsLast = true, lastSentAt = 0, now = now))
        assertEquals(60_000L, RankingRules.retryDelayMs(1))
        assertEquals(15 * 60_000L, RankingRules.retryDelayMs(3))
        assertNull(RankingRules.retryDelayMs(4))
        assertEquals(21_000L, RankingRules.retryAfterMs(20))
        assertEquals(21_000L, RankingRules.retryAfterMs(null))
        assertEquals("5 分前", RankingRules.agoText(now - 5 * 60_000L, now))
        assertEquals("2 時間前", RankingRules.agoText(now - 125 * 60_000L, now))
    }

    @Test
    fun signatureIgnoresMapOrder() {
        val a = RankValues("2026-11", linkedMapOf("distance" to 1L, "cells" to 2L), mapOf("distance" to 0L))
        val b = RankValues("2026-11", linkedMapOf("cells" to 2L, "distance" to 1L), mapOf("distance" to 0L))
        assertEquals(a.signature(), b.signature())
        assertNotEquals(a.signature(), a.copy(month = "2026-12").signature())
        assertNotEquals(a.signature(), a.copy(monthValues = mapOf("distance" to 1L)).signature())
    }

    @Test
    fun putBodyHasOnlyNumbersAndBoardReplyJson() {
        val body = RankingApi.JSON.encodeToString(
            PutBody.serializer(),
            PutBody("たびびと", "android", "0.9.0", "2026-11", mapOf("distance" to 123456L, "cells" to 50L), mapOf("distance" to 3000L, "cells" to 2L)),
        )
        // 送る本文のキーはこれだけ（位置・経路・マスの場所は入らない）
        assertEquals(setOf("name", "platform", "appVersion", "month", "values", "monthValues"), Json.parseToJsonElement(body).jsonObject.keys)
        assertTrue(body.contains("\"values\":{\"distance\":123456,\"cells\":50}"))
        assertTrue(body.contains("\"monthValues\":{\"distance\":3000,\"cells\":2}"))
        // API.md §3.3 の例（10 人未満）
        val locked = RankingApi.decodeBoard(
            """{ "board": "distance", "period": "all", "month": "2026-11", "players": 7, "ranked": 6, "minPlayers": 10, "unlocked": false,
                "me": { "rank": 3, "value": 12345, "name": "たびびと" }, "top": [] }""",
        )
        assertFalse(locked.unlocked)
        assertEquals(3, locked.me?.rank)
        assertEquals(7, locked.players)
        assertTrue(locked.top.isEmpty())
        // 10 人以上・自分の値なし（me が null）・知らないキーは無視
        val open = RankingApi.decodeBoard(
            """{ "board": "cells", "period": "month", "month": "2026-11", "players": 15, "ranked": 2, "minPlayers": 10, "unlocked": true,
                "me": null, "top": [ { "rank": 1, "name": "a", "value": 9, "me": false }, { "rank": 2, "name": "b", "value": 3, "me": false } ], "extra": 1 }""",
        )
        assertTrue(open.unlocked)
        assertNull(open.me)
        assertEquals(listOf("a", "b"), open.top.map { it.name })
        assertEquals(open, RankingApi.decodeBoard(RankingApi.encodeBoard(open)))
    }

    @Test
    fun boardFormatsKm() {
        val d = RankingSpec.BOARDS.first { it.id == "distance" }
        assertEquals("12.3 km", d.format(12_345))
        assertEquals("1,234.6 km", d.format(1_234_567))
        assertEquals("0.0 km", d.format(0))
        assertEquals("1,234 マス", RankingSpec.BOARDS.first { it.id == "cells" }.format(1234))
        assertEquals("5 点", RankBoard("x", "x", "点").format(5))
    }

    // ---------------- ドライブロガーの値 ----------------

    private fun cell(lat: Double, lon: Double) = GridCells.cellOf(lat, lon)

    private fun ride(id: Long, at: String, km: Double, vararg cells: Long) = TripFacts(
        id = id, name = "r$id", startedAt = jst(at), endedAt = jst(at) + 3_600_000L, distanceM = km * 1000.0,
        movingMs = 3_000_000L, maxSpeedMps = 20.0, cells = cells.toSet(),
    )

    private val a = cell(35.0, 139.0)
    private val b = cell(35.02, 139.0)
    private val c = cell(35.04, 139.0)
    private val d = cell(35.06, 139.0)

    private val rides = listOf(
        ride(1, "2026-10-20T10:00:00", 12.3456, a, b),
        ride(2, "2026-10-31T23:59:59", 5.0, b), // 10 月（日本時間）
        ride(3, "2026-11-01T00:00:00", 20.0, b, c), // b は 10 月に走ったので今月の「初めて」に入らない
        ride(4, "2026-11-30T23:00:00", 0.4004, c, d), // d だけが今月の初めて（c はドライブ 3 で今月）
    )

    @Test
    fun valuesFromRides() {
        val v = RankingSpec.compute(rides, jst("2026-11-07T12:00:00"))
        assertEquals("2026-11", v.month)
        assertEquals(mapOf("distance" to 37746L, "cells" to 4L), v.values)
        assertEquals(mapOf("distance" to 20400L, "cells" to 2L), v.monthValues)
        // 月が替わると今月の分は 0（0 = 記録なし）
        val dec = RankingSpec.compute(rides, jst("2026-12-01T00:00:00"))
        assertEquals("2026-12", dec.month)
        assertEquals(mapOf("distance" to 0L, "cells" to 0L), dec.monthValues)
        assertEquals(v.values, dec.values)
        // 記録なし
        assertEquals(mapOf("distance" to 0L, "cells" to 0L), RankingSpec.compute(emptyList(), jst("2026-11-07T12:00:00")).values)
        // 板の id はサーバ（src/apps.ts の drive）と同じ
        assertEquals(listOf("distance", "cells"), RankingSpec.BOARDS.map { it.id })
        assertEquals(RankingSpec.BOARDS.map { it.id }.toSet(), v.values.keys)
        assertEquals(v.values.keys, v.monthValues.keys)
    }

    @Test
    fun valuesMatchSummaryScreen() {
        // まとめの画面と同じ計算（全期間の距離・マス、今月の距離・今月初めてのマス）。端末が日本時間のとき「今月」も一致する
        val zone = TimeZone.getTimeZone("Asia/Tokyo")
        val now = jst("2026-11-07T12:00:00")
        val v = RankingSpec.compute(rides, now)
        val all = DriveSummaries.summarize(rides, zone)
        assertEquals(Math.round(all.distanceM), v.values["distance"])
        assertEquals(all.cells.toLong(), v.values["cells"])
        val range = DriveSummaries.range(SummaryPeriod.THIS_MONTH, now, zone)
        val month = rides.filter { it.startedAt in range } // まとめの画面と同じ選び方
        assertEquals(Math.round(DriveSummaries.summarize(month, zone).distanceM), v.monthValues["distance"])
        val fresh = CellShapes.newIn(month.flatMapTo(HashSet()) { it.cells }, CellShapes.firstSeen(rides), range)
        assertEquals(fresh.size.toLong(), v.monthValues["cells"])
    }

    @Test
    fun valuesCappedAtServerLimits() {
        val huge = listOf(ride(1, "2026-11-02T10:00:00", 120_000.0, a)) // 12 万 km（今月の上限 10 万 km）
        val v = RankingSpec.compute(huge, jst("2026-11-07T12:00:00"))
        assertEquals(RankingSpec.MAX_DISTANCE_MONTH, v.monthValues["distance"])
        assertEquals(120_000_000L, v.values["distance"])
    }

    // ---------------- マスの覚え ----------------

    @Test
    fun cellCacheRoundTrip() {
        val m = mapOf(1L to ("k1" to longArrayOf(a, b)), 7L to ("1|2|3.0|true" to longArrayOf()))
        val out = ByteArrayOutputStream()
        RankingCellCache.write(out, m)
        val back = RankingCellCache.read(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(m.keys, back.keys)
        assertEquals("k1", back[1L]!!.first)
        assertArrayEquals(longArrayOf(a, b), back[1L]!!.second)
        assertEquals(0, back[7L]!!.second.size)
        // 壊れたファイル・途中で切れたファイルは読まない（作り直す）
        assertNull(RankingCellCache.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(RankingCellCache.read(ByteArrayInputStream(out.toByteArray().copyOf(out.size() - 4))))
    }

    @Test
    fun cellCacheRecomputesOnlyChanged() = kotlinx.coroutines.runBlocking {
        val f = java.io.File.createTempFile("rkc", ".bin").also { it.delete() }
        try {
            val cache = RankingCellCache(f)
            val computed = ArrayList<Long>()
            val r1 = cache.cells(listOf(1L to "k", 2L to "k")) { id -> computed.add(id); setOf(id * 10) }
            assertEquals(mapOf(1L to setOf(10L), 2L to setOf(20L)), r1)
            // 鍵が同じなら数え直さない。鍵が変わった記録だけ。ファイルから読み直しても同じ
            val again = RankingCellCache(f)
            val r2 = again.cells(listOf(1L to "k", 2L to "k2")) { id -> computed.add(id); setOf(id * 100) }
            assertEquals(listOf(1L, 2L, 2L), computed)
            assertEquals(mapOf(1L to setOf(10L), 2L to setOf(200L)), r2)
            // 消した記録は捨てる
            val r3 = again.cells(listOf(2L to "k2")) { error("数え直さない") }
            assertEquals(setOf(2L), r3.keys)
        } finally {
            f.delete()
        }
    }

    // ---------------- バックアップ ----------------

    @Test
    fun backupCarriesRankingIdentity() {
        val json = Json { ignoreUnknownKeys = true }
        val m = BackupManifest(exportedAt = 1, trips = emptyList(), ranking = RankingBackup("2b0c3a7e-1f6a-4c1e-9a55-3f1f5f0e8a10", "c2VjcmV0", "たびびと"))
        val back = json.decodeFromString(BackupManifest.serializer(), json.encodeToString(BackupManifest.serializer(), m))
        assertEquals(m.ranking, back.ranking)
        // 0.8.0 までのバックアップ（ranking なし）も読める
        val old = json.decodeFromString(BackupManifest.serializer(), """{ "format": 1, "exportedAt": 5, "trips": [] }""")
        assertNull(old.ranking)
    }
}
