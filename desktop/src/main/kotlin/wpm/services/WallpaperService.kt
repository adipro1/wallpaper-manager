package wpm.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import wpm.AppPaths
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.logger
import wpm.media.Imaging
import wpm.win32.DesktopWallpaperApi
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension

private val log = logger("wallpaper")

// Formats Windows reliably accepts as a wallpaper; anything else is converted to PNG.
private val NATIVE_WALLPAPER_FORMATS = setOf("jpg", "jpeg", "png", "bmp")
val IMAGE_STYLES = listOf("fill", "fit", "stretch", "center", "tile")
val VIDEO_STYLES = listOf("fill", "fit")

/** What the desktop currently shows, as far as this app knows. Same JSON as version 1.x. */
@Serializable
data class DesktopState(
    val kind: String? = null, // "image", "video" or null (not set by this app)
    @SerialName("media_id") val mediaId: Long? = null,
    val path: String? = null,
    val style: String = "fill",
)

/** The picture under any live wallpaper, repainted when the video stops. */
@Serializable
data class StaticWallpaper(val file: String? = null, val style: String = "fill", @SerialName("media_id") val mediaId: Long? = null)

@Serializable
data class OriginalWallpaper(val path: String? = null, val style: String = "fill")

/**
 * Applies wallpapers and remembers what is currently set. Converts files Windows
 * can't use directly, backs up the user's original wallpaper before the first change,
 * and saves the current choice so it survives a restart.
 *
 * Calls into Windows block briefly: call from a background dispatcher.
 */
open class WallpaperService(
    protected val db: Database,
    protected val paths: AppPaths,
    protected val windows: DesktopWallpaperApi,
) {
    protected val settings = Settings(db)

    // -- state ------------------------------------------------------------------

    val state: DesktopState get() = settings.obj("desktop_wallpaper", DesktopState.serializer()) ?: DesktopState()

    protected fun saveState(state: DesktopState) = settings.set("desktop_wallpaper", DesktopState.serializer(), state)

    fun currentItem(): MediaItem? = state.mediaId?.let(db::getMedia)

    /** The picture Windows shows right now, even if this app didn't set it. */
    fun currentPicture(): java.nio.file.Path? =
        currentItem()?.path?.let(::Path)?.takeIf { it.exists() && !currentItem()!!.isVideo }
            ?: windows.currentWallpaper()?.let(::Path)?.takeIf { it.exists() }
            ?: windows.transcodedWallpaper()

    // -- static images ------------------------------------------------------------

    /** Makes an image the desktop wallpaper (stops a live wallpaper). */
    open fun setImage(item: MediaItem, style: String? = null) {
        require(!item.isVideo) { "Use setVideo for videos" }
        if (!Path(item.path).exists()) throw FileNotFoundException("File not found: ${item.path}")
        val chosen = style ?: settings.string("image_style", "fill")
        backupOriginal()
        apply(prepareImage(item), chosen, item.id)
        onStaticApplied() // after applying the image, so the old picture never flashes up
        settings.set("image_style", chosen)
        saveState(DesktopState("image", item.id, item.path, chosen))
        log.info("Desktop wallpaper set to ${item.path} ($chosen)")
    }

    /** Hook for the live wallpaper engine: a static picture replaced it. */
    protected open fun onStaticApplied() {}

    /** Changes how the current image is positioned (fill, fit, ...). */
    fun setImageStyle(style: String) {
        val item = currentItem()
        if (state.kind == "image" && item != null) setImage(item, style) else settings.set("image_style", style)
    }

    /** A file Windows can use: the original for JPG/PNG/BMP, otherwise a PNG copy (first frame for GIFs). */
    private fun prepareImage(item: MediaItem): String {
        if (Path(item.path).extension.lowercase() in NATIVE_WALLPAPER_FORMATS) return item.path
        // One file per item, so a converted copy is never overwritten while Windows still reads it.
        val out = paths.cacheDir.resolve("desktop-${item.id}.png")
        Imaging.load(Path(item.path)).use { Imaging.savePng(it, out) }
        return out.toString()
    }

    protected fun apply(file: String, style: String, mediaId: Long? = null) {
        windows.setStyle(style)
        windows.setWallpaper(file)
        settings.set("static_wallpaper", StaticWallpaper.serializer(), StaticWallpaper(file, style, mediaId))
    }

    /** Re-applies the static picture so Explorer repaints the area a video covered. */
    protected fun repaintStatic() {
        val static = settings.obj("static_wallpaper", StaticWallpaper.serializer())
        val original = settings.obj("original_wallpaper", OriginalWallpaper.serializer())
        val file = listOfNotNull(static?.file, original?.path).firstOrNull { Path(it).exists() } ?: return
        runCatching { windows.setWallpaper(file, persist = false) }.onFailure { log.warning("Could not repaint the desktop: $it") }
    }

    // -- library shortcuts ---------------------------------------------------------

    /** Sets any library item as the desktop wallpaper. */
    open fun set(item: MediaItem) {
        if (item.isVideo) throw UnsupportedOperationException("Video wallpapers need the video engine")
        setImage(item)
    }

    /** Shows the library item after the current one (newest first, wrapping round). */
    fun nextFromLibrary(): MediaItem? {
        val items = db.listMedia(sort = "newest").filter(::playable)
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.id == state.mediaId }
        val item = items[(index + 1) % items.size]
        set(item)
        return item
    }

    /** Shows a random library item other than the current one. */
    fun randomFromLibrary(): MediaItem? {
        val current = state.mediaId
        val item = db.listMedia().filter { it.id != current && playable(it) && Path(it.path).exists() }.randomOrNull() ?: return null
        set(item)
        return item
    }

    protected open fun playable(item: MediaItem): Boolean = !item.isVideo

    // -- original wallpaper ----------------------------------------------------------

    /** Before the first change, keeps a copy of the user's wallpaper so it can be restored later. */
    fun backupOriginal() {
        if (db.getSetting("original_wallpaper") != null) return
        val current = windows.currentWallpaper()?.let(::Path)?.takeIf { it.exists() }
            // The file may have been moved or deleted since; Windows' own copy is what is on screen.
            ?: windows.transcodedWallpaper()
        var backup: String? = null
        if (current != null) {
            try {
                val dir = paths.dataDir.resolve("backup")
                Files.createDirectories(dir)
                val target = dir.resolve("original_wallpaper${Imaging.sniffExtension(current)}")
                if (!(target.exists() && Files.isSameFile(current, target))) {
                    Files.copy(current, target, StandardCopyOption.REPLACE_EXISTING)
                }
                backup = target.toString()
            } catch (e: Exception) {
                log.warning("Could not back up the current wallpaper $current: $e")
            }
        }
        settings.set("original_wallpaper", OriginalWallpaper.serializer(), OriginalWallpaper(backup, windows.style()))
        log.info("Backed up original wallpaper to $backup")
    }

    fun hasOriginal(): Boolean = db.getSetting("original_wallpaper") != null

    open fun restoreOriginal() {
        val original = settings.obj("original_wallpaper", OriginalWallpaper.serializer()) ?: return
        val path = original.path
        if (path != null && Path(path).exists()) apply(path, original.style) else windows.setWallpaper("") // solid colour
        onStaticApplied()
        saveState(DesktopState())
        log.info("Restored the original wallpaper")
    }
}
