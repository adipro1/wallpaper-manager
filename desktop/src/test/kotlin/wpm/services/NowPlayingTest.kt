package wpm.services

import org.jetbrains.skia.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.AppState
import wpm.Samples
import wpm.data.Database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plays tracks on demand. */
class FakeMediaWatcher : MediaWatcher {
    var onChange: ((Track?) -> Unit)? = null
    var starts = 0
    var stops = 0
    val running get() = onChange != null

    override fun start(onChange: (Track?) -> Unit) {
        starts++
        this.onChange = onChange
    }

    override fun stop() {
        stops++
        onChange = null
    }

    fun play(track: Track?) = onChange?.invoke(track)
}

class NowPlayingTest {
    private val dir: Path = Files.createTempDirectory("wpm-music")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun orangeArt() = Samples.image(dir.resolve("art.png"), color = Color.makeRGB(240, 120, 0)).toString()

    @Test
    fun readsTheWatchersLines() {
        assertNull(Track.parse("{}"))
        assertNull(Track.parse("not json"))
        assertNull(Track.parse("""{"title":"","artist":""}"""))
        val track = Track.parse("""{"title":"Song","artist":"Band","album":"LP","app":"Spotify.exe","playing":true,"art":null,"future":1}""")!!
        assertEquals(Track("Song", "Band", "LP", "Spotify.exe", playing = true), track)
    }

    @Test
    fun pagesGetLivelysFormatAndShadersTheColor() {
        val nothing = NowPlaying.script(null)
        assertTrue("""window.livelyCurrentTrack("null")""" in nothing, nothing)
        assertTrue("setMusic([0,0,0],false)" in nothing)

        val track = Track("Song \"1\"", "Band", "LP", playing = true, art = orangeArt(), color = 0xFF8000)
        val script = NowPlaying.script(track)
        // livelyCurrentTrack gets a string holding JSON, as in Lively.
        val argument = script.substringAfter("livelyCurrentTrack(").substringBefore(");window.wpm")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(kotlinx.serialization.json.Json.decodeFromString<String>(argument))
        val obj = json as kotlinx.serialization.json.JsonObject
        assertEquals("Song \"1\"", obj["Title"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("#ff8000", (obj["Color"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue((obj["Thumbnail"] as kotlinx.serialization.json.JsonPrimitive).content.startsWith("iVBOR")) // a PNG
        assertTrue("setMusic([1.000,0.502,0.000],true)" in script, script)
    }

    @Test
    fun watchesOnlyWhileWantedAndColorsTheArt() {
        val watcher = FakeMediaWatcher()
        val changes = mutableListOf<Track?>()
        val now = NowPlaying(watcher) { changes.add(it) }
        now.setWanted(true)
        now.setWanted(true)
        assertEquals(1, watcher.starts)

        val art = orangeArt()
        watcher.play(Track("Song", "Band", playing = true, art = art))
        val color = assertNotNull(now.track?.color)
        val (h, s, _) = rgbToHsv(color)
        assertTrue(h in 15.0..45.0 && s > 0.5, "orange art gives an orange color, got #%06X".format(color))
        // The same track again (the watcher repeats nothing, but be safe): no new change.
        watcher.play(Track("Song", "Band", playing = true, art = art))
        assertEquals(1, changes.size)
        // Pausing is a change; the color is kept without reading the picture again.
        Files.delete(Path.of(art))
        watcher.play(Track("Song", "Band", playing = false, art = art))
        assertEquals(color, now.track?.color)
        assertFalse(now.track!!.playing)

        now.setWanted(false)
        assertEquals(1, watcher.stops)
        assertNull(now.track)
        assertEquals(null, changes.last())
    }

    @Test
    fun musicColorsTintTheAppAndGoBack() {
        val paths = AppPaths(dir.resolve("data")).ensure()
        val db = Database(paths.dbPath)
        val watcher = FakeMediaWatcher()
        val app = AppState(
            paths, db, startMinimized = true, desktopApi = FakeDesktop(),
            lockScreenApi = FakeLockScreen(Samples.image(dir.resolve("lock.png"))), live = FakeLivePlayer(), videoReader = null,
            systemProbe = FakeProbe(), mediaWatcher = watcher,
        )
        try {
            assertFalse(app.accent.enabled) // so the test never changes the real Windows accent
            fun waitFor(what: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!condition()) {
                    check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
                    Thread.sleep(20)
                }
            }
            app.setMusicColors(true)
            waitFor("the watcher") { watcher.running }
            watcher.play(Track("Song", "Band", playing = true, art = orangeArt()))
            waitFor("the music color") { app.musicColor != null }
            assertEquals(app.musicColor, app.shownThemeColor)
            assertEquals("Song", app.currentTrack?.title)

            app.setMusicColors(false)
            waitFor("the colors to go back") { app.musicColor == null && !watcher.running }
            assertEquals(app.themeColor, app.shownThemeColor)
        } finally {
            app.close()
            db.close()
        }
    }

    /** The real watcher starts, reports something (maybe "nothing playing") and stops. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun realWatcherRunsAndStops() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val watcher = PowerShellMediaWatcher(dir.resolve("scripts"), dir.resolve("art"))
        val lines = java.util.concurrent.LinkedBlockingQueue<Any>()
        watcher.start { lines.add(it ?: "nothing") }
        try {
            assertNotNull(lines.poll(30, java.util.concurrent.TimeUnit.SECONDS), "the watcher reports what is playing")
        } finally {
            val started = System.nanoTime()
            watcher.stop()
            assertTrue(System.nanoTime() - started < 3_500_000_000L, "stops when its input closes")
        }
    }
}
