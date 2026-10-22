package wpm.media

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.engine.LiveKind
import wpm.engine.LiveSource
import wpm.engine.WebLivePlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A web wallpaper's project.json as Wallpaper Engine writes it. */
private val WEB_PROJECT = """
{
  "file": "index.html",
  "preview": "preview.png",
  "title": "Neon <b>City</b>",
  "type": "web",
  "general": {
    "properties": {
      "schemecolor": { "order": 0, "text": "ui_browse_properties_scheme_color", "type": "color", "value": "0.5 0.25 1" },
      "speed": { "order": 2, "text": "Speed", "type": "slider", "min": 1, "max": 10, "value": 4 },
      "rain": { "order": 1, "text": "<span>Rain</span>", "type": "bool", "value": true },
      "mode": { "order": 3, "text": "Mode", "type": "combo", "value": "fast",
                "options": [ { "label": "Slow", "value": "slow" }, { "label": "Fast", "value": "fast" } ] },
      "photo": { "order": 4, "text": "Photo", "type": "file", "value": "" }
    }
  }
}
""".trimIndent()

class WallpaperEngineTest {
    private val dir: Path = Files.createTempDirectory("wpm-we")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val web = WebWallpapers(db, library, dir.resolve("web"))

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** steamapps/workshop/content/431960 with a web project, a scene and a broken video. */
    private fun steamLibrary(): Path {
        val lib = dir.resolve("SteamLibrary")
        val workshop = lib.resolve("steamapps/workshop/content/431960").createDirectories()
        workshop.resolve("111").createDirectories().also {
            it.resolve("project.json").writeText(WEB_PROJECT)
            it.resolve("index.html").writeText("<html><body>neon</body></html>")
            Samples.image(it.resolve("preview.png"))
        }
        workshop.resolve("222").createDirectories().resolve("project.json")
            .writeText("""{"title":"Forest scene","type":"scene","file":"scene.json"}""")
        workshop.resolve("333").createDirectories().resolve("project.json")
            .writeText("""{"title":"Lost video","type":"video","file":"gone.mp4"}""")
        lib.resolve("steamapps/common/wallpaper_engine/projects/myprojects/mine").createDirectories().also {
            it.resolve("project.json").writeText("""{"title":"My page","type":"web","file":"page.html"}""")
            it.resolve("page.html").writeText("<html></html>")
        }
        return lib
    }

    @Test
    fun findsAndImportsProjects() {
        val projects = WallpaperEngine.findProjects(listOf(steamLibrary()))
        assertEquals(listOf("My page", "Neon <b>City</b>", "Forest scene", "Lost video"), projects.map { it.title })
        assertEquals(listOf(true, true, false, false), projects.map { it.supported })
        assertEquals("Scene wallpapers need Wallpaper Engine", projects[2].reason)

        val neon = projects.first { it.title.startsWith("Neon") }
        val id = WallpaperEngine.import(neon, db, library, web)!!
        val item = db.getMedia(id)!!
        assertTrue(item.isWeb)
        assertEquals("index.html", item.entry)
        assertNull(WallpaperEngine.import(neon, db, library, web)) // only once
        assertTrue(WebWallpapers.looksLikeWebWallpaper(neon.folder)) // dropping the folder works too

        // Its settings, in Wallpaper Engine's order, with HTML and "ui_" keys cleaned up.
        val props = WallpaperProperties(db).read(item)
        assertEquals(listOf("schemecolor", "rain", "speed", "mode"), props.map { it.name }) // "file" isn't supported
        assertEquals(listOf("schemecolor", "Rain", "Speed", "Mode"), props.map { it.label })
        assertEquals(JsonPrimitive("#8040FF"), props[0].default)
        assertEquals(JsonPrimitive(1), props[3].default) // "fast"
        val script = WallpaperProperties.loadScript(props, props.associate { it.name to it.default })
        assertTrue("applyUserProperties" in script && "livelyPropertyListener" !in script, script)
        assertTrue("\"mode\":{\"value\":\"fast\"}" in script, script)
        assertTrue("\"schemecolor\":{\"value\":\"0.50196 0.25098 1\"}" in script, script)
        assertTrue("\"rain\":{\"value\":true}" in script, script)
    }

    @Test
    fun colorsConvert() {
        assertEquals("#FF8000", WallpaperProperties.engineColorToHex("1 0.5 0"))
        assertEquals("1 0.50196 0", WallpaperProperties.hexToEngineColor("#FF8000"))
        assertNull(WallpaperProperties.engineColorToHex("red"))
        assertFalse(WallpaperEngine.read(dir)?.supported ?: false)
    }

    /** A Wallpaper Engine page receives its settings in real Chromium. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun pageGetsSettingsInChromium() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val page = dir.resolve("we.html")
        page.writeText("""<html><head><title>waiting</title></head><body><script>
            window.wallpaperPropertyListener = { applyUserProperties: function (p) {
                document.title = (p.mode ? p.mode.value : "") + "|" + (p.schemecolor ? p.schemecolor.value : "") + "|" + (p.speed ? p.speed.value : "");
            } };
            </script></body></html>""")
        val props = WallpaperProperties.parseEngine(WEB_PROJECT)
        val player = WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
        try {
            player.play(LiveSource(LiveKind.Web, page.toUri().toString(), onLoadScript = WallpaperProperties.loadScript(props, props.associate { it.name to it.default })))
            val deadline = System.currentTimeMillis() + 10_000
            while (player.pageTitle == "waiting" || player.pageTitle == null) {
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(100)
            }
            assertEquals("fast|0.50196 0.25098 1|4", player.pageTitle)
        } finally {
            player.stop()
        }
    }
}
