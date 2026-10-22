package wpm.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.AppState
import wpm.Samples
import wpm.data.Database
import wpm.engine.LiveKind
import wpm.engine.LiveSource
import wpm.engine.WebLivePlayer
import wpm.engine.openBrowserWindow
import wpm.engine.Chromium
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.FakeLockScreen
import wpm.services.FakeProbe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A LivelyProperties.json with every control type, as Lively wallpapers ship it. */
private val LIVELY_PROPERTIES = """
{
  "speed": { "max": 100, "min": 0, "text": "Speed", "type": "slider", "value": 50, "step": 1 },
  "opacity": { "max": 1, "min": 0, "text": "Opacity", "type": "slider", "value": 0.5, "step": 0.05 },
  "tint": { "text": "Tint", "type": "color", "value": "#FFC0CB" },
  "stars": { "text": "Show stars", "type": "checkbox", "value": true },
  "mode": { "text": "Mode", "type": "dropdown", "value": 1, "items": ["Calm", "Busy", "Wild"] },
  "image": { "text": "Background", "type": "folderDropdown", "value": "b.jpg", "folder": "images", "filter": "*.jpg|*.png" },
  "greeting": { "text": "Greeting", "type": "textbox", "value": "Hello" },
  "reset": { "text": "Reset", "type": "button", "value": "Restore defaults" },
  "about": { "type": "label", "value": "Made by someone" },
  "weird": { "type": "hologram", "value": 1 },
}
""".trimIndent()

class WallpaperPropertiesTest {
    private val dir: Path = Files.createTempDirectory("wpm-props")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun livelyFolder(): Path {
        val folder = dir.resolve("lively").createDirectories()
        folder.resolve("index.html").writeText("<html><body>hi</body></html>")
        folder.resolve(WallpaperProperties.LIVELY_FILE).writeText(LIVELY_PROPERTIES)
        folder.resolve("images").createDirectories().let { images ->
            listOf("a.jpg", "b.jpg", "c.PNG", "notes.txt").forEach { images.resolve(it).writeText("x") }
        }
        return folder
    }

    @Test
    fun readsLivelyProperties() {
        val list = WallpaperProperties.parseLively(LIVELY_PROPERTIES)
        assertEquals(listOf("speed", "opacity", "tint", "stars", "mode", "image", "greeting", "reset", "about"), list.map { it.name })
        val speed = list[0]
        assertEquals(JsonPrimitive(50L), speed.default)
        assertEquals(JsonPrimitive(100L), speed.normalize(JsonPrimitive(250)))
        assertEquals(JsonPrimitive(0.5), list[1].default)
        assertEquals(JsonPrimitive("#FFC0CB"), list[2].normalize(JsonPrimitive("nonsense")))
        assertEquals(JsonPrimitive("#112233"), list[2].normalize(JsonPrimitive("#123")))
        assertEquals(JsonPrimitive(1), list[4].normalize(JsonPrimitive(7))) // out of range: default
        assertEquals("about", list.last().label)
    }

    @Test
    fun savesValuesAndBuildsScripts() {
        val library = MediaLibrary(db, paths.thumbDir)
        val web = WebWallpapers(db, library, dir.resolve("web"))
        val item = db.getMedia(web.import(livelyFolder())!!)!!
        val properties = WallpaperProperties(db)
        val list = properties.read(item)
        assertEquals(9, list.size)
        assertEquals(listOf("a.jpg", "b.jpg", "c.PNG"), properties.folderFiles(item, list.first { it.name == "image" }))

        properties.set(item.id, "speed", JsonPrimitive(70))
        properties.set(item.id, "image", JsonPrimitive("c.PNG"))
        val values = properties.values(item.id, list)
        assertEquals(JsonPrimitive(70L), values["speed"])
        assertEquals(JsonPrimitive(true), values["stars"])

        val script = WallpaperProperties.loadScript(list, values)
        assertTrue("f(\"speed\",70)" in script, script)
        assertTrue("f(\"image\",\"images\\\\c.PNG\")" in script, script) // folder\file, as Lively sends it
        assertTrue("f(\"tint\",\"#FFC0CB\")" in script)
        assertTrue("\"reset\"" !in script && "\"about\"" !in script, "buttons and labels aren't sent on load")

        properties.reset(item.id)
        assertEquals(JsonPrimitive(50L), properties.values(item.id, list)["speed"])
    }

    @Test
    fun shaderAnnotations() {
        val code = """
            // @slider speed 0 2 1 Wave speed
            // @slider count 0 50 10
            // @color tint #ff8800 Tint
            // @checkbox rain on
            // @image photo Picture
            // @slider broken 5 1 3
            // @slider iTime 0 1 0
            // @color bad notacolor
            void mainImage(out vec4 c, in vec2 p) { c = vec4(speed); }
        """.trimIndent()
        val list = parseShaderProperties(code)
        assertEquals(listOf("speed", "count", "tint", "rain", "photo"), list.map { it.name })
        assertEquals("Wave speed", list[0].label)
        assertEquals(0.02, list[0].step, 1e-9)
        assertEquals(1.0, list[1].step) // whole numbers over a wide range
        assertEquals(JsonPrimitive("#FF8800"), list[2].default)
        assertEquals(JsonPrimitive(true), list[3].default)
        assertEquals(PropertyType.IMAGE, list[4].type)
        assertTrue(list.none { it.type == PropertyType.IMAGE && it.sentOnLoad })
    }

    private fun app(live: FakeLivePlayer = FakeLivePlayer()) = AppState(
        paths, db, startMinimized = true, desktopApi = FakeDesktop(),
        lockScreenApi = FakeLockScreen(Samples.image(dir.resolve("lock.png"))), live = live, videoReader = null, systemProbe = FakeProbe(),
    )

    @Test
    fun makeAliveCreatesAShaderShowingThePicture() {
        val live = FakeLivePlayer()
        val app = app(live)
        val picture = db.getMedia(app.library.importFiles(listOf(Samples.image(dir.resolve("Sunset.png"), 400, 200))).added.single())!!
        val alive = runBlocking { app.makeAlive(picture, "Ken Burns") }
        assertEquals("Sunset · Ken Burns", alive.filename)
        assertTrue(alive.isShader && live.active)
        val page = Path.of(java.net.URI(live.source!!)).readText()
        assertTrue("data:image/jpeg;base64," in page, "the picture is inside the page")
        assertEquals(listOf("photo", "speed", "zoom"), app.propertiesOf(alive).map { it.name })
        assertEquals(PICTURE_EFFECTS.size, app.shaders.effects().size)
        app.close()
    }

    /** Effects compile and a page gets its settings through livelyPropertyListener. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun effectsAndPropertiesInChromium() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val installDir = Path.of("build/chromium-test").toAbsolutePath()
        val chromium = Chromium.get(installDir, muted = true)
        val shaders = Shaders(db, WebWallpapers(db, MediaLibrary(db, paths.thumbDir), dir.resolve("web")), dir.resolve("shaders"), dir.resolve("pages"))
        for (effect in shaders.effects()) {
            var title: String? = null
            val window = openBrowserWindow(chromium, shaders.previewPage(effect.code), onTitle = { title = it })
            try {
                val deadline = System.currentTimeMillis() + 15_000
                while (title != "ok" && title?.startsWith("error") != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals("ok", title, "${effect.name} compiles")
            } finally {
                window.close()
            }
        }
        // A Lively-style page shows what it was sent in its title.
        val page = dir.resolve("props.html")
        page.writeText("""<html><head><title>waiting</title></head><body><script>
            var got = {};
            function livelyPropertyListener(name, value) { got[name] = value; document.title = JSON.stringify(got); }
            </script></body></html>""")
        val list = WallpaperProperties.parseLively(LIVELY_PROPERTIES)
        val script = WallpaperProperties.loadScript(list, list.associate { it.name to it.default })
        val player = WebLivePlayer(installDir)
        try {
            player.play(LiveSource(LiveKind.Web, page.toUri().toString(), onLoadScript = script))
            val deadline = System.currentTimeMillis() + 10_000
            while (player.pageTitle?.contains("greeting") != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val title = player.pageTitle ?: ""
            assertTrue("\"speed\":50" in title && "\"stars\":true" in title && "\"image\":\"images\\\\b.jpg\"" in title, title)
            player.runScript(WallpaperProperties.script(listOf("speed" to JsonPrimitive(99))))
            Thread.sleep(500)
            assertTrue("\"speed\":99" in (player.pageTitle ?: ""), player.pageTitle)
        } finally {
            player.stop()
        }
    }
}
