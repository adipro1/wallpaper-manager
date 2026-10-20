package wpm.media

import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.data.Database
import wpm.mpv.NativeBin
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlTest {
    @Test
    fun parsesLinks() {
        assertEquals("https://www.youtube.com/watch?v=abc", parseVideoUrl("  https://www.youtube.com/watch?v=abc "))
        assertEquals("http://example.com/a.mp4", parseVideoUrl("<http://example.com/a.mp4>"))
        assertNull(parseVideoUrl("ftp://example.com/a.mp4"))
        assertNull(parseVideoUrl("not a link"))
        assertNull(parseVideoUrl("https://"))
    }

    /** Talks to the internet: opt-in with WPM_NETWORK_TEST=1 (needs bin/yt-dlp.exe). */
    @Test
    fun looksUpAndStreamsRealVideo() {
        assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val ytDlp = YtDlp(NativeBin.dir.resolve("yt-dlp.exe"))
        assumeTrue(ytDlp.available, "yt-dlp not installed")
        val dir = Files.createTempDirectory("wpm-url")
        try {
            Database(dir.resolve("t.db")).use { db ->
                val library = MediaLibrary(db, dir.resolve("thumbs"))
                val importer = UrlImporter(db, library, ytDlp, dir.resolve("downloads"))
                // "Big Buck Bunny" trailer, a long-lived Creative Commons video on YouTube.
                val video = importer.lookUp("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
                println("found: $video")
                assertTrue(video.title.isNotBlank())
                assertNotNull(video.duration)
                val id = importer.addStream(video)!!
                val item = db.getMedia(id)!!
                assertTrue(item.isRemote)
                assertTrue(library.thumbDir.resolve(item.thumbnail!!).exists())
                assertNull(importer.addStream(video)) // only once

                // mpv opens the page through its yt-dlp hook, with the same options as the live wallpaper.
                wpm.mpv.MpvPlayer(mapOf(
                    "vo" to "null", "ao" to "null", "idle" to "yes", "config" to "no", "terminal" to "no",
                    "ytdl" to "yes", "script-opts" to "ytdl_hook-ytdl_path=${ytDlp.exe.toAbsolutePath()}",
                    "ytdl-format" to "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/b",
                )).use { mpv ->
                    mpv.command("loadfile", video.url)
                    var loaded = false
                    val deadline = System.currentTimeMillis() + 90_000
                    while (!loaded && System.currentTimeMillis() < deadline) {
                        val event = mpv.waitEvent(1.0)
                        if (event.id == wpm.mpv.Mpv.EVENT_END_FILE) error("mpv could not open the stream: ${mpv.errorString(event.endError)}")
                        loaded = event.id == wpm.mpv.Mpv.EVENT_FILE_LOADED
                    }
                    assertTrue(loaded, "stream did not load in time")
                    println("streaming at ${mpv["width"]}x${mpv["height"]}, codec ${mpv["video-codec"]}")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
