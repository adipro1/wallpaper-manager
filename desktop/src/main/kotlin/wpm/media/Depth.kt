package wpm.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import wpm.APP_VERSION
import wpm.tr
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/** Depth Anything V2 Small (Apache-2.0), 8-bit, pinned to one revision and checked with SHA-256. */
private const val MODEL_REVISION = "4472b7362082ad9968fee890ca0f1e5aca36b93d"
private const val MODEL_FILE = "depth-anything-v2-small-int8.onnx"
private const val MODEL_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/$MODEL_REVISION/onnx/model_int8.onnx"
private const val MODEL_SHA256 = "01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001"
const val DEPTH_MODEL_MB = 27

/** The model works on sides that are multiples of 14; the short side is 518, the long side at most [MAX_SIDE]. */
private const val PATCH = 14
private const val SHORT_SIDE = 518
private const val MAX_SIDE = 924

private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/**
 * AI depth maps: how near each part of a picture is (white = near, black = far), for effects
 * that move a photo like a 3D scene. The model is downloaded on first use; maps are cached.
 */
class DepthMaps(private val modelDir: Path, private val cacheDir: Path) {
    private val log = wpm.logger("depth")
    val modelFile: Path get() = modelDir.resolve(MODEL_FILE)

    /** The model is on this PC, so [of] works without downloading anything. */
    val installed: Boolean get() = modelFile.isRegularFile()

    /** Downloads the model once (~27 MB); [progress] gets 0..1. Throws if the checksum doesn't match. */
    @Synchronized
    fun install(progress: (Double) -> Unit = {}) {
        if (installed) return
        Files.createDirectories(modelDir)
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
        val request = HttpRequest.newBuilder(URI(MODEL_URL)).header("User-Agent", "WallpaperManager/$APP_VERSION").build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException(tr("Hugging Face answered {0}", response.statusCode()))
        }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(DEPTH_MODEL_MB * 1_000_000L)
        val partial = modelDir.resolve("$MODEL_FILE.part")
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    progress((done.toDouble() / total).coerceAtMost(1.0))
                }
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != MODEL_SHA256) {
            Files.deleteIfExists(partial)
            throw IOException(tr("The depth model download is damaged (checksum); try again"))
        }
        Files.move(partial, modelFile, StandardCopyOption.REPLACE_EXISTING)
        log.info("Installed the depth model in $modelFile")
    }

    /** The cached depth map of [picture], if it was made already. */
    fun cached(picture: Path): Path? = cacheFile(picture).takeIf { it.exists() }

    /** The depth map of [picture] as a grey PNG (made once, then cached). Needs [installed]. */
    @Synchronized
    fun of(picture: Path): Path {
        val target = cacheFile(picture)
        if (target.exists()) return target
        check(installed) { tr("The depth model isn't downloaded yet") }
        val started = System.nanoTime()
        val (width, height, depth) = Imaging.load(picture).use { estimate(it) }
        Files.createDirectories(cacheDir)
        save(depthPixels(depth), width, height, target)
        log.info("Depth map of $picture in ${(System.nanoTime() - started) / 1_000_000} ms")
        return target
    }

    private fun cacheFile(picture: Path): Path {
        val key = Integer.toHexString("$picture|${picture.getLastModifiedTime().toMillis()}|$MODEL_FILE|2".hashCode())
        return cacheDir.resolve("depth-$key.png")
    }

    /** Runs the model; returns the size it worked at and its raw output (bigger = nearer). */
    private fun estimate(image: Image): Triple<Int, Int, FloatArray> {
        val (width, height) = inputSize(image.width, image.height)
        val rgba = Imaging.cover(image, width, height).use { scaled ->
            Bitmap.makeFromImage(scaled).use { bitmap ->
                bitmap.readPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), width * 4, 0, 0)
            } ?: error("Couldn't read the picture's pixels")
        }
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
            env.createSession(modelFile.toString(), options).use { session ->
                val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(normalizedInput(rgba, width, height)), shape).use { input ->
                    session.run(mapOf(session.inputNames.first() to input)).use { result ->
                        val output = result[0] as OnnxTensor
                        val values = FloatArray(width * height)
                        output.floatBuffer.get(values)
                        return Triple(width, height, values)
                    }
                }
            }
        }
    }

    /**
     * Writes the map with near things grown by a few pixels and then blurred: the stretch where the
     * background comes into view then falls on the background, and noisy leaves don't swirl.
     */
    private fun save(grey: ByteArray, width: Int, height: Int, target: Path) {
        val rgba = ByteArray(width * height * 4)
        for (i in grey.indices) {
            rgba[i * 4] = grey[i]; rgba[i * 4 + 1] = grey[i]; rgba[i * 4 + 2] = grey[i]; rgba[i * 4 + 3] = -1
        }
        Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), rgba, width * 4).use { raw ->
            Surface.makeRasterN32Premul(width, height).use { surface ->
                val grow = ImageFilter.makeDilate(3f, 3f, null, null)
                val paint = Paint().apply { imageFilter = ImageFilter.makeBlur(3f, 3f, FilterTileMode.CLAMP, grow, null) }
                surface.canvas.drawImage(raw, 0f, 0f, paint)
                surface.makeImageSnapshot().use { Imaging.savePng(it, target) }
            }
        }
    }

    companion object {
        /** The model's input size for a [width] x [height] picture: same shape, sides multiples of 14. */
        fun inputSize(width: Int, height: Int): Pair<Int, Int> {
            fun snap(side: Double) = (Math.round(side / PATCH).toInt() * PATCH).coerceIn(PATCH, MAX_SIDE)
            val scale = SHORT_SIDE.toDouble() / minOf(width, height)
            return snap(width * scale) to snap(height * scale)
        }

        /** RGBA bytes to the model's planar, normalized RGB input. */
        fun normalizedInput(rgba: ByteArray, width: Int, height: Int): FloatArray {
            val plane = width * height
            val out = FloatArray(plane * 3)
            for (i in 0 until plane) {
                for (c in 0..2) {
                    val value = (rgba[i * 4 + c].toInt() and 0xFF) / 255f
                    out[c * plane + i] = (value - MEAN[c]) / STD[c]
                }
            }
            return out
        }

        /** Raw model output to grey levels: the nearest 1% is white, the farthest 1% black. */
        fun depthPixels(depth: FloatArray): ByteArray {
            val sorted = depth.copyOf().apply { sort() }
            val low = sorted[(sorted.size * 0.01).toInt()]
            val high = sorted[((sorted.size - 1) * 0.99).toInt()]
            val range = (high - low).takeIf { it > 1e-6f } ?: 1f
            return ByteArray(depth.size) { i -> (((depth[i] - low) / range).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        }
    }
}
