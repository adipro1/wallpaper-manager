package wpm

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path

/** Generates small sample images for tests. */
object Samples {
    fun image(path: Path, width: Int = 64, height: Int = 36, color: Int = Color.makeRGB(200, 40, 40)): Path {
        val format = when (path.fileName.toString().substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> EncodedImageFormat.JPEG
            "webp" -> EncodedImageFormat.WEBP
            else -> EncodedImageFormat.PNG
        }
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(color)
            val data = surface.makeImageSnapshot().encodeToData(format, 90)!!
            Files.createDirectories(path.parent)
            Files.write(path, data.bytes)
        }
        return path
    }

    /** An H.264 test pattern video, encoded by libmpv itself. */
    fun video(path: Path, seconds: Int = 3, width: Int = 320, height: Int = 180): Path {
        Files.createDirectories(path.parent)
        wpm.mpv.MpvPlayer(
            mapOf(
                "o" to path.toAbsolutePath().toString(), "ovc" to "libx264", "of" to "mp4",
                "ao" to "null", "aid" to "no", "terminal" to "no", "config" to "no", "idle" to "yes",
            ),
        ).use { mpv ->
            mpv.command("loadfile", "av://lavfi:testsrc=duration=$seconds:size=${width}x$height:rate=25")
            while (true) {
                val event = mpv.waitEvent(10.0)
                if (event.id == wpm.mpv.Mpv.EVENT_END_FILE || event.id == wpm.mpv.Mpv.EVENT_SHUTDOWN || event.id == 0) break
            }
        }
        check(Files.size(path) > 0) { "mpv did not write $path" }
        return path
    }

    /** A JPEG with EXIF orientation 6 (displayed rotated by 90 degrees). */
    fun rotatedJpeg(path: Path, width: Int = 64, height: Int = 36): Path {
        image(path, width, height)
        val jpeg = Files.readAllBytes(path)
        // APP1 Exif segment: big-endian TIFF with one IFD entry, Orientation (0x0112) = 6.
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0,
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        Files.write(path, jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size))
        return path
    }
}
