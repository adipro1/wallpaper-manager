package wpm.media

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.data.Database
import wpm.engine.Chromium
import wpm.engine.openBrowserWindow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShadersTest {
    private val dir: Path = Files.createTempDirectory("wpm-shaders")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val web = WebWallpapers(db, library, paths.dataDir.resolve("web"))
    private val shaders = Shaders(db, web, paths.dataDir.resolve("shaders"), paths.cacheDir.resolve("pages"))

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun samplesShip() {
        val samples = shaders.samples()
        assertEquals(listOf("Plasma", "Aurora", "Ripples", "Equalizer"), samples.map { it.name })
        assertTrue(samples.all { "mainImage" in it.code })
    }

    @Test
    fun createSaveAndPage() {
        val id = shaders.create("Glow", "void mainImage(out vec4 c, in vec2 p) { c = vec4(1.0); } // </script> must not break the page")
        val item = db.getMedia(id)!!
        assertTrue(item.isShader && item.interactive && !item.audio)
        assertTrue(shaders.code(item).contains("vec4(1.0)"))
        // Same name again gets its own file.
        val second = db.getMedia(shaders.create("Glow", "void mainImage(out vec4 c, in vec2 p) {}"))!!
        assertTrue(second.path != item.path)

        shaders.save(item, "void mainImage(out vec4 c, in vec2 p) { c = vec4(0.5); }")
        val page = Path.of(java.net.URI(shaders.pageFor(item, 30)))
        val html = page.readText()
        assertTrue("vec4(0.5)" in html)
        assertTrue("\"fps\":30" in html)
        assertTrue("</script> must" !in html)
    }

    @Test
    fun importsGlslFiles() {
        val file = dir.resolve("waves.frag").also { it.writeText("uniform float x; void mainImage(out vec4 c, in vec2 p) { c = texture(iChannel0, p); vec4 m = iMouse; }") }
        val item = db.getMedia(shaders.import(file))!!
        assertEquals("waves", item.filename)
        assertTrue(item.audio && item.interactive)
        assertFailsWith<IllegalArgumentException> { shaders.import(dir.resolve("x.txt").also { it.writeText("hi") }) }
        assertFailsWith<IllegalArgumentException> { shaders.import(dir.resolve("plain.glsl").also { it.writeText("void main() {}") }) }
    }

    /** Compiles every sample in real Chromium. Opt-in: WPM_DESKTOP_TEST=1 (uses build/chromium-test). */
    @Test
    fun samplesCompileInChromium() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val app = Chromium.get(Path.of("build/chromium-test").toAbsolutePath(), muted = true)
        val samples = shaders.samples() + SampleShader("Broken", "void mainImage(out vec4 c, in vec2 p) { c = vec4(nope); }")
        for (sample in samples) {
            var title: String? = null
            val window = openBrowserWindow(app, shaders.previewPage(sample.code), onTitle = { title = it })
            try {
                val deadline = System.currentTimeMillis() + 15_000
                while (title != "ok" && title?.startsWith("error") != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
                println("${sample.name}: $title")
                if (sample.name == "Broken") assertTrue(title?.startsWith("error: line 1") == true, "errors point at the line: $title")
                else assertEquals("ok", title, "${sample.name} compiles")
            } finally {
                window.close()
            }
        }
    }

    /** Plays a sample as the wallpaper and saves what it draws to WPM_SCREENSHOT. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun sampleRendersAsWallpaper() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val id = shaders.create("Aurora", shaders.samples().first { it.name == "Aurora" }.code)
        val player = wpm.engine.WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
        try {
            player.play(wpm.engine.LiveSource(wpm.engine.LiveKind.Web, shaders.pageFor(db.getMedia(id)!!, 60)))
            Thread.sleep(3_000)
            assertEquals("ok", player.pageTitle)
            val shot = player.snapshot()!!
            System.getenv("WPM_SCREENSHOT")?.let { javax.imageio.ImageIO.write(shot, "png", java.io.File(it)) }
        } finally {
            player.stop()
        }
    }
}
