package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.jetbrains.skia.Color
import wpm.Samples
import wpm.data.Database
import wpm.media.MediaLibrary
import wpm.media.Shaders
import wpm.media.TRANSITIONS
import wpm.media.WebWallpapers
import wpm.win32.Monitors
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionsTest {
    private val dir = Files.createTempDirectory("wpm-transitions")
    private val db = Database(dir.resolve("library.db"))
    private val shaders = Shaders(db, WebWallpapers(db, MediaLibrary(db, dir.resolve("thumbs")), dir.resolve("web")), dir.resolve("shaders"), dir.resolve("pages"))
    private val red = Samples.image(dir.resolve("red.png"), 320, 180, Color.makeRGB(200, 40, 40))
    private val blue = Samples.image(dir.resolve("blue.png"), 320, 180, Color.makeRGB(30, 60, 200))

    @Test
    fun transitionPagesCarryBothPicturesAndTheirLength() {
        for (style in TRANSITIONS) {
            val page = Path.of(java.net.URI(shaders.transitionPage(style, red, blue, 1.5))).readText()
            assertTrue("\"transition\":{\"duration\":1.5}" in page, style)
            assertEquals(2, Regex("data:image/jpeg;base64,").findAll(page).count(), style)
        }
    }

    /** Each transition plays over the real desktop, then the new picture is applied once. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun playsOverTheDesktop() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val installDir = Path.of("build/chromium-test").toAbsolutePath()
        Chromium.get(installDir, muted = true)
        val transitions = PictureTransitions(installDir, Monitors::all)
        for (style in TRANSITIONS) {
            var applied = 0
            val started = System.currentTimeMillis()
            transitions.play(shaders.transitionPage(style, red, blue, 0.8), 0.8) { applied++ }
            val took = System.currentTimeMillis() - started
            assertEquals(1, applied, style)
            // It really played (a page that never got ready would apply at once).
            assertTrue(took >= 800, "$style took $took ms")
        }
    }
}
