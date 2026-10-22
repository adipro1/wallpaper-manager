package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.media.WebWallpapers
import wpm.win32.ResourceMonitor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerformanceTest {
    @Test
    fun measuresThisProcess() {
        ResourceMonitor().use { monitor ->
            monitor.sample()
            val end = System.nanoTime() + 300_000_000
            var x = 0L
            while (System.nanoTime() < end) x += System.nanoTime() % 7 // keep a core busy
            val usage = monitor.sample()
            assertTrue(usage.cpuPercent > 1.0, "cpu ${usage.cpuPercent} ($x)")
            assertTrue(usage.memoryMb > 20, "memory ${usage.memoryMb} MB")
            assertTrue(usage.processes >= 1)
            println("Usage: $usage")
        }
    }

    @Test
    fun frameRateLimitIsValidJavaScript() {
        val script = WebWallpapers.frameRateLimit(30)
        assertTrue(script.startsWith("(function(fps)") && script.endsWith("})(30);") && '\n' !in script, script)
    }

    /** A page's animation frames are capped at 30 fps. Opt-in: WPM_DESKTOP_TEST=1 (shows a page on the desktop briefly). */
    @Test
    fun webFrameRateIsCapped() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val dir = Files.createTempDirectory("wpm-fps")
        val page = dir.resolve("fps.html")
        page.writeText("""<html><head><title>0</title></head><body style="background:#123"><script>
            var frames = 0, start = 0;
            function tick(t) { if (!start) start = t; frames++; if (t - start >= 2000) { document.title = "fps " + Math.round(frames / ((t - start) / 1000)); return; } requestAnimationFrame(tick); }
            setTimeout(function () { requestAnimationFrame(tick); }, 300);
            </script></body></html>""")
        fun measure(script: String?): Int {
            val player = WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
            try {
                player.play(LiveSource(LiveKind.Web, page.toUri().toString(), onLoadScript = script))
                val deadline = System.currentTimeMillis() + 10_000
                while (player.pageTitle?.startsWith("fps") != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
                return assertNotNull(player.pageTitle?.removePrefix("fps ")?.toIntOrNull(), "title ${player.pageTitle}")
            } finally {
                player.stop()
            }
        }
        val full = measure(null)
        val capped = measure(WebWallpapers.frameRateLimit(30))
        println("Full: $full fps, capped: $capped fps")
        assertTrue(capped in 25..32, "capped at $capped fps")
        assertTrue(full > capped + 10, "uncapped runs at the display rate ($full fps)")
        dir.toFile().deleteRecursively()
    }
}
