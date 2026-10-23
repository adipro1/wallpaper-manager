package wpm.services

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.engine.LivePlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeLivePlayer : LivePlayer {
    var healthy = true
    var fillOn = true
    var mutedOn = true
    override var source: String? = null
    override var lastError: String? = null
    override var active = false
    override var paused = false

    var kind: wpm.engine.LiveKind? = null
    var interactiveOn = false
    var loadScript: String? = null

    override fun play(source: wpm.engine.LiveSource) {
        this.source = source.target
        loadScript = source.onLoadScript
        fillOn = source.fill
        mutedOn = source.muted
        kind = source.kind
        interactiveOn = source.interactive
        active = true
        paused = false
        healthy = true
    }
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun setMuted(muted: Boolean) { mutedOn = muted }
    override fun setFill(fill: Boolean) { fillOn = fill }
    override fun isHealthy() = healthy
    override fun competingApps() = emptyList<String>()
    override fun fitToScreen() {}
    override fun stop() {
        active = false
        paused = false
        source = null
    }
}

class FakeProbe(
    var locked: Boolean = false,
    var screenOff: Boolean = false,
    var fullscreen: Boolean = false,
    var covered: Boolean = false,
    var battery: Boolean = false,
) : SystemProbe {
    /** Extra displays next to the main one (the main one is 1920x1080 at 0,0). */
    var extraDisplays: List<wpm.win32.Monitor> = emptyList()
    /** Displays (by id) with a fullscreen app / maximized windows, in addition to the flags above. */
    var fullscreenOn: String? = null
    var coveredOn: Set<String> = emptySet()

    override fun sessionLocked() = locked
    override fun screenOff() = screenOff
    override fun fullscreenDisplay() = when {
        fullscreen -> MAIN.bounds
        fullscreenOn != null -> displays().first { it.id == fullscreenOn }.bounds
        else -> null
    }
    override fun coveredDisplays() = (if (covered) setOf(MAIN.bounds) else emptySet()) +
        displays().filter { it.id in coveredOn }.map { it.bounds }
    override fun onBattery() = battery
    override fun displays() = listOf(MAIN) + extraDisplays
    var foreground: String? = null
    var running: Set<String> = emptySet()
    override fun foregroundApp() = foreground
    override fun runningApps() = running

    companion object {
        val MAIN = wpm.win32.Monitor("""\\.\DISPLAY1""", wpm.win32.ScreenRect(0, 0, 1920, 1080), true)
    }
}

class LiveWallpaperTest {
    private val dir: Path = Files.createTempDirectory("wpm-live")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val fake = FakeDesktop(Samples.image(dir.resolve("original.jpg")).toString())
    private val live = FakeLivePlayer()
    private val service = WallpaperService(db, paths, fake, live)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun video(name: String = "clip.mp4"): MediaItem {
        val file = dir.resolve(name).also { it.writeText("video") } // the service only checks that it exists
        val id = db.addMedia(file.toString(), "video", 1920, 1080, 10.0, 5, null)!!
        return db.getMedia(id)!!
    }

    private fun image(name: String = "a.png"): MediaItem {
        val file = Samples.image(dir.resolve(name))
        val id = db.addMedia(file.toString(), "image", 64, 36, null, 5, null)!!
        return db.getMedia(id)!!
    }

    @Test
    fun videoPlaysMutedAndSavesState() {
        val clip = video()
        service.setVideo(clip)
        assertEquals(clip.path, live.source)
        assertTrue(live.mutedOn && live.fillOn)
        assertEquals(DesktopState("video", clip.id, clip.path, "fill"), service.state)
        assertTrue(service.hasOriginal()) // backed up before the first change
    }

    @Test
    fun imageAfterVideoStopsTheVideo() {
        service.setVideo(video())
        service.setImage(image())
        assertFalse(live.active)
        assertEquals("image", service.state.kind)
    }

    @Test
    fun stopVideoReturnsToPreviousImage() {
        val picture = image()
        service.setImage(picture, "center")
        service.setVideo(video())
        service.stopVideo()
        assertFalse(live.active)
        assertEquals(picture.path to false, fake.applied.last()) // repainted, not saved again
        assertEquals(DesktopState("image", picture.id, picture.path, "center"), service.state)
    }

    @Test
    fun pauseResumeSoundAndFit() {
        service.setVideo(video())
        service.pauseVideo()
        assertTrue(service.videoPaused)
        service.resumeVideo()
        assertFalse(service.videoPaused)
        service.setMuted(false)
        service.setVideoStyle("fit")
        assertFalse(live.mutedOn)
        assertFalse(live.fillOn)
        assertEquals("fit", service.state.style)
    }

    @Test
    fun shutdownKeepsChoiceAndStartupRestoresIt() {
        val clip = video()
        service.setVideo(clip, "fit")
        service.shutdown()
        assertFalse(live.active)
        assertEquals("video", service.state.kind)

        val restartedLive = FakeLivePlayer()
        assertTrue(WallpaperService(db, paths, fake, restartedLive).restoreOnStartup())
        assertEquals(clip.path, restartedLive.source)
        assertFalse(restartedLive.fillOn)

        Settings(db).set("resume_live_wallpaper", false)
        assertFalse(WallpaperService(db, paths, fake, FakeLivePlayer()).restoreOnStartup())
    }

    @Test
    fun checkLiveRecreatesLostWindow() {
        val clip = video()
        service.setVideo(clip)
        live.healthy = false // e.g. Explorer was restarted
        service.checkLive()
        assertEquals(clip.path, live.source)
        assertTrue(live.healthy)
    }

    @Test
    fun autoPauseAndResume() {
        service.setVideo(video())
        assertTrue(service.updateAutoPause(PauseReason.Fullscreen))
        assertTrue(service.videoPaused)
        assertEquals(PauseReason.Fullscreen, service.autoPauseReason)
        assertFalse(service.updateAutoPause(PauseReason.Fullscreen)) // still fullscreen: stays paused
        assertTrue(service.videoPaused)
        assertTrue(service.updateAutoPause(null)) // game closed
        assertFalse(service.videoPaused)
        assertNull(service.autoPauseReason)
    }

    @Test
    fun autoPauseNeverOverridesTheUser() {
        service.setVideo(video())
        // User paused by hand: leaving fullscreen must not resume it.
        service.pauseVideo()
        service.updateAutoPause(PauseReason.Fullscreen)
        service.updateAutoPause(null)
        assertTrue(service.videoPaused)

        // User resumes while on battery: don't pause again for the same reason...
        service.resumeVideo()
        service.updateAutoPause(PauseReason.Battery)
        service.resumeVideo()
        service.updateAutoPause(PauseReason.Battery)
        assertFalse(service.videoPaused)
        // ...but the same reason later pauses again.
        service.updateAutoPause(null)
        service.updateAutoPause(PauseReason.Battery)
        assertTrue(service.videoPaused)
    }

    @Test
    fun pauseReasonPriorityAndSettings() {
        val settings = Settings(db)
        val probe = FakeProbe(locked = true, screenOff = true, fullscreen = true, covered = true, battery = true)
        assertEquals(PauseReason.Locked, pauseReason(settings, probe))
        probe.locked = false
        assertEquals(PauseReason.ScreenOff, pauseReason(settings, probe))
        settings.set("pause_when_hidden", false)
        assertEquals(PauseReason.Fullscreen, pauseReason(settings, probe))
        settings.set("pause_on_fullscreen", false)
        assertEquals(PauseReason.Covered, pauseReason(settings, probe))
        settings.set("pause_when_covered", false)
        assertNull(pauseReason(settings, probe)) // battery is off by default
        settings.set("pause_on_battery", true)
        assertEquals(PauseReason.Battery, pauseReason(settings, probe))
    }

    @Test
    fun pausesForApps() {
        val settings = Settings(db)
        val probe = FakeProbe()
        probe.foreground = "chrome.exe"
        probe.running = setOf("chrome.exe", "obs64.exe", "explorer.exe")
        assertNull(pauseReason(settings, probe)) // nothing chosen yet
        settings.set("pause_apps", appRulesSerializer, listOf(AppPauseRule("game.exe"), AppPauseRule("obs64.exe", whileRunning = true)))
        assertEquals(PauseReason.AppRunning, pauseReason(settings, probe))
        probe.running = setOf("chrome.exe")
        assertNull(pauseReason(settings, probe))
        probe.foreground = "game.exe"
        assertEquals(PauseReason.AppInFront, pauseReason(settings, probe))
        probe.foreground = null // the desktop
        settings.set("pause_when_app_focused", true)
        assertNull(pauseReason(settings, probe))
        probe.foreground = "notepad.exe"
        assertEquals(PauseReason.AppInFront, pauseReason(settings, probe))
        // Locking still comes first.
        probe.locked = true
        assertEquals(PauseReason.Locked, pauseReason(settings, probe))
        assertEquals("game.exe", normalizeExe("C:\\Games\\Game.EXE"))
        assertEquals("game.exe", normalizeExe(" game "))
    }

    @Test
    fun nextSkipsVideosWithoutVideoEngine() {
        image("a.png")
        video()
        service.videosAvailable = false
        repeat(3) { assertNotNull(service.nextFromLibrary()).also { assertFalse(it.isVideo) } }
    }
}
