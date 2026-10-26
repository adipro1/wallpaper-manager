package wpm.services

import wpm.APP_VERSION
import wpm.logger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.max
import kotlin.math.min

private val log = logger("earth")

/**
 * Pictures of the whole Earth for the Live Earth scene, from NASA's GIBS (free, no account):
 * yesterday's real satellite picture (clouds and all; the two VIIRS satellites fill each other's
 * gaps), Blue Marble where neither saw the ground, and the city lights at night. The page can't
 * load pictures from disk into WebGL, so each one is written as a small script that hands it over
 * as a data: address ("<name>.js" calling `wpm.texture(name, url)`).
 */
class EarthImagery(
    private val folder: Path,
    private val download: (String) -> ByteArray = ::httpGet,
    private val clock: () -> Instant = Instant::now,
    private val width: Int = 4096,
) {
    private val height = width / 2

    /** The day the pictures show: yesterday (UTC), whose pictures are complete. */
    fun day(): LocalDate = clock().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)

    /** A number that changes whenever the pictures do (the page reloads them then). */
    fun version(): String = folder.resolve("version.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""

    /** True when the pictures are there (maybe from an earlier day). */
    val ready: Boolean get() = TEXTURES.all { folder.resolve("$it.js").exists() }

    /**
     * Gets the day's pictures unless they are there already (blocking; call it off the UI thread).
     * Returns true when new pictures were written. A failure keeps the old ones.
     */
    @Synchronized
    fun refresh(): Boolean {
        val day = day()
        if (ready && version().startsWith("$day")) return false
        Files.createDirectories(folder)
        return try {
            val base = cached("base.jpg") { layer("BlueMarble_NextGeneration", null) }
            val lights = cached("lights.jpg") { layer("VIIRS_CityLights_2012", null) }
            val first = decode(layer("VIIRS_SNPP_CorrectedReflectance_TrueColor", day))
            val second = decode(layer("VIIRS_NOAA20_CorrectedReflectance_TrueColor", day))
            val (today, clouds) = compose(decode(base), first, second)
            writeTexture("day", "image/jpeg", jpeg(today))
            writeTexture("clouds", "image/png", png(clouds))
            writeTexture("lights", "image/jpeg", lights)
            folder.resolve("version.txt").writeText("$day-${clock().toEpochMilli()}")
            log.info("Pictures of the Earth for $day are ready")
            true
        } catch (e: Exception) {
            log.warning("Couldn't get today's pictures of the Earth: $e")
            false
        }
    }

    private fun cached(name: String, get: () -> ByteArray): ByteArray {
        val file = folder.resolve(name)
        // The marker keeps a picture from an older app version (a different size) from being used.
        val marker = folder.resolve("$name.version")
        if (file.exists() && marker.exists() && marker.readText() == "$APP_VERSION/$width") return Files.readAllBytes(file)
        val bytes = get()
        Files.write(file, bytes)
        marker.writeText("$APP_VERSION/$width")
        return bytes
    }

    private fun layer(name: String, day: LocalDate?): ByteArray = download(
        "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
            "&LAYERS=$name&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=$width&HEIGHT=$height&FORMAT=image/jpeg" +
            (day?.let { "&TIME=$it" } ?: "")
    )

    private fun writeTexture(name: String, type: String, bytes: ByteArray) {
        val url = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
        val temp = folder.resolve("$name.js.tmp")
        temp.writeText("window.wpm&&window.wpm.texture&&window.wpm.texture(\"$name\",\"$url\");\n")
        Files.move(temp, folder.resolve("$name.js"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** The pictures the page loads ("<name>.js"). */
        val TEXTURES = listOf("day", "lights", "clouds")

        private fun httpGet(url: String): ByteArray {
            val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "WallpaperManager/$APP_VERSION")
                .timeout(Duration.ofSeconds(90)).build()
            val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) throw IOException("NASA GIBS answered ${response.statusCode()}")
            if (response.headers().firstValue("content-type").orElse("").startsWith("text")) throw IOException("NASA GIBS sent no picture")
            return response.body()
        }

        private fun decode(bytes: ByteArray): BufferedImage =
            ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Not a picture")

        private fun jpeg(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { out ->
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val params = writer.defaultWriteParam.apply {
                compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.88f
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, javax.imageio.IIOImage(image, null, null), params)
            }
            writer.dispose()
        }.toByteArray()

        private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        private fun brightness(rgb: Int): Double = (((rgb shr 16) and 0xFF) * 0.3 + ((rgb shr 8) and 0xFF) * 0.59 + (rgb and 0xFF) * 0.11) / 255

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun channel(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255) shl shift
            return channel(16) or channel(8) or channel(0)
        }

        /** A box blur of [values] ([radius] pixels, twice, so it is smooth), across and down. */
        private fun blur(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            var out = values
            repeat(2) {
                val across = FloatArray(w * h)
                for (y in 0 until h) {
                    var sum = 0f
                    for (x in -radius..radius) sum += out[y * w + x.coerceIn(0, w - 1)]
                    for (x in 0 until w) {
                        across[y * w + x] = sum / (2 * radius + 1)
                        sum += out[y * w + (x + radius + 1).coerceAtMost(w - 1)] - out[y * w + (x - radius).coerceAtLeast(0)]
                    }
                }
                val down = FloatArray(w * h)
                for (x in 0 until w) {
                    var sum = 0f
                    for (y in -radius..radius) sum += across[y.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) {
                        down[y * w + x] = sum / (2 * radius + 1)
                        sum += across[(y + radius + 1).coerceAtMost(h - 1) * w + x] - across[(y - radius).coerceAtLeast(0) * w + x]
                    }
                }
                out = down
            }
            return out
        }

        /** No data there: the satellites left it black (or nearly: too little sun for a picture). */
        private fun missing(rgb: Int): Boolean = ((rgb shr 16) and 0xFF) < 20 && ((rgb shr 8) and 0xFF) < 20 && (rgb and 0xFF) < 28

        /**
         * The day's picture (the first satellite, the second where the first saw nothing, else
         * [base]) and a map at half the size: red = clouds (white where [base], the cloud-free
         * Earth, isn't: so not snow, ice or deserts), green = water (for the sun's glint).
         */
        fun compose(base: BufferedImage, first: BufferedImage, second: BufferedImage): Pair<BufferedImage, BufferedImage> {
            val w = base.width
            val h = base.height
            fun scaled(image: BufferedImage): BufferedImage = if (image.width == w && image.height == h) image else
                BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply {
                    setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(image, 0, 0, w, h, null)
                    dispose()
                } }
            val a = scaled(first).getRGB(0, 0, w, h, null, 0, w)
            val b = scaled(second).getRGB(0, 0, w, h, null, 0, w)
            val ground = base.getRGB(0, 0, w, h, null, 0, w)
            val day = IntArray(w * h)
            val cloud = FloatArray(w * h)
            val seenMask = FloatArray(w * h)
            for (i in day.indices) {
                val seen = when {
                    !missing(a[i]) -> a[i]
                    !missing(b[i]) -> b[i]
                    else -> -1
                }
                day[i] = if (seen == -1) ground[i] else seen
                if (seen != -1) seenMask[i] = 1f
                if (seen != -1) {
                    val r = (seen shr 16) and 0xFF
                    val g = (seen shr 8) and 0xFF
                    val bl = seen and 0xFF
                    val grey = 1.0 - (max(r, max(g, bl)) - min(r, min(g, bl))) / 255.0 // clouds are hardly colored
                    cloud[i] = ((brightness(seen) - brightness(ground[i]) - 0.08) * 2.2 * grey).coerceIn(0.0, 1.0).toFloat()
                }
            }
            // Where the satellites stop, their picture fades into Blue Marble instead of ending at a hard line.
            val soft = blur(seenMask, w, h, max(2, w / 160))
            for (i in day.indices) {
                val t = min(soft[i], seenMask[i] * 2f).coerceIn(0f, 1f)
                if (t < 1f) day[i] = mix(ground[i], day[i], t)
            }
            val today = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, day, 0, w) }
            val mw = w / 2
            val mh = h / 2
            val map = IntArray(mw * mh)
            for (y in 0 until mh) for (x in 0 until mw) {
                var c = 0f
                var water = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (y * 2 + dy) * w + x * 2 + dx
                    c += cloud[i]
                    val g = ground[i]
                    // Blue Marble's sea is dark blue: blue clearly above red and green.
                    if ((g and 0xFF) > ((g shr 16) and 0xFF) + 12 && (g and 0xFF) > ((g shr 8) and 0xFF) && brightness(g) < 0.45) water++
                }
                map[y * mw + x] = ((c / 4 * 255).toInt() shl 16) or ((water * 255 / 4) shl 8)
            }
            val clouds = BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, mw, mh, map, 0, mw) }
            return today to clouds
        }
    }
}
