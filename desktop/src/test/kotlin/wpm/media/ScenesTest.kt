package wpm.media

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.data.Database
import wpm.data.Settings
import wpm.services.Place
import wpm.services.Weather
import wpm.services.World
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenesTest {
    private val dir: Path = Files.createTempDirectory("wpm-scenes")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val web = WebWallpapers(db, library, paths.dataDir.resolve("web"))
    private val scenes = Scenes(db, Settings(db), web, paths.dataDir.resolve("scenes"))

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun installsOnceAndStaysDeleted() {
        val ids = scenes.install()
        assertEquals(listOf("Night sky", "Live Earth"), ids.map { db.getMedia(it)!!.filename })
        val item = db.getMedia(ids.first())!!
        assertFalse(item.interactive)
        for (scene in Scenes.SCENES) {
            for (file in scene.files) assertTrue(scenes.folderOf(scene).resolve(file).exists(), "${scene.id}/$file is copied")
        }
        assertEquals(Scenes.EARTH, scenes.sceneOf(WebWallpapers.pageUrl(db.getMedia(ids.last())!!)))
        assertTrue(scenes.isScene(WebWallpapers.pageUrl(item)))
        assertFalse(scenes.isScene(paths.dataDir.resolve("web/other/index.html").toUri().toString()))
        assertFalse(scenes.isScene(null))
        // Its settings come from LivelyProperties.json, like any web wallpaper's.
        assertTrue(WallpaperProperties(db).read(item).any { it.name == "moonSize" })

        assertTrue(item.thumbnail != null && paths.thumbDir.resolve(item.thumbnail!!).exists(), "${item.thumbnail}")
        assertEquals(emptyList(), scenes.install(), "added once")
        // 3.3.0 added them without a thumbnail: the next start makes one.
        db.setThumbnail(item.id, null)
        scenes.install()
        assertTrue(db.getMedia(item.id)!!.thumbnail != null, "the missing thumbnail is made")
        ids.forEach(db::removeMedia)
        assertEquals(emptyList(), scenes.install(), "a deleted scene stays deleted")
    }

    @Test
    fun theSceneGetsThePlaceTheSkyAndTheWeather() {
        val world = World({ Place("Berlin", 52.52, 13.4) }, clock = { Instant.parse("2025-01-10T19:00:00Z") },
            source = { Weather(clouds = 0.4, fog = 0.2) })
        world.refreshWeather()
        val script = world.sceneScript(weatherOn = true, language = "de") { if (it == "Mercury") "Merkur" else it }
        assertTrue("lat:52.520,lon:13.400" in script, script)
        assertTrue("label:\"Merkur\"" in script && "name:\"Venus\"" in script, script)
        assertTrue("clouds:0.400,fog:0.200" in script, script)
        assertTrue("lang:\"de\"" in script, script)
        assertTrue("clouds:0.000,fog:0.000" in world.sceneScript(weatherOn = false, language = "en"), "no weather when it is off")
        val nowhere = World({ null }).sceneScript(weatherOn = true, language = "en")
        assertFalse("lat:" in nowhere, "no place, no coordinates: the page guesses from the time zone")
    }

    /**
     * Plays the night sky as the wallpaper at night, at dusk and by day (the page's clock moved)
     * and saves the pictures to WPM_SCREENSHOT_DIR. Opt-in: WPM_DESKTOP_TEST=1.
     */
    @Test
    fun nightSkyDraws() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val item = db.getMedia(scenes.install().first())!!
        val player = wpm.engine.WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
        val place = Place("Berlin", 52.52, 13.4)
        try {
            player.play(wpm.engine.LiveSource(wpm.engine.LiveKind.Web, WebWallpapers.pageUrl(item)))
            Thread.sleep(4_000)
            // Times in Berlin: a winter night, dusk, noon, a full moon and the summer Milky Way (the page shows "now" plus the offset).
            for ((name, time) in listOf(
                "night" to "2025-01-10T22:00:00Z", "dusk" to "2025-01-10T16:10:00Z", "day" to "2025-01-10T11:30:00Z",
                "moon" to "2025-01-13T23:30:00Z", "summer" to "2025-08-01T21:45:00Z",
            )) {
                val at = Instant.parse(time)
                val world = World({ place }, clock = { at }, source = { Weather(clouds = if (name == "day") 0.3 else 0.0) })
                world.refreshWeather()
                val offset = at.toEpochMilli() - System.currentTimeMillis()
                player.runScript(world.sceneScript(weatherOn = true, language = "en") + "window.wpm.setSky({offset:$offset});")
                Thread.sleep(2_500)
                player.runScript("document.title = JSON.stringify(window.wpm.debug());")
                Thread.sleep(500)
                println("$name: ${player.pageTitle}")
                assertTrue(player.pageTitle?.contains("\"error\":null") == true, "no error: ${player.pageTitle}")
                val shot = player.snapshot()!!
                System.getenv("WPM_SCREENSHOT_DIR")?.let { javax.imageio.ImageIO.write(shot, "png", java.io.File(it, "sky-$name.png")) }
            }
        } finally {
            player.stop()
        }
    }

    /**
     * Plays Live Earth with real pictures from NASA and saves the globe and the map to
     * WPM_SCREENSHOT_DIR. Opt-in: WPM_DESKTOP_TEST=1 and WPM_NETWORK_TEST=1.
     */
    @Test
    fun liveEarthDraws() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1" && System.getenv("WPM_NETWORK_TEST") == "1", "desktop and network test not requested")
        val item = db.getMedia(scenes.install().last())!!
        val earth = wpm.services.EarthImagery(scenes.folderOf(Scenes.EARTH).resolve("textures"))
        assertTrue(earth.refresh())
        val player = wpm.engine.WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
        val world = World({ Place("Berlin", 52.52, 13.4) })
        try {
            player.play(wpm.engine.LiveSource(wpm.engine.LiveKind.Web, WebWallpapers.pageUrl(item)))
            Thread.sleep(3_000)
            player.runScript(world.sceneScript(weatherOn = false, language = "en") +
                "window.wpm.setTextures(${kotlinx.serialization.json.JsonPrimitive(earth.version())});" +
                wpm.services.IssTracker.script(wpm.services.IssTracker().update()))
            Thread.sleep(6_000)
            for ((name, script) in listOf("globe" to "", "map" to "livelyPropertyListener('mode', 1);")) {
                player.runScript(script)
                Thread.sleep(1_500)
                player.runScript("document.title = JSON.stringify(window.wpm.debug());")
                Thread.sleep(500)
                println("$name: ${player.pageTitle}")
                assertTrue(player.pageTitle?.contains("\"textures\":[\"day\",\"lights\",\"clouds\"]") == true ||
                    player.pageTitle?.contains("\"error\":null") == true && player.pageTitle?.contains("clouds") == true, "pictures loaded: ${player.pageTitle}")
                assertTrue(player.pageTitle?.contains("\"error\":null") == true, "no error: ${player.pageTitle}")
                val shot = player.snapshot()!!
                System.getenv("WPM_SCREENSHOT_DIR")?.let { javax.imageio.ImageIO.write(shot, "png", java.io.File(it, "earth-$name.png")) }
            }
        } finally {
            player.stop()
        }
    }
}
