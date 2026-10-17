package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.data.TripEntity
import jp.kjtr.drivelogger.data.TripEventEntity
import jp.kjtr.drivelogger.data.TripPointEntity
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.gpx.GpxParser
import jp.kjtr.drivelogger.gpx.GpxWriter
import jp.kjtr.drivelogger.update.Releases
import jp.kjtr.drivelogger.update.UpdateLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class UpdateCheckTest {
    @Test
    fun readsOnlyThisAppsNewestEntry() {
        val json = """
            {"releases":[
              {"folder":"cycle_route_logger","tag":"cycleroutelogger-v9.9.9"},
              {"folder":"drive_logger","tag":"drivelogger-v0.1.0","notes":"- 最初の版"},
              {"folder":"drive_logger","tag":"drivelogger-v0.2.0","notes":"- 録画の改善\n- **保護**の修正"}
            ]}
        """.trimIndent()
        val r = Releases.parse(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals(listOf("録画の改善", "保護の修正"), Releases.summary(r.notes))
        assertEquals("https://github.com/kijitora-no-hito/android-apps/releases/download/drivelogger-v0.2.0/drivelogger-0.2.0-release.apk", UpdateLinks.apkUrl(r))
        assertNull(Releases.parse("""{"releases":[{"folder":"drive_logger","tag":"cycleroutelogger-v1.0"}]}"""))
        assertNull(Releases.parse("not json"))
    }
}

class TripGpxTest {
    @Test
    fun gpxRoundTripWithEventWaypoints() {
        val t = TripEntity(id = 1, name = "テスト & ドライブ", startedAt = 1_000_000, status = TripEntity.STATUS_FINISHED, autoPause = false)
        val pts = listOf(
            TripPointEntity(1, 1, 0, 1_000_000, 35.0, 139.0, 10.0, 5f, null, null, null),
            TripPointEntity(2, 1, 0, 1_001_000, 35.0001, 139.0, null, 5f, null, null, null),
            TripPointEntity(3, 1, 1, 1_010_000, 35.001, 139.001, null, 5f, null, null, null),
        )
        val events = listOf(
            TripEventEntity(1, 1, TripEventEntity.TYPE_IMPACT, 1_001_500, 1_001_500, 35.0001, 139.0, 2.345),
            TripEventEntity(2, 1, TripEventEntity.TYPE_VIDEO, 1_000_000, 1_010_000, null, null, null),
        )
        val sb = StringBuilder()
        GpxWriter.write(sb, TripRepository.tripToGpx(t, pts, events), t.startedAt)
        val back = GpxParser().parse(KXmlParser(), sb.toString().byteInputStream())
        assertEquals("テスト & ドライブ", back.name)
        assertEquals(2, back.tracks.single().segments.size)
        assertEquals(1_001_000L, back.tracks.single().segments[0][1].timeMs)
        assertEquals("impact 2.35G", back.waypoints.single().name)
    }
}
