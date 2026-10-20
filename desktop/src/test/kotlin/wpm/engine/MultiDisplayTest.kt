package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.data.Settings
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.FakeProbe
import wpm.services.PauseReason
import wpm.services.WallpaperService
import wpm.services.pauseDecision
import wpm.win32.DesktopWallpaperCom
import wpm.win32.Monitor
import wpm.win32.ScreenRect
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiDisplayTest {
    private val main = FakeProbe.MAIN
    private val side = Monitor("""\\.\DISPLAY2""", ScreenRect(1920, 0, 1280, 1024), false)
    private var connected = listOf(main, side)
    private val players = mutableMapOf<String, FakeLivePlayer>()
    private val multi = MultiLive({ connected }) { display -> FakeLivePlayer().also { players[display.id] = it } }

    private val dir = Files.createTempDirectory("wpm-multi")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun video(name: String) = LiveSource(LiveKind.Video, name)

    @Test
    fun sharedWallpaperPlaysOnEveryDisplay() {
        multi.play(video("a.mp4"))
        assertEquals("a.mp4", players.getValue(main.id).source)
        assertEquals("a.mp4", players.getValue(side.id).source)

        multi.allDisplays = false
        multi.play(video("b.mp4"))
        assertEquals("b.mp4", players.getValue(main.id).source)
        assertFalse(side.id in multi.players()) // only the main display now
    }

    @Test
    fun displaysCanHaveTheirOwnWallpaper() {
        multi.play(video("shared.mp4"))
        multi.playOn(side.id, video("own.mp4"))
        multi.play(video("next.mp4")) // e.g. a playlist moves on
        assertEquals("next.mp4", players.getValue(main.id).source)
        assertEquals("own.mp4", players.getValue(side.id).source) // kept its own

        multi.stopOn(side.id)
        assertFalse(side.id in multi.players())
    }

    @Test
    fun followsDisplaysComingAndGoing() {
        connected = listOf(main)
        multi.play(video("a.mp4"))
        connected = listOf(main, side) // plugged in
        multi.fitToScreen()
        assertEquals("a.mp4", players.getValue(side.id).source)
        connected = listOf(main) // unplugged
        multi.fitToScreen()
        assertFalse(side.id in multi.players())
    }

    @Test
    fun hiddenDisplaysPauseOnTheirOwn() {
        multi.play(video("a.mp4"))
        multi.hideDisplays(setOf(side.id))
        assertTrue(players.getValue(side.id).paused)
        assertFalse(players.getValue(main.id).paused)
        assertFalse(multi.paused) // not everything is paused

        multi.pause() // the user, or the PC is locked
        assertTrue(multi.paused)
        multi.resume()
        assertTrue(players.getValue(side.id).paused) // still hidden
        multi.hideDisplays(emptySet())
        assertFalse(players.getValue(side.id).paused)
    }

    @Test
    fun pauseEverythingOnlyWhenEveryLiveDisplayIsHidden() {
        val settings = Settings(db)
        val probe = FakeProbe().apply { extraDisplays = listOf(side) }
        val both = setOf(main.id, side.id)

        probe.fullscreenOn = side.id
        val partly = pauseDecision(settings, probe, both)
        assertNull(partly.reason)
        assertEquals(setOf(side.id), partly.hiddenDisplays)

        probe.coveredOn = setOf(main.id)
        assertEquals(PauseReason.Fullscreen, pauseDecision(settings, probe, both).reason)

        probe.fullscreenOn = null
        probe.coveredOn = setOf(main.id)
        assertEquals(PauseReason.Covered, pauseDecision(settings, probe, setOf(main.id)).reason)

        probe.locked = true
        assertEquals(PauseReason.Locked, pauseDecision(settings, probe, both).reason)
    }

    @Test
    fun wallpaperServiceRemembersDisplayChoices() {
        val desktop = FakeDesktop(Samples.image(dir.resolve("original.jpg")).toString())
        val service = WallpaperService(db, paths, desktop, multi)
        val clip = dir.resolve("clip.mp4").also { it.writeText("video") }
        val video = db.getMedia(db.addMedia(clip.toString(), "video", 640, 360, 3.0, 5, null)!!)!!
        val picture = db.getMedia(db.addMedia(Samples.image(dir.resolve("p.png")).toString(), "image", 64, 36, null, 5, null)!!)!!

        service.setVideo(video) // all displays
        service.setOnDisplay(side, picture)
        assertEquals(picture.path, desktop.perDisplay[side.id])
        assertFalse(side.id in multi.players()) // the picture replaced the video there
        assertEquals("image", service.displayChoices().getValue(side.id).kind)

        service.setOnDisplay(main, video)
        service.shutdown()
        players.clear()
        val restarted = WallpaperService(db, paths, desktop, multi)
        assertTrue(restarted.restoreOnStartup())
        assertEquals(clip.toString(), players.getValue(main.id).source)

        service.clearDisplay(side)
        assertTrue(side.id !in service.displayChoices())
        service.setImage(picture) // all displays again: choices are forgotten
        assertTrue(service.displayChoices().isEmpty())
    }

    /** Reads Windows' display list through IDesktopWallpaper (read-only). Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun windowsListsDisplays() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val displays = DesktopWallpaperCom.displays()
        println("IDesktopWallpaper displays: $displays")
        assertTrue(displays.isNotEmpty())
        val ours = wpm.win32.Monitors.all().map { it.bounds }.toSet()
        assertTrue(displays.all { it.second in ours }, "rectangles match our display list")
    }
}
