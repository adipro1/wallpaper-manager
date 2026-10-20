package wpm.services

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.data.MediaItem
import wpm.media.MediaLibrary
import wpm.win32.DesktopWallpaperApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what would have been sent to Windows. */
class FakeDesktop(var current: String? = null) : DesktopWallpaperApi {
    val applied = mutableListOf<Pair<String, Boolean>>()
    var currentStyle = "fit"

    override fun currentWallpaper() = current
    override fun transcodedWallpaper(): Path? = null
    override fun setWallpaper(path: String, persist: Boolean) {
        applied += path to persist
        if (persist) current = path
    }
    override fun style() = currentStyle
    override fun setStyle(style: String) {
        currentStyle = style
    }
}

class FakeLockScreen(private val source: Path) : LockScreenApi {
    val shown = mutableListOf<Path>()
    var spotlightOn = true

    override fun setImage(file: Path) {
        shown.add(file)
    }
    override fun readImage(target: Path) {
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    override fun spotlightEnabled() = spotlightOn
    override fun setSpotlight(enabled: Boolean) {
        spotlightOn = enabled
    }
}

class WallpaperServiceTest {
    private val dir: Path = Files.createTempDirectory("wpm-wall")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val original = Samples.image(dir.resolve("original.jpg"))
    private val fake = FakeDesktop(original.toString())
    private val service = WallpaperService(db, paths, fake, FakeLivePlayer())

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun import(vararg names: String): List<MediaItem> {
        val files = names.map { Samples.image(dir.resolve(it)) }
        library.importFiles(files)
        return files.map { f -> db.listMedia().first { it.path == f.toAbsolutePath().normalize().toString() } }
    }

    @Test
    fun setsImageAndBacksUpOriginalOnce() {
        val (a, b) = import("a.png", "b.jpg")
        service.setImage(a, "fit")
        assertEquals(a.path to true, fake.applied.last())
        assertEquals(DesktopState("image", a.id, a.path, "fit"), service.state)
        val backup = paths.dataDir.resolve("backup/original_wallpaper.jpg")
        assertTrue(backup.exists())

        service.setImage(b) // uses the remembered style
        assertEquals("fit", fake.currentStyle)
        assertEquals(b.id, service.currentItem()?.id)
        assertTrue(service.hasOriginal())

        service.restoreOriginal()
        assertEquals(backup.toString(), fake.applied.last().first)
        assertEquals(DesktopState(), service.state)
    }

    @Test
    fun convertsWebpToPng() {
        val (webp) = import("pic.webp")
        service.setImage(webp)
        val applied = Path.of(fake.applied.last().first)
        assertEquals("png", applied.extension)
        assertTrue(applied.exists())
        assertNotEquals(webp.path, applied.toString())
    }

    @Test
    fun missingFileFails() {
        val (a) = import("gone.png")
        Files.delete(Path.of(a.path))
        assertFailsWith<java.io.FileNotFoundException> { service.setImage(a) }
        assertTrue(fake.applied.isEmpty())
    }

    @Test
    fun nextAndRandomCycleThroughLibrary() {
        val items = import("1.png", "2.png", "3.png")
        val shown = (1..4).map { service.nextFromLibrary()!!.id }
        assertEquals(3, shown.take(3).toSet().size) // every item once before repeating
        assertEquals(shown[0], shown[3])
        repeat(5) {
            val before = service.state.mediaId
            assertNotEquals(before, service.randomFromLibrary()!!.id)
        }
        assertEquals(3, items.size)
    }

    @Test
    fun emptyLibrary() {
        assertNull(service.nextFromLibrary())
        assertNull(service.randomFromLibrary())
    }

    @Test
    fun styleChangeReappliesCurrentImage() {
        val (a) = import("a.png")
        service.setImage(a, "fill")
        service.setImageStyle("center")
        assertEquals("center", fake.currentStyle)
        assertEquals("center", service.state.style)
    }

    @Test
    fun corruptStateIsIgnored() {
        db.execute("INSERT INTO settings (key, value) VALUES ('desktop_wallpaper', '{\"kind\": 5, \"bogus\": true}')")
        assertEquals(DesktopState(), service.state)
    }

    @Test
    fun lockScreenSetAndRestore() {
        val currentLock = Samples.image(dir.resolve("lock-now.png"))
        val api = FakeLockScreen(currentLock)
        val lock = LockScreenService(db, paths, api)
        val (jpg, webp) = import("a.jpg", "b.webp")

        lock.setImage(jpg)
        assertEquals(Path.of(jpg.path), api.shown.last())
        assertEquals(jpg.id, lock.currentItem()?.id)
        lock.setImage(webp)
        assertEquals("png", api.shown.last().extension) // converted

        api.spotlightOn = false
        lock.restoreOriginal()
        val backup = paths.dataDir.resolve("backup/original_lockscreen.png")
        assertEquals(backup, api.shown.last())
        assertTrue(api.spotlightOn) // Spotlight was on before the first change
        assertNull(lock.currentItem())
    }
}
