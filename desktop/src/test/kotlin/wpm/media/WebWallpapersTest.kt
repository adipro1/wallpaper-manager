package wpm.media

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.engine.LiveKind
import wpm.engine.LiveRouter
import wpm.engine.LiveSource
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.WallpaperService
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebWallpapersTest {
    private val dir: Path = Files.createTempDirectory("wpm-webimport")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val web = WebWallpapers(db, library, paths.dataDir.resolve("web"))

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun page(folder: Path, name: String = "index.html"): Path =
        folder.createDirectories().resolve(name).also { it.writeText("<html><body>hi</body></html>") }

    @Test
    fun importsFolderWithIndexPage() {
        val folder = dir.resolve("Rain")
        page(folder)
        assertTrue(WebWallpapers.looksLikeWebWallpaper(folder))
        val id = web.import(folder)!!
        val item = db.getMedia(id)!!
        assertEquals("Rain", item.filename)
        assertTrue(item.isWeb && item.interactive && !item.isRemote)
        assertEquals("index.html", item.entry)
        assertTrue(item.thumbnail!!.startsWith(WebWallpapers.CARD_PREFIX)) // no preview image: a title card
        assertEquals(folder.resolve("index.html").toUri().toString(), WebWallpapers.pageUrl(item))
        assertNull(web.import(folder)) // only once
    }

    @Test
    fun readsManifestsAndPreviews() {
        val ours = dir.resolve("ours")
        page(ours, "main.html")
        Samples.image(ours.resolve("shot.png"))
        ours.resolve("wpm.json").writeText("""{"title": "Fireflies", "entry": "main.html", "audio": true, "interactive": false, "preview": "shot.png"}""")
        val a = db.getMedia(web.import(ours)!!)!!
        assertEquals("Fireflies", a.filename)
        assertEquals("main.html", a.entry)
        assertTrue(a.audio)
        assertFalse(a.interactive)
        assertFalse(a.thumbnail!!.startsWith(WebWallpapers.CARD_PREFIX)) // made from the preview

        val lively = dir.resolve("lively")
        page(lively, "game.html")
        lively.resolve("LivelyInfo.json").writeText("""{"AppVersion":"2.0","Title":"Snake","FileName":"game.html","Type":1}""")
        val b = db.getMedia(web.import(lively)!!)!!
        assertEquals("Snake", b.filename)
        assertEquals("game.html", b.entry)
    }

    @Test
    fun importsHtmlFileZipAndWebsite() {
        val html = page(dir.resolve("single"), "clock.html")
        val single = db.getMedia(web.import(html)!!)!!
        assertEquals("clock", single.filename)
        assertNull(single.entry)
        assertEquals(html.toUri().toString(), WebWallpapers.pageUrl(single))

        val zip = dir.resolve("Waves.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("Waves/index.html")); out.write("<html></html>".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("Waves/style.css")); out.write("body{}".toByteArray()); out.closeEntry()
        }
        val unzipped = db.getMedia(web.import(zip)!!)!!
        assertEquals("Waves", unzipped.filename) // the wrapping folder inside the zip
        assertTrue(Files.isRegularFile(Path.of(unzipped.path!!).resolve("style.css")))

        val site = db.getMedia(web.addWebsite("https://www.example.com/art")!!)!!
        assertEquals("example.com", site.filename)
        assertTrue(site.isRemote && site.isWeb)
        assertEquals("https://www.example.com/art", WebWallpapers.pageUrl(site))
        assertNull(web.addWebsite("https://www.example.com/art"))
    }

    @Test
    fun rejectsUnsafeZipsAndFoldersWithoutPages() {
        val zip = dir.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("../../outside.html")); out.write("x".toByteArray()); out.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { web.import(zip) }
        val empty = dir.resolve("pictures").createDirectories()
        Samples.image(empty.resolve("a.png"))
        assertFalse(WebWallpapers.looksLikeWebWallpaper(empty))
        assertFailsWith<IllegalArgumentException> { web.import(empty) }
    }

    @Test
    fun liveRouterUsesOneEngineAtATime() {
        val video = FakeLivePlayer()
        val webEngine = FakeLivePlayer()
        var created = 0
        val router = LiveRouter(video) { created++; webEngine }
        router.play(LiveSource(LiveKind.Video, "a.mp4"))
        assertEquals(0, created) // the web engine starts only when needed
        router.play(LiveSource(LiveKind.Web, "file:///page.html", interactive = true))
        assertFalse(video.active)
        assertTrue(webEngine.active && webEngine.interactiveOn)
        assertEquals(LiveKind.Web, router.kind)
        router.play(LiveSource(LiveKind.Video, "b.mp4"))
        assertFalse(webEngine.active)
        assertEquals("b.mp4", router.source)
        assertEquals(1, created)
    }

    @Test
    fun webItemsBecomeLiveWallpapers() {
        val live = FakeLivePlayer()
        val service = WallpaperService(db, paths, FakeDesktop(), live)
        service.sourceFor = { item, _ -> LiveSource(if (item.isVideo) LiveKind.Video else LiveKind.Web, WebWallpapers.pageUrl(item), interactive = item.interactive) }
        val folder = dir.resolve("Stars")
        page(folder)
        val item = db.getMedia(web.import(folder)!!)!!
        service.set(item)
        assertEquals(LiveKind.Web, live.kind)
        assertEquals("web", service.state.kind)
        service.shutdown()
        val restarted = FakeLivePlayer()
        val again = WallpaperService(db, paths, FakeDesktop(), restarted).also { it.sourceFor = service.sourceFor }
        assertTrue(again.restoreOnStartup())
        assertEquals(LiveKind.Web, restarted.kind)
    }
}
