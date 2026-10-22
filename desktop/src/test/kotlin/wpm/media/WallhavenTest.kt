package wpm.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.AppState
import wpm.Samples
import wpm.data.Database
import wpm.services.FakeDesktop
import wpm.services.FakeLivePlayer
import wpm.services.FakeLockScreen
import wpm.services.FakeProbe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Serves made-up results and a generated picture, so nothing goes online. */
class FakeOnline(private val picture: ByteArray) : WallpaperSource {
    var downloads = 0
    override fun search(query: String, sort: String, page: Int, atLeast: String?) = OnlineResults(
        (1..6).map { OnlineWallpaper("id$it", "https://wallhaven.cc/w/id$it", "https://w.wallhaven.cc/full/id/wallhaven-id$it.png",
            "https://th.wallhaven.cc/small/id/id$it.jpg", 1920, 1080, picture.size.toLong()) },
        page, 2,
    )
    override fun bytes(url: String): ByteArray {
        if ("/full/" in url) downloads++
        return picture
    }
}

class WallhavenTest {
    private val dir: Path = Files.createTempDirectory("wpm-online")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun onlyTheSitesOwnServers() {
        assertTrue(Wallhaven.isWallhavenHost("https://w.wallhaven.cc/full/ab/wallhaven-ab.jpg"))
        assertTrue(Wallhaven.isWallhavenHost("https://wallhaven.cc/api/v1/search"))
        assertFalse(Wallhaven.isWallhavenHost("http://w.wallhaven.cc/full/x.jpg")) // not https
        assertFalse(Wallhaven.isWallhavenHost("https://wallhaven.cc.evil.example/x.jpg"))
        assertFalse(Wallhaven.isWallhavenHost("https://evilwallhaven.cc/x.jpg"))
        assertFailsWith<IllegalArgumentException> { Wallhaven().bytes("https://example.com/x.jpg") }
    }

    @Test
    fun downloadsOnceIntoTheLibrary() {
        val picture = Files.readAllBytes(Samples.image(dir.resolve("p.png"), 320, 180))
        val online = FakeOnline(picture)
        val paths = AppPaths(dir.resolve("data")).ensure()
        Database(paths.dbPath).use { db ->
            val app = AppState(paths, db, true, FakeDesktop(), FakeLockScreen(Samples.image(dir.resolve("l.png"))), FakeLivePlayer(), null, FakeProbe(), online)
            val result = online.search("", "toplist", 1).items.first()
            // Downloads land in Pictures\Wallpapers: use a file name no real download has.
            val item = runBlocking { app.downloadOnline(result.copy(id = "wpmtest${System.nanoTime()}")) }
            try {
                assertEquals(320, item.width)
                assertEquals(result.pageUrl, item.sourceUrl)
                val again = runBlocking { app.downloadOnline(result) }
                assertEquals(item.id, again.id) // the same page isn't downloaded twice
                assertEquals(1, online.downloads)
            } finally {
                item.localPath?.let(Files::deleteIfExists)
                app.close()
            }
        }
        // Something that isn't a picture is refused.
        assertFailsWith<Exception> { downloadWallpaper(FakeOnline("<html>".toByteArray()), FakeOnline(picture).search("", "toplist", 1).items[0], dir.resolve("out")) }
    }

    /** Real search and thumbnail download. Opt-in: WPM_NETWORK_TEST=1. */
    @Test
    fun realSearch() {
        assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val wallhaven = Wallhaven()
        val results = wallhaven.search("mountains", "toplist", 1, "1920x1080")
        assertTrue(results.items.size >= 10, "${results.items.size} results")
        assertTrue(results.items.all { it.width >= 1920 && it.height >= 1080 })
        Imaging.decode(wallhaven.bytes(results.items.first().thumbUrl)).use { assertTrue(it.width > 0) }
        assertTrue(wallhaven.search("", "random", 1).items.isNotEmpty())
    }
}
