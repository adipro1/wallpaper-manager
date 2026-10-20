package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shows a web page behind the real desktop icons. Opt-in: WPM_DESKTOP_TEST=1.
 * The first run downloads Chromium into build/chromium-test.
 */
class DesktopWebTest {
    @Test
    fun showsPageBehindDesktopIcons() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val dir = Files.createTempDirectory("wpm-web")
        val page = dir.resolve("index.html")
        page.writeText(
            """
            <!doctype html><html><body style="margin:0;overflow:hidden">
            <canvas id=c></canvas>
            <script>
              const c = document.getElementById('c'), g = c.getContext('2d');
              let clicks = 0;
              addEventListener('mousedown', () => { clicks++; document.title = 'clicks:' + clicks; });
              function frame(t) {
                c.width = innerWidth; c.height = innerHeight;
                g.fillStyle = 'hsl(' + (t / 20 % 360) + ',70%,40%)'; g.fillRect(0, 0, c.width, c.height);
                g.fillStyle = 'white'; g.font = '48px sans-serif'; g.fillText('Wallpaper Manager web test', 80, 120);
                requestAnimationFrame(frame);
              }
              requestAnimationFrame(frame);
            </script></body></html>
            """.trimIndent(),
        )
        val player = WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath(), onStatus = { println(it ?: "ready") })
        try {
            player.play(LiveSource(LiveKind.Web, page.toUri().toString(), interactive = true))
            Thread.sleep(4_000)
            assertTrue(player.active)
            assertTrue(player.isHealthy(), "window is attached to the wallpaper layer")
            assertNull(player.lastError)
            println("child windows: ${player.childWindowClasses()}")
            val own = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
            val mine = mutableListOf<String>()
            com.sun.jna.platform.win32.User32.INSTANCE.EnumWindows({ h, _ ->
                if (wpm.win32.windowPid(h) == own) {
                    val kids = mutableListOf<String>()
                    com.sun.jna.platform.win32.User32.INSTANCE.EnumChildWindows(h, { k, _ -> kids += wpm.win32.className(k); true }, null)
                    val r = com.sun.jna.platform.win32.WinDef.RECT().also { com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(h, it) }
                    mine += wpm.win32.className(h) + (if (com.sun.jna.platform.win32.User32.INSTANCE.IsWindowVisible(h)) "(v)" else "") + "${r.right - r.left}x${r.bottom - r.top}" + kids
                }
                true
            }, null)
            println("process windows: $mine")
            assertTrue(player.hasRenderWidget(), "Chromium's input window exists")
            player.postClick(300, 300)
            player.postClick(320, 320)
            Thread.sleep(1_000)
            println("title after clicks: ${player.pageTitle}")
            assertEquals("clicks:2", player.pageTitle, "posted mouse input reaches the page")
            val shot = player.snapshot()
            assertTrue(shot != null, "the page can be captured")
            System.getenv("WPM_SCREENSHOT")?.let { target -> ImageIO.write(shot, "png", java.io.File(target)) }
            player.pause()
            assertTrue(player.paused)
            player.resume()
            assertFalse(player.paused)
            println("competing apps: ${player.competingApps()}")
        } finally {
            player.stop()
            dir.toFile().deleteRecursively()
        }
        assertFalse(player.active)
    }
}
