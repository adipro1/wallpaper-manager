package wpm.services

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EarthTest {
    private val dir: Path = Files.createTempDirectory("wpm-earth")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun picture(w: Int, h: Int, color: (x: Int, y: Int) -> Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, color(x, y))
    }

    private fun jpeg(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    @Test
    fun theSatellitesFillEachOthersGapsAndCloudsAreFound() {
        val sea = 0x102A6A
        val ice = 0xF0F0F4
        // Blue Marble: sea on the left, ice on the right.
        val base = picture(8, 4) { x, _ -> if (x < 4) sea else ice }
        // The first satellite saw a cloud at (0, 0) and nothing (black) on the second row; the second filled row 1.
        val first = picture(8, 4) { x, y -> when { y == 1 -> 0x000000; x == 0 && y == 0 -> 0xF2F2F2; else -> if (x < 4) sea else ice } }
        val second = picture(8, 4) { x, y -> if (y == 1 && x == 1) 0xEEEEEE else if (x < 4) sea else ice }
        val (today, map) = EarthImagery.compose(base, first, second)
        assertEquals(0xF2F2F2, today.getRGB(0, 0) and 0xFFFFFF, "the first satellite's picture")
        assertEquals(0xEEEEEE, today.getRGB(1, 1) and 0xFFFFFF, "the second fills the first's gap")
        assertEquals(4, map.width)
        assertEquals(2, map.height)
        val cloudyCorner = (map.getRGB(0, 0) shr 16) and 0xFF
        assertTrue(cloudyCorner > 100, "two of four pixels are cloud: $cloudyCorner")
        assertEquals(0, (map.getRGB(3, 0) shr 16) and 0xFF, "white ice in Blue Marble too is no cloud")
        assertEquals(255, (map.getRGB(1, 1) shr 8) and 0xFF, "the sea is water")
        assertEquals(0, (map.getRGB(3, 1) shr 8) and 0xFF, "ice isn't")
    }

    @Test
    fun picturesAreDownloadedOnceADay() {
        var now = Instant.parse("2025-06-02T08:00:00Z")
        val asked = mutableListOf<String>()
        val earth = EarthImagery(dir, download = { url ->
            asked += url
            jpeg(picture(16, 8) { x, _ -> if (x < 8) 0x102A6A else 0x305020 })
        }, clock = { now }, width = 16)
        assertFalse(earth.ready)
        assertTrue(earth.refresh())
        assertTrue(earth.ready)
        assertTrue(earth.version().startsWith("2025-06-01"), earth.version())
        assertTrue(asked.any { "TIME=2025-06-01" in it && "VIIRS_SNPP" in it }, "yesterday's picture")
        for (name in EarthImagery.TEXTURES) {
            val script = dir.resolve("$name.js").readText()
            assertTrue(script.startsWith("window.wpm&&window.wpm.texture&&window.wpm.texture(\"$name\",\"data:image/"), script.take(80))
        }
        val count = asked.size
        assertFalse(earth.refresh(), "nothing new the same day")
        assertEquals(count, asked.size)
        now = Instant.parse("2025-06-03T08:00:00Z")
        assertTrue(earth.refresh())
        assertEquals(count + 2, asked.size, "the next day only the satellites' pictures again (Blue Marble and the lights are kept)")
    }

    @Test
    fun aFailedDownloadKeepsThePictures() {
        var fail = false
        val earth = EarthImagery(dir, download = { if (fail) throw java.io.IOException("offline") else jpeg(picture(16, 8) { _, _ -> 0x102A6A }) },
            clock = { if (fail) Instant.parse("2025-06-05T00:00:00Z") else Instant.parse("2025-06-02T00:00:00Z") }, width = 16)
        assertTrue(earth.refresh())
        fail = true
        assertFalse(earth.refresh())
        assertTrue(earth.ready && dir.resolve("day.js").exists())
    }

    @Test
    fun spaceStation() {
        var now = Instant.parse("2025-06-02T08:00:00Z")
        val urls = mutableListOf<String>()
        val tracker = IssTracker(get = { url ->
            urls += url
            if ("positions" in url) {
                val times = url.substringAfter("timestamps=").substringBefore("&").split(",")
                "[" + times.joinToString(",") { """{"timestamp":$it,"latitude":10.5,"longitude":20.25}""" } + "]"
            } else """{"name":"iss","latitude":51.6,"longitude":-0.12,"timestamp":${now.epochSecond}}"""
        }, clock = { now })
        val first = tracker.update()
        assertEquals(10, first.size, "45 minutes of track and where it is now")
        assertEquals(now, first.last().time)
        assertEquals(51.6, first.last().latitude)
        now = now.plusSeconds(10)
        assertEquals(11, tracker.update().size)
        assertEquals(1, urls.count { "positions" in it }, "the track is fetched once")
        val script = IssTracker.script(listOf(IssPosition(Instant.ofEpochMilli(1000), 1.5, -2.25)))
        assertEquals("window.wpm&&window.wpm.setIss&&window.wpm.setIss([[1000,1.500,-2.250]]);", script)
    }

    /** The real pictures and the real station. Opt-in: WPM_NETWORK_TEST=1. */
    @Test
    fun realPicturesAndStation() {
        assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val earth = EarthImagery(dir, width = 1024)
        assertTrue(earth.refresh())
        assertTrue(Files.size(dir.resolve("day.js")) > 50_000)
        val track = IssTracker().update()
        assertTrue(track.size >= 2 && track.all { it.latitude in -52.0..52.0 }, "$track")
    }
}
