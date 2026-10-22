package wpm.services

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistAndScheduleTest {
    private val dir: Path = Files.createTempDirectory("wpm-auto")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val desktop = FakeDesktop(Samples.image(dir.resolve("original.jpg")).toString())
    private val live = FakeLivePlayer()
    private val wallpapers = WallpaperService(db, paths, desktop, live)

    private var now = 1_000_000.0
    private val player = PlaylistPlayer(db, wallpapers) { now }

    private val zone = ZoneId.of("Europe/Berlin")
    private var wallClock: ZonedDateTime = at("2026-10-16T12:00")
    private val scheduler = Scheduler(db, wallpapers, player) { wallClock }

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone)

    // 3 images + 1 video
    private val ids: List<Long> = listOf("img0.png", "img1.png", "img2.png").map { name ->
        val f = Samples.image(dir.resolve(name))
        db.addMedia(f.toString(), "image", 64, 36, null, 5, null)!!
    } + dir.resolve("clip.mp4").also { it.writeText("video") }.let { db.addMedia(it.toString(), "video", 640, 360, 4.0, 5, null)!! }

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    // -- playlists ----------------------------------------------------------------------

    @Test
    fun sequentialPlaybackWithInterval() {
        val pid = db.createPlaylist("Seq", "sequential", 300)
        db.addToPlaylist(pid, ids)
        assertEquals(ids[0], player.start(pid).id)
        assertEquals(ids[0], wallpapers.state.mediaId)
        assertFalse(player.isDue())
        assertEquals(300.0, player.secondsUntilChange())

        val shown = (1..4).map {
            now += 300
            assertTrue(player.isDue())
            player.advance().id
        }
        assertEquals(listOf(ids[1], ids[2], ids[3], ids[0]), shown) // wraps around
        assertNull(live.source) // the video was replaced by the image again
    }

    @Test
    fun videoItemsBecomeLiveWallpapers() {
        val pid = db.createPlaylist("Vid")
        db.addToPlaylist(pid, listOf(ids[3]))
        player.start(pid)
        assertTrue(live.source!!.endsWith("clip.mp4"))
        assertEquals("video", wallpapers.state.kind)
    }

    @Test
    fun randomModeShowsEverythingBeforeRepeating() {
        val pid = db.createPlaylist("Rnd", "random", 60)
        db.addToPlaylist(pid, ids)
        repeat(20) {
            val seen = listOf(player.start(pid).id) + (1..3).map { player.advance().id }
            assertEquals(ids.sorted(), seen.sorted())
            assertNotEquals(seen.last(), player.advance().id) // no immediate repeat across rounds
            player.stop()
        }
    }

    @Test
    fun stateSurvivesRestart() {
        val pid = db.createPlaylist("Seq", "sequential", 300)
        db.addToPlaylist(pid, ids.take(3))
        player.start(pid)
        player.advance()
        val restarted = PlaylistPlayer(db, wallpapers) { now }
        assertEquals(pid, restarted.activePlaylist()?.id)
        now += 301
        assertTrue(restarted.isDue())
        assertEquals(ids[2], restarted.advance().id)
    }

    @Test
    fun missingFilesAreSkippedAndEmptyPlaylistRejected() {
        val empty = db.createPlaylist("Empty")
        assertFailsWith<IllegalArgumentException> { player.start(empty) }

        val pid = db.createPlaylist("Seq", "sequential", 300)
        db.addToPlaylist(pid, ids.take(3))
        Path.of(db.getMedia(ids[1])!!.path).deleteExisting()
        player.start(pid)
        now += 300
        assertEquals(ids[2], player.advance().id) // ids[1] skipped
    }

    @Test
    fun minimumIntervalAndStop() {
        val pid = db.createPlaylist("Fast", "sequential", 5)
        db.addToPlaylist(pid, ids.take(2))
        player.start(pid)
        assertEquals(MIN_INTERVAL_SECONDS.toDouble(), player.secondsUntilChange())
        player.stop()
        assertNull(player.state)
        assertFalse(player.isDue())
    }

    @Test
    fun deletingRunningPlaylistStopsIt() {
        val pid = db.createPlaylist("Gone")
        db.addToPlaylist(pid, ids.take(1))
        player.start(pid)
        db.deletePlaylist(pid)
        assertNull(player.activePlaylist())
        assertNull(player.state)
    }

    @Test
    fun readsVersion1State() {
        val pid = db.createPlaylist("Old", "sequential", 600)
        db.addToPlaylist(pid, ids.take(2))
        db.execute("INSERT INTO settings (key, value) VALUES ('active_playlist', ?)",
            """{"id": $pid, "index": 0, "next_change": 1000000.5, "bag": [], "media_id": ${ids[0]}}""")
        assertEquals(pid, player.activePlaylist()?.id)
        assertEquals(ids[0], player.currentMediaId())
        now = 1_000_001.0
        assertEquals(ids[1], player.advance().id)
    }

    // -- schedule ---------------------------------------------------------------------------

    @Test
    fun parsesTimes() {
        mapOf("7:05" to "07:05", "0705" to "07:05", " 23:59 " to "23:59", "18:00" to "18:00").forEach { (text, expected) ->
            assertEquals(expected, parseTime(text))
        }
        listOf("24:00", "12:60", "noon", "", "7", "25:00ge", "ab:cd", "-1:30").forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { parseTime(text) }
        }
    }

    @Test
    fun parsesSunTimes() {
        mapOf(
            "sunset" to "sunset", "Sunrise" to "sunrise", "sunset - 30" to "sunset-30", "sunrise+1:15" to "sunrise+75",
            "SUNSET +45 min" to "sunset+45", "sunrise - 0" to "sunrise",
        ).forEach { (text, expected) -> assertEquals(expected, parseTime(text), text) }
        listOf("sunset-7:00", "sundown", "sunset * 2", "sunrise+").forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { parseTime(text) }
        }
        assertEquals("30 min before sunset", describeTime("sunset-30"))
        assertEquals("1 h 15 min after sunrise", describeTime("sunrise+75"))
        assertEquals("Sunset", describeTime("sunset"))
        assertEquals("07:00", describeTime("07:00"))
    }

    @Test
    fun sunTimesMatchPublishedTables() {
        fun hm(t: ZonedDateTime?) = t!!.toLocalTime().let { it.hour * 60 + it.minute }
        val berlin = Place("Berlin", 52.52, 13.40)
        val june = java.time.LocalDate.of(2026, 6, 21)
        // Published: sunrise 04:43, sunset 21:33 (CEST).
        assertTrue(kotlin.math.abs(hm(Sun.sunrise(june, berlin, zone)) - (4 * 60 + 43)) <= 3)
        assertTrue(kotlin.math.abs(hm(Sun.sunset(june, berlin, zone)) - (21 * 60 + 33)) <= 3)
        val newYork = Place("New York", 40.71, -74.01)
        val december = java.time.LocalDate.of(2026, 12, 21)
        val eastern = ZoneId.of("America/New_York")
        // Published: sunrise 07:17, sunset 16:32 (EST).
        assertTrue(kotlin.math.abs(hm(Sun.sunrise(december, newYork, eastern)) - (7 * 60 + 17)) <= 3)
        assertTrue(kotlin.math.abs(hm(Sun.sunset(december, newYork, eastern)) - (16 * 60 + 32)) <= 3)
        // Tokyo: sunrise falls on the previous day in UTC, but on the right local day.
        val tokyo = Sun.sunrise(june, Place("Tokyo", 35.68, 139.69), ZoneId.of("Asia/Tokyo"))!!
        assertEquals(june, tokyo.toLocalDate())
        // Polar day: no sunset in Tromsø in June.
        assertNull(Sun.sunset(june, Place("Tromsø", 69.65, 18.96), zone))
        assertEquals("Berlin", guessCity(ZoneId.of("Europe/Berlin"))?.name)
        assertEquals("Kolkata", guessCity(ZoneId.of("Asia/Calcutta"))?.name)
        assertEquals("New York", findCities("new").first().name)
    }

    @Test
    fun sunRulesNeedALocationAndFollowTheSun() {
        val sunset = db.addRule("sunset-30", mediaId = ids[3])
        db.addRule("07:00", mediaId = ids[0])
        wallClock = at("2026-10-16T20:00")
        // Without a location only the clock rule counts.
        assertEquals("07:00", scheduler.active()?.rule?.time)
        scheduler.setPlace(Place("Berlin", 52.52, 13.40))
        // Berlin sunset on 16 Oct 2026 is about 18:11, so the rule ran at about 17:41.
        val active = scheduler.active()!!
        assertEquals(sunset, active.rule.id)
        assertTrue(active.time.toLocalTime().hour == 17 && active.time.toLocalTime().minute in 37..45, active.time.toString())
        assertFalse(scheduler.isDue(), "setting the location doesn't switch wallpapers by itself")
        wallClock = at("2026-10-17T07:01")
        assertTrue(scheduler.isDue())
        assertEquals("07:00", scheduler.applyDue()?.time)
        assertEquals(sunset, scheduler.upcoming()?.rule?.id)
        assertTrue(scheduler.todayText(db.listRules().first { it.id == sunset })!!.startsWith("17:"))
    }

    private fun rules() {
        db.addRule("07:00", mediaId = ids[0])
        db.addRule("18:00", mediaId = ids[3])
        db.addRule("22:00", mediaId = ids[2])
    }

    @Test
    fun activeRuleWrapsRoundMidnight() {
        rules()
        listOf("2026-10-16T12:00" to "img0.png", "2026-10-16T18:00" to "clip.mp4", "2026-10-16T23:30" to "img2.png",
            "2026-10-17T03:00" to "img2.png", "2026-10-17T07:00" to "img0.png").forEach { (time, expected) ->
            wallClock = at(time)
            assertEquals(expected, scheduler.active()?.rule?.targetName, time)
        }
        wallClock = at("2026-10-17T03:00")
        val next = scheduler.upcoming()!!
        assertEquals("img0.png", next.rule.targetName)
        assertEquals(at("2026-10-17T07:00"), next.time)
    }

    @Test
    fun rulesFireOnceAtTheirTime() {
        rules()
        scheduler.markCurrentApplied() // rules were just created: nothing changes yet
        assertFalse(scheduler.isDue())

        wallClock = at("2026-10-16T17:59")
        assertNull(scheduler.applyDue())
        wallClock = at("2026-10-16T18:00")
        assertEquals("clip.mp4", scheduler.applyDue()?.targetName)
        assertEquals("video", wallpapers.state.kind)
        assertNull(scheduler.applyDue()) // only once

        wallClock = at("2026-10-16T22:01")
        assertEquals("img2.png", scheduler.applyDue()?.targetName)
        assertEquals("image", wallpapers.state.kind)
        assertFalse(live.active)
    }

    @Test
    fun catchesUpAfterRestartAndKeepsVersion1Keys() {
        rules()
        scheduler.markCurrentApplied() // at 12:00 → img0
        assertEquals("\"${db.listRules().first().id}@2026-10-16T07:00\"", db.getSetting("schedule_last_applied").toString())
        wallClock = at("2026-10-16T19:30") // app was closed at 18:00
        val restarted = Scheduler(db, wallpapers, player) { wallClock }
        assertEquals("clip.mp4", restarted.applyDue()?.targetName)
    }

    @Test
    fun disabledScheduleAndDisabledRules() {
        rules()
        scheduler.markCurrentApplied()
        scheduler.setEnabled(false)
        wallClock = at("2026-10-16T18:30")
        assertFalse(scheduler.isDue())

        scheduler.setEnabled(true) // turning it on doesn't apply the 18:00 rule immediately
        assertFalse(scheduler.isDue())
        val city = db.listRules().first { it.time == "18:00" }
        db.setRuleEnabled(city.id, false)
        wallClock = at("2026-10-16T18:31")
        assertEquals("img0.png", scheduler.active()?.rule?.targetName)
    }

    @Test
    fun ruleCanStartAPlaylist() {
        val pid = db.createPlaylist("Night", "sequential", 600)
        db.addToPlaylist(pid, listOf(ids[2], ids[0]))
        db.addRule("21:00", playlistId = pid)
        scheduler.markCurrentApplied()
        wallClock = at("2026-10-16T21:00")
        assertEquals("Night", scheduler.applyDue()?.targetName)
        assertEquals(pid, player.activePlaylist()?.id)
        assertEquals(ids[2], wallpapers.state.mediaId)
    }

    @Test
    fun handlesDaylightSavingChanges() {
        // Berlin: clocks go forward 2027-03-28 02:00 -> 03:00 and back 2026-10-25 03:00 -> 02:00.
        db.addRule("02:30", mediaId = ids[0])
        db.addRule("12:00", mediaId = ids[1])
        wallClock = at("2027-03-28T03:15") // 02:30 never happened today: moved to 03:30, so not yet active
        assertEquals("img1.png", scheduler.active()?.rule?.targetName)
        wallClock = at("2027-03-28T03:31")
        assertEquals("img0.png", scheduler.active()?.rule?.targetName)
        scheduler.markCurrentApplied()
        assertFalse(scheduler.isDue())

        wallClock = at("2026-10-25T02:45") // 02:30 happens twice; it fires once (the first time)
        assertEquals("img0.png", scheduler.active()?.rule?.targetName)
        scheduler.applyDue()
        wallClock = wallClock.plusHours(1) // second 02:45, after the clocks went back
        assertFalse(scheduler.isDue())
    }
}
