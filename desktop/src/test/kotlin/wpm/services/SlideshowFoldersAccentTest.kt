package wpm.services

import org.jetbrains.skia.Color
import org.jetbrains.skia.Surface
import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.data.Settings
import wpm.media.MediaLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlideshowFoldersAccentTest {
    private val dir: Path = Files.createTempDirectory("wpm-t2")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun lockScreenSlideshow() {
        val lock = FakeLockScreen(Samples.image(dir.resolve("lock.png")))
        val service = LockScreenService(db, paths, lock)
        var now = 1_000_000.0
        val slideshow = LockScreenSlideshow(db, service) { now }
        val ids = library.importFiles((1..3).map { Samples.image(dir.resolve("p$it.png")) }).added
        val playlist = db.createPlaylist("Lock")
        db.addToPlaylist(playlist, ids)

        assertEquals(ids[0], slideshow.start(playlist, 5).id) // at least 15 minutes
        assertEquals(15 * 60.0, slideshow.secondsUntilChange())
        assertFalse(slideshow.isDue())
        now += 15 * 60
        assertTrue(slideshow.isDue())
        assertEquals(ids[1], slideshow.advance().id)
        assertEquals(2, lock.shown.size)
        // Missing files are skipped.
        Files.delete(dir.resolve("p3.png"))
        now += 15 * 60
        assertEquals(ids[0], slideshow.advance().id)
        // Survives a restart (state is saved).
        assertEquals(playlist, LockScreenSlideshow(db, service) { now }.state?.playlistId)
        slideshow.stop()
        assertNull(slideshow.state)
        assertFailsWith<IllegalArgumentException> { slideshow.start(db.createPlaylist("Empty"), 60) }
    }

    @Test
    fun watchedFoldersAddRemoveAndMove() {
        val folder = dir.resolve("watched").createDirectories()
        Samples.image(folder.resolve("a.png"))
        Samples.image(folder.resolve("sub/b.png"))
        val watched = WatchedFolders(db, library)
        assertEquals(FolderSync(added = 2), watched.add(folder))
        assertFailsWith<IllegalArgumentException> { watched.add(folder.resolve("sub")) } // inside a watched folder

        val b = db.listMedia().first { it.filename == "b.png" }
        db.addTag(listOf(b.id), "keep")
        Samples.image(folder.resolve("c.png"))
        folder.resolve("sub/b.png").moveTo(folder.resolve("b.png"))
        Files.delete(folder.resolve("a.png"))
        assertEquals(FolderSync(added = 1, removed = 1, moved = 1), watched.sync(folder))
        assertEquals(setOf("b.png", "c.png"), db.listMedia().map { it.filename }.toSet())
        assertEquals(listOf("keep"), db.tagsOf(b.id)) // moved, not re-added

        // An unreachable folder (e.g. an unplugged drive) removes nothing.
        folder.toFile().renameTo(dir.resolve("unplugged").toFile())
        assertEquals(FolderSync(), watched.sync(folder))
        assertEquals(2, db.countMedia())
        watched.remove(folder)
        assertTrue(watched.folders.isEmpty())
    }

    @Test
    fun watchedFoldersNoticeChanges() {
        val folder = dir.resolve("live").createDirectories()
        val changes = java.util.concurrent.LinkedBlockingQueue<FolderSync>()
        WatchedFolders(db, library) { changes.add(it) }.use { watched ->
            watched.add(folder)
            watched.start()
            Thread.sleep(500)
            Samples.image(folder.resolve("new.png"))
            val change = changes.poll(20, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(change, "the new file was noticed")
            assertEquals(1, db.countMedia())
        }
    }

    private class FakeAccent(var current: AccentBackup = AccentBackup(accentColor = 0x11223344, autoColorization = "1")) : AccentApi {
        override fun read() = current
        override fun write(backup: AccentBackup) {
            current = backup
        }
    }

    private fun solid(rgb: Int, other: Int = Color.makeRGB(128, 128, 128)) = Surface.makeRasterN32Premul(100, 60).use { s ->
        s.canvas.clear(other)
        s.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 0f, 30f, 60f), org.jetbrains.skia.Paint().apply { color = rgb })
        s.makeImageSnapshot()
    }

    @Test
    fun accentColorFollowsTheWallpaperAndComesBack() {
        // A vivid orange stripe beats a larger grey area.
        val orange = dominantColor(solid(Color.makeRGB(240, 120, 20)))!!
        val (hue, _, _) = rgbToHsv(orange)
        assertTrue(hue in 20.0..35.0, "hue $hue")
        assertNull(dominantColor(solid(Color.makeRGB(90, 90, 90)))) // no color at all
        assertEquals(8, accentPalette(orange).size)
        assertEquals(0xFF0000FF.toInt(), accentValues(0xFF0000).accentColor) // ABGR

        val api = FakeAccent()
        val accent = AccentFromWallpaper(Settings(db), api)
        accent.applyFrom(solid(Color.makeRGB(20, 80, 240)))
        assertEquals(0x11223344, api.current.accentColor, "nothing happens while it's off")
        accent.setEnabled(true)
        accent.applyFrom(solid(Color.makeRGB(20, 80, 240)))
        assertEquals("0", api.current.autoColorization)
        accent.applyFrom(solid(Color.makeRGB(240, 20, 20)))
        accent.setEnabled(false)
        assertEquals(AccentBackup(accentColor = 0x11223344, autoColorization = "1"), api.current) // the user's own color
    }
}
