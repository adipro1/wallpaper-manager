package wpm.media

import wpm.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import wpm.data.Database
import wpm.data.MediaItem
import wpm.logger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

private val log = logger("web-import")
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Optional wpm.json in a web wallpaper folder. */
@Serializable
data class WebManifest(
    val title: String? = null,
    val author: String? = null,
    val type: String = "web", // "web" or "shader"
    val entry: String? = null,
    val audio: Boolean = false,
    val interactive: Boolean = true,
    val preview: String? = null,
)

/** The parts of Lively's LivelyInfo.json we understand, so Lively wallpapers import as they are. */
@Serializable
private data class LivelyInfo(
    @SerialName("Title") val title: String? = null,
    @SerialName("FileName") val fileName: String? = null,
    @SerialName("Thumbnail") val thumbnail: String? = null,
    @SerialName("Preview") val preview: String? = null,
    @SerialName("Type") val type: Int? = null,
)

private val PREVIEW_NAMES = listOf("preview", "thumbnail", "thumb", "screenshot")

/**
 * Web wallpapers: a page (with its scripts, images and so on) shown by the web engine.
 * Imported from a folder, a single .html file, a .zip, or a website address.
 */
class WebWallpapers(private val db: Database, private val library: MediaLibrary, private val extractDir: Path) {

    /** Imports a folder, .html/.htm file or .zip. Returns the new id, or null if it's already in the library. */
    fun import(source: Path): Long? = when {
        source.isDirectory() -> importFolder(source)
        source.extension.lowercase() in setOf("html", "htm") -> addPage(source.parent, source.name, source.nameWithoutExtension, null, source)
        source.extension.lowercase() == "zip" -> importFolder(extract(source))
        else -> throw IllegalArgumentException(tr("Choose a folder, an .html file or a .zip"))
    }

    private fun importFolder(folder: Path): Long? {
        val manifest = folder.resolve("wpm.json").takeIf { it.isRegularFile() }
            ?.let { runCatching { json.decodeFromString(WebManifest.serializer(), it.readText()) }.getOrNull() }
        val lively = folder.resolve("LivelyInfo.json").takeIf { it.isRegularFile() }
            ?.let { runCatching { json.decodeFromString(LivelyInfo.serializer(), it.readText()) }.getOrNull() }
        val engine = WallpaperEngine.read(folder)?.takeIf { it.type == "web" }
        val entry = listOfNotNull(manifest?.entry, lively?.fileName, engine?.file, "index.html")
            .firstOrNull { folder.resolve(it).isRegularFile() }
            ?: folder.listDirectoryEntries("*.htm*").singleOrNull()?.name
            ?: throw IllegalArgumentException(tr("No index.html found in {0}", folder.name))
        val preview = (listOfNotNull(manifest?.preview, lively?.thumbnail, lively?.preview).map(folder::resolve) + listOfNotNull(engine?.preview))
            .firstOrNull { it.isRegularFile() }
            ?: folder.listDirectoryEntries().firstOrNull { f ->
                f.nameWithoutExtension.lowercase() in PREVIEW_NAMES && mediaTypeFor(f.toString()) == "image"
            }
        val title = manifest?.title ?: lively?.title ?: engine?.title ?: folder.name
        if (manifest?.type == "shader") throw IllegalArgumentException(tr("Import shaders with “Add shader”"))
        return addPage(folder, entry, title, manifest, preview, isFolder = true)
    }

    private fun addPage(folder: Path, entry: String, title: String, manifest: WebManifest?, preview: Path?, isFolder: Boolean = false): Long? {
        val path = if (isFolder) folder else folder.resolve(entry)
        if (db.hasMedia(path.toString())) return null
        val thumb = thumbnail(preview, title, "WEB")
        return db.addItem(
            title, "web", path = path.toString(), entry = if (isFolder) entry else null, thumbnail = thumb,
            interactive = manifest?.interactive ?: true, audio = manifest?.audio ?: false,
        )
    }

    /** Adds a website as a wallpaper (it is loaded live every time). */
    fun addWebsite(url: String): Long? {
        if (db.hasUrl(url)) return null
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
        return db.addItem(host, "web", sourceUrl = url, thumbnail = thumbnail(null, host, "WEBSITE"), interactive = true)
    }

    /** Writes a thumbnail from a preview image, or a simple title card (replaced by a real snapshot once shown). */
    fun thumbnail(preview: Path?, title: String, label: String): String? = runCatching {
        val image = preview?.let { runCatching { Imaging.load(it) }.getOrNull() }
        val name = (if (image == null) CARD_PREFIX else "") + library.thumbnailNameFor("$label|$title|${System.nanoTime()}")
        (image ?: titleCard(title, label)).use { img ->
            Imaging.cover(img, THUMB_WIDTH, THUMB_HEIGHT).use { t -> Imaging.saveJpeg(t, library.thumbDir.resolve(name), 85) }
        }
        name
    }.onFailure { log.warning("Couldn't make a thumbnail for $title: $it") }.getOrNull()

    /** Saves a snapshot of the running page as the item's thumbnail; returns its file name. */
    fun saveSnapshot(image: java.awt.image.BufferedImage, mediaId: Long): String {
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val name = library.thumbnailNameFor("snapshot|$mediaId|${System.nanoTime()}")
        Imaging.decode(bytes).use { img ->
            Imaging.cover(img, THUMB_WIDTH, THUMB_HEIGHT).use { Imaging.saveJpeg(it, library.thumbDir.resolve(name), 85) }
        }
        return name
    }

    /** Unpacks a .zip into the app's web folder (guarding against paths that escape it). */
    private fun extract(zip: Path): Path {
        val target = extractDir.resolve(zip.nameWithoutExtension.replace(Regex("[^\\w .-]"), "_"))
        if (target.exists()) target.toFile().deleteRecursively()
        Files.createDirectories(target)
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                val out = target.resolve(entry.name).normalize()
                require(out.startsWith(target)) { tr("The zip contains an unsafe path: {0}", entry.name) }
                if (entry.isDirectory) Files.createDirectories(out) else {
                    Files.createDirectories(out.parent)
                    Files.copy(input, out)
                }
            }
        }
        // Many zips wrap everything in one folder.
        val children = target.listDirectoryEntries()
        return children.singleOrNull()?.takeIf { it.isDirectory() && !target.resolve("index.html").exists() } ?: target
    }

    companion object {
        /**
         * Caps a page's animations at [fps]: requestAnimationFrame callbacks only run on frames at
         * least 1/fps apart (all callbacks of such a frame run, so pages with several loops stay in step).
         */
        fun frameRateLimit(fps: Int): String = """
            (function(fps){if(window.__wpmFps)return;window.__wpmFps=fps;var raf=window.requestAnimationFrame.bind(window),
            gap=1000/fps,next=0,allowed=-1;window.requestAnimationFrame=function(cb){return raf(function run(t){
            if(t===allowed||t>=next){if(t!==allowed){allowed=t;next=t+gap-1;}cb(t);}else{raf(run);}});};})($fps);
        """.trimIndent().replace("\n", "")

        /** File name prefix of generated title-card thumbnails. */
        const val CARD_PREFIX = "card-"

        /** A .html/.zip file, or a folder with a page at its top level (rather than a folder of pictures). */
        fun looksLikeWebWallpaper(path: Path): Boolean = when {
            path.isDirectory() -> listOf("index.html", "wpm.json", "LivelyInfo.json").any { path.resolve(it).isRegularFile() } ||
                WallpaperEngine.read(path)?.type == "web"
            else -> path.extension.lowercase() in setOf("html", "htm", "zip")
        }

        /** The address the web engine opens for an item. */
        fun pageUrl(item: MediaItem): String {
            val local = item.localPath ?: return item.sourceUrl!!
            val page = item.entry?.let(local::resolve) ?: local
            return page.toUri().toString()
        }

        /** A gradient card with the title, for wallpapers without a preview image. */
        fun titleCard(title: String, label: String): org.jetbrains.skia.Image =
            Surface.makeRasterN32Premul(THUMB_WIDTH * 2, THUMB_HEIGHT * 2).use { surface ->
                val canvas = surface.canvas
                val w = surface.width.toFloat()
                val h = surface.height.toFloat()
                Paint().use { paint ->
                    paint.shader = Shader.makeLinearGradient(Point(0f, 0f), Point(w, h),
                        intArrayOf(Color.makeRGB(32, 40, 92), Color.makeRGB(112, 42, 140), Color.makeRGB(20, 120, 150)))
                    canvas.drawRect(org.jetbrains.skia.Rect.makeWH(w, h), paint)
                }
                val typeface = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD) ?: FontMgr.default.legacyMakeTypeface("", FontStyle.BOLD)
                Paint().use { paint ->
                    paint.color = Color.makeARGB(170, 255, 255, 255)
                    Font(typeface, 26f).use { canvas.drawString(label, 40f, 70f, it, paint) }
                    paint.color = Color.WHITE
                    val text = if (title.length > 28) title.take(27) + "…" else title
                    Font(typeface, 54f).use { canvas.drawString(text, 40f, h - 60f, it, paint) }
                }
                surface.makeImageSnapshot()
            }
    }
}
