package wpm.media

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path

/**
 * Image helpers on Skia (bundled with Compose): it reads JPEG, PNG, WEBP, GIF and BMP,
 * so no extra imaging library is needed.
 */
object Imaging {
    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    /** Pixel size as displayed, i.e. after EXIF rotation. */
    fun size(path: Path): Pair<Int, Int> {
        val codec = Codec.makeFromData(Data.makeFromBytes(Files.readAllBytes(path)))
        codec.use {
            return if (it.encodedOrigin.swapsWidthHeight()) it.height to it.width else it.width to it.height
        }
    }

    /** Decodes an image (first frame for GIFs) with its EXIF rotation applied. */
    fun load(path: Path): Image = decode(Files.readAllBytes(path))

    fun decode(bytes: ByteArray): Image {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        codec.use {
            val raw = Image.makeFromBitmap(it.readPixels().apply { setImmutable() })
            return orient(raw, it.encodedOrigin)
        }
    }

    private fun orient(image: Image, origin: EncodedOrigin): Image {
        if (origin == EncodedOrigin.TOP_LEFT || origin == EncodedOrigin.UNUSED) return image
        val w = if (origin.swapsWidthHeight()) image.height else image.width
        val h = if (origin.swapsWidthHeight()) image.width else image.height
        Surface.makeRasterN32Premul(w, h).use { surface ->
            surface.canvas.concat(origin.toMatrix(image.width, image.height))
            surface.canvas.drawImage(image, 0f, 0f)
            image.close()
            return surface.makeImageSnapshot()
        }
    }

    /** Scale and centre-crop so the image fills [width] x [height] completely. */
    fun cover(image: Image, width: Int, height: Int): Image {
        val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val srcW = width / scale
        val srcH = height / scale
        val src = Rect.makeXYWH((image.width - srcW) / 2, (image.height - srcH) / 2, srcW, srcH)
        return draw(image, src, width, height)
    }

    /** Scale so the whole image fits inside [maxWidth] x [maxHeight], keeping its aspect ratio. */
    fun contain(image: Image, maxWidth: Int, maxHeight: Int): Image {
        val scale = minOf(maxWidth.toFloat() / image.width, maxHeight.toFloat() / image.height, 1f)
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        return draw(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), w, h)
    }

    private fun draw(image: Image, src: Rect, width: Int, height: Int): Image =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(image, src, Rect.makeWH(width.toFloat(), height.toFloat()), sampling, null, true)
            surface.makeImageSnapshot()
        }

    fun saveJpeg(image: Image, path: Path, quality: Int = 90) = save(image, path, EncodedImageFormat.JPEG, quality)

    fun savePng(image: Image, path: Path) = save(image, path, EncodedImageFormat.PNG, 100)

    private fun save(image: Image, path: Path, format: EncodedImageFormat, quality: Int) {
        val data = image.encodeToData(format, quality) ?: error("Could not encode the image")
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, data.bytes)
    }

    /** Detects JPEG / PNG / BMP from the file header (Windows' own wallpaper copy has no extension). */
    fun sniffExtension(path: Path): String {
        val head = Files.newInputStream(path).use { it.readNBytes(8) }
        return when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> ".jpg"
            head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() -> ".bmp"
            else -> ".png"
        }
    }
}

/** Shortens long text such as a folder path by replacing its middle with '…'. */
fun shortenMiddle(text: String, maxLen: Int): String {
    if (text.length <= maxLen) return text
    val keep = maxLen - 1
    return text.take(keep / 2) + "…" + text.takeLast(keep - keep / 2)
}
