package wpm.services

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.AppState
import wpm.Samples
import wpm.data.Database
import wpm.data.Settings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeScreensaverApi(var values: ScreensaverValues = ScreensaverValues()) : ScreensaverApi {
    override fun read() = values
    override fun write(values: ScreensaverValues) { this.values = values }
}

class ScreensaverTest {
    private val dir: Path = Files.createTempDirectory("wpm-saver")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun switchingOnSavesTheUsersScreensaverAndPutsItBack() {
        val exe = dir.resolve("app").also { Files.createDirectories(it) }.resolve("WallpaperManager.exe").also { it.writeBytes(ByteArray(100) { 1 }) }
        val users = ScreensaverValues("C:\\Windows\\system32\\Bubbles.scr", "1", "300")
        val api = FakeScreensaverApi(users)
        val setup = ScreensaverSetup(Settings(db), api) { exe }
        setup.setMinutes(5)
        setup.setEnabled(true)
        val scr = exe.resolveSibling("WallpaperManager.scr")
        assertTrue(Files.size(scr) == 100L, "a copy of the launcher")
        assertEquals(ScreensaverValues(ScreensaverSetup.shortPath(scr), "1", "300"), api.values)
        setup.setEnabled(true) // again: the saved original stays the user's
        setup.setMinutes(15)
        assertEquals("900", api.values.timeout)
        setup.setEnabled(false)
        assertEquals(users, api.values)
        assertTrue(!setup.enabled)

        // Without an installed launcher (a development build) it says so.
        val dev = ScreensaverSetup(Settings(db), FakeScreensaverApi()) { null }
        assertFailsWith<IllegalStateException> { dev.setEnabled(true) }
        assertTrue(!dev.enabled)
    }

    /** A real screensaver for a moment: a slideshow, then the live wallpaper's window lent and given back. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun screensaverOnTheRealScreen() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val live = FakeLivePlayer()
        val app = AppState(paths, db, startMinimized = true, desktopApi = FakeDesktop(), lockScreenApi = FakeLockScreen(Samples.image(dir.resolve("lock.png"))),
            live = live, videoReader = null, systemProbe = FakeProbe(), widgetOverlay = { wpm.widgets.FakeOverlay() })
        try {
            val picture = db.getMedia(app.library.importFiles(listOf(Samples.image(dir.resolve("Dawn.png"), 800, 450))).added.single())!!
            val playlist = db.createPlaylist("Saver", "sequential", 60)
            db.addToPlaylist(playlist, listOf(picture.id))
            app.screensaverSetup.setSource("playlist:$playlist")
            app.startScreensaver()
            assertNotNull(app.screensaver).let { assertEquals(listOf(picture.localPath), it.pictures) }
            Thread.sleep(1_500)
            app.stopScreensaver()
            assertNull(app.screensaver)

            // What the slideshow looks like.
            val shot = wpm.ui.ScreensaverHost(wpm.ui.ScreensaverShow(listOf(picture.localPath!!), 0xFF6B52))
            Thread.sleep(2_500)
            val image = assertNotNull(wpm.win32.WindowCapture.capture(shot.primary))
            shot.close()
            javax.imageio.ImageIO.write(image, "png", Path.of("build/screensaver.png").toAbsolutePath().toFile())

            // The live wallpaper's own window moves into the screensaver and back.
            val host = wpm.win32.DesktopHost(wpm.win32.Monitors.primary().bounds)
            host.start()
            try {
                val window = host.hwnd!!
                val parent = com.sun.jna.platform.win32.User32.INSTANCE.GetParent(window)
                val saver = wpm.ui.ScreensaverHost(wpm.ui.ScreensaverShow(emptyList(), 0))
                val lent = assertNotNull(wpm.win32.lendWindow(window, saver.primary))
                assertEquals(saver.primary, com.sun.jna.platform.win32.User32.INSTANCE.GetParent(window))
                Thread.sleep(500)
                lent.giveBack()
                saver.close()
                assertEquals(parent, com.sun.jna.platform.win32.User32.INSTANCE.GetParent(window))
                assertTrue(host.isAlive())
            } finally {
                host.close()
            }
        } finally {
            app.close()
        }
    }
}
