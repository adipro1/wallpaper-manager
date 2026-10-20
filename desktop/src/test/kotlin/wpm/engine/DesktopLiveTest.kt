package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.Samples
import wpm.mpv.Mpv
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plays a test video behind the real desktop icons for a few seconds.
 * Opt-in (it changes what the desktop shows briefly): set WPM_DESKTOP_TEST=1.
 * WPM_SCREENSHOT=<file.png> saves a screenshot while it plays.
 */
class DesktopLiveTest {
    @Test
    fun playsBehindDesktopIcons() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        assumeTrue(Mpv.available, "libmpv not installed")
        val dir = Files.createTempDirectory("wpm-desktop")
        val clip = Samples.video(dir.resolve("clip.mp4"), seconds = 5, width = 640, height = 360)
        val player = MpvLivePlayer()
        try {
            player.play(LiveSource(LiveKind.Video, clip.toString()))
            assertTrue(player.active)
            Thread.sleep(2_500)
            assertTrue(player.isHealthy())
            assertFalse(player.paused)
            System.getenv("WPM_SCREENSHOT")?.let { target ->
                val shot = Robot().createScreenCapture(Rectangle(Toolkit.getDefaultToolkit().screenSize))
                ImageIO.write(shot, "png", java.io.File(target))
            }
            player.pause()
            Thread.sleep(300)
            assertTrue(player.paused)
            player.resume()
            player.fitToScreen()
            println("competing apps: ${player.competingApps()}")
        } finally {
            player.stop()
            dir.toFile().deleteRecursively()
        }
        assertFalse(player.active)
    }
}
