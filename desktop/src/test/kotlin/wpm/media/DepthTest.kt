package wpm.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthTest {
    private val dir: Path = Files.createTempDirectory("wpm-depth")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun inputKeepsTheShapeInMultiplesOf14() {
        assertEquals(924 to 518, DepthMaps.inputSize(3840, 2160))
        assertEquals(518 to 924, DepthMaps.inputSize(1080, 1920))
        assertEquals(518 to 518, DepthMaps.inputSize(64, 64))
        // Very wide pictures are capped (the effect crops them anyway).
        assertEquals(924 to 518, DepthMaps.inputSize(5120, 1440))
        val (w, h) = DepthMaps.inputSize(1366, 768)
        assertTrue(w % 14 == 0 && h % 14 == 0)
    }

    @Test
    fun inputIsPlanarAndNormalized() {
        // Two pixels: white and black.
        val rgba = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, -1)
        val input = DepthMaps.normalizedInput(rgba, 2, 1)
        assertEquals(6, input.size)
        assertEquals((1 - 0.485f) / 0.229f, input[0], 1e-4f)  // R of the white pixel
        assertEquals(-0.485f / 0.229f, input[1], 1e-4f)       // R of the black pixel
        assertEquals((1 - 0.406f) / 0.225f, input[4], 1e-4f)  // B of the white pixel
    }

    @Test
    fun nearIsWhiteAndOutliersDontMatter() {
        val depth = FloatArray(1000) { it.toFloat() }.also { it[999] = 1e9f }
        val grey = DepthMaps.depthPixels(depth)
        assertEquals(0, grey[0].toInt() and 0xFF)
        assertEquals(255, grey[998].toInt() and 0xFF)
        assertTrue((grey[500].toInt() and 0xFF) in 120..135)
        // A flat picture gives a flat map instead of dividing by zero.
        assertTrue(DepthMaps.depthPixels(FloatArray(10) { 3f }).all { it.toInt() == 0 })
    }

    /** Downloads the real model and checks that a road's bottom is nearer than the sky. Opt-in: WPM_NETWORK_TEST=1. */
    @Test
    fun realModelSeesTheGroundNearerThanTheSky() {
        assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val depth = DepthMaps(Path.of("build/depth-model-test").toAbsolutePath(), dir.resolve("cache"))
        depth.install()
        assertTrue(depth.installed)
        val picture = road(dir.resolve("road.jpg"))
        val map = depth.of(picture)
        assertEquals(map, depth.cached(picture))
        val image = Imaging.load(map)
        val grey = Bitmap.makeFromImage(image).readPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), image.width * 4, 0, 0)!!
        fun rowsMean(from: Int, to: Int): Double {
            var sum = 0.0
            for (y in from until to) for (x in 0 until image.width) sum += grey[(y * image.width + x) * 4].toInt() and 0xFF
            return sum / ((to - from) * image.width)
        }
        val sky = rowsMean(0, image.height / 5)
        val ground = rowsMean(image.height * 4 / 5, image.height)
        assertTrue(ground > sky + 60, "ground $ground should be much nearer than the sky $sky")
    }

    /** A sky over a road with stripes running to the horizon. */
    private fun road(path: Path): Path {
        val (w, h) = 960 to 540
        Surface.makeRasterN32Premul(w, h).use { surface ->
            val canvas = surface.canvas
            canvas.drawRect(org.jetbrains.skia.Rect.makeWH(w.toFloat(), h * 0.45f), Paint().apply { color = Color.makeRGB(120, 170, 230) })
            canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, h * 0.45f, w.toFloat(), h * 0.55f), Paint().apply { color = Color.makeRGB(70, 120, 50) })
            val road = SkPath().moveTo(w * 0.47f, h * 0.45f).lineTo(w * 0.53f, h * 0.45f).lineTo(w * 0.95f, h.toFloat()).lineTo(w * 0.05f, h.toFloat()).closePath()
            canvas.drawPath(road, Paint().apply { color = Color.makeRGB(60, 60, 65) })
            for (i in 0 until 12) {
                val t0 = Math.pow(i / 12.0, 2.0).toFloat()
                val t1 = Math.pow((i + 0.5) / 12.0, 2.0).toFloat()
                val y0 = h * (0.45f + 0.55f * t0)
                val y1 = h * (0.45f + 0.55f * t1)
                canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(w * 0.5f - 2 - 8 * t0, y0, w * 0.5f + 2 + 8 * t1, y1), Paint().apply { color = Color.WHITE })
            }
            Files.write(path, surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes)
        }
        return path
    }
}
