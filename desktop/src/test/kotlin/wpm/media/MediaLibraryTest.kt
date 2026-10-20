package wpm.media

import wpm.Samples
import wpm.data.Database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaLibraryTest {
    private val dir: Path = Files.createTempDirectory("wpm-media")
    private val db = Database(dir.resolve("test.db"))
    private val library = MediaLibrary(db, dir.resolve("thumbs"))

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun detectsTypes() {
        assertEquals("image", mediaTypeFor("a.JPG"))
        assertEquals("image", mediaTypeFor("a.webp"))
        assertEquals("video", mediaTypeFor("C:\\x\\clip.mp4"))
        assertNull(mediaTypeFor("notes.txt"))
    }

    @Test
    fun importsImagesWithMetadataAndThumbnails() {
        val png = Samples.image(dir.resolve("in/a.png"), 64, 36)
        val webp = Samples.image(dir.resolve("in/sub/b.webp"), 30, 20)
        val rotated = Samples.rotatedJpeg(dir.resolve("in/photo.jpg"), 64, 36)
        dir.resolve("in/readme.txt").writeText("not media")

        val found = findMediaFiles(dir.resolve("in"))
        assertEquals(setOf(png, webp, rotated), found.toSet())

        val result = library.importFiles(found)
        assertEquals(3, result.added.size)
        assertTrue(result.failed.isEmpty())

        val items = db.listMedia(sort = "name").associateBy { it.filename }
        assertEquals(64 to 36, items.getValue("a.png").let { it.width to it.height })
        assertEquals(30 to 20, items.getValue("b.webp").let { it.width to it.height })
        assertEquals(36 to 64, items.getValue("photo.jpg").let { it.width to it.height }) // EXIF rotation applied
        items.values.forEach { item ->
            val thumb = library.thumbDir.resolve(item.thumbnail!!)
            assertTrue(thumb.exists())
            assertEquals(THUMB_WIDTH to THUMB_HEIGHT, Imaging.size(thumb))
        }

        // Importing again skips what is already in the library.
        val again = library.importFiles(found)
        assertEquals(0, again.added.size)
        assertEquals(3, again.skipped)
    }

    @Test
    fun reportsBrokenFiles() {
        val broken = dir.resolve("broken.png").also { it.writeText("this is not a png") }
        val result = library.importFiles(listOf(broken, dir.resolve("missing.jpg")))
        assertEquals(2, result.failed.size)
        assertEquals("file not found", result.failed[1].second)
        assertEquals(0, db.countMedia())
    }

    @Test
    fun videosNeedTheVideoEngine() {
        val clip = dir.resolve("clip.mp4").also { it.writeText("fake") }
        val result = library.importFiles(listOf(clip))
        assertEquals(1, result.failed.size)
    }

    @Test
    fun recreatesDeletedThumbnail() {
        val png = Samples.image(dir.resolve("a.png"))
        library.importFiles(listOf(png))
        val item = db.listMedia().single()
        Files.delete(library.thumbDir.resolve(item.thumbnail!!))
        assertNotNull(library.ensureThumbnail(item))
        Files.delete(png)
        assertNull(library.ensureThumbnail(db.getMedia(item.id)!!.copy(thumbnail = null)))
    }
}
