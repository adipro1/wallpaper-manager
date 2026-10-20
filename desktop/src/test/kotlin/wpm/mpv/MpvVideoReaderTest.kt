package wpm.mpv

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.Samples
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Needs bin/libmpv-2.dll (tools/get_libmpv.py); skipped without it. */
class MpvVideoReaderTest {
    private val dir: Path = Files.createTempDirectory("wpm-mpv")
    private val reader = MpvVideoReader(dir.resolve("tmp"))

    @BeforeTest
    fun needsLibmpv() = assumeTrue(Mpv.available, "libmpv not installed")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun probesVideo() {
        val clip = Samples.video(dir.resolve("clip.mp4"), seconds = 3, width = 320, height = 180)
        val info = reader.probe(clip)
        assertEquals("video", info.mediaType)
        assertEquals(320 to 180, info.width to info.height)
        assertTrue(info.duration!! in 2.5..3.5, "duration ${info.duration}")
    }

    @Test
    fun grabsFrame() {
        val clip = Samples.video(dir.resolve("clip.mp4"), seconds = 3, width = 320, height = 180)
        reader.frame(clip, 1.5).use { frame ->
            assertEquals(320 to 180, frame.width to frame.height)
        }
    }

    @Test
    fun importsVideoIntoLibrary() {
        val clip = Samples.video(dir.resolve("clip.mp4"), seconds = 3, width = 320, height = 180)
        wpm.data.Database(dir.resolve("test.db")).use { db ->
            val library = wpm.media.MediaLibrary(db, dir.resolve("thumbs"), reader)
            val result = library.importFiles(listOf(clip))
            assertEquals(1, result.added.size, result.failed.toString())
            val item = db.listMedia().single()
            assertTrue(item.isVideo)
            assertEquals("0:03", item.durationText)
            assertEquals(wpm.media.THUMB_WIDTH to wpm.media.THUMB_HEIGHT, wpm.media.Imaging.size(library.thumbDir.resolve(item.thumbnail!!)))
        }
    }

    @Test
    fun rejectsBrokenFiles() {
        val broken = dir.resolve("broken.mp4").also { it.writeText("not a video") }
        assertFailsWith<IllegalArgumentException> { reader.probe(broken) }
        assertFailsWith<java.nio.file.NoSuchFileException> { reader.probe(dir.resolve("missing.mp4")) }
    }
}
