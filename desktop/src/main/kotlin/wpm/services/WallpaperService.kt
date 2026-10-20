package wpm.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import wpm.AppPaths
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.engine.LiveKind
import wpm.engine.LivePlayer
import wpm.engine.LiveSource
import wpm.logger
import wpm.media.Imaging
import wpm.win32.DesktopWallpaperApi
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension

private val log = logger("wallpaper")

// Formats Windows reliably accepts as a wallpaper; anything else is converted to PNG.
private val NATIVE_WALLPAPER_FORMATS = setOf("jpg", "jpeg", "png", "bmp")
val IMAGE_STYLES = listOf("fill", "fit", "stretch", "center", "tile")
private val LIVE_KINDS = setOf("video", "web", "shader")
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

/** Why a live wallpaper was paused automatically, most important first. */
enum class PauseReason(val text: String) {
    Locked("the PC is locked"),
    ScreenOff("the screen is off"),
    Fullscreen("a fullscreen app is running"),
    Covered("windows cover the desktop"),
    Battery("running on battery"),
}

/**
 * Applies wallpapers and remembers what is currently set. Converts files Windows can't
 * use directly, backs up the user's original wallpaper before the first change, switches
 * between pictures and live videos, and saves the current choice so it survives a restart.
 *
 * Calls into Windows and mpv block briefly: call from a background dispatcher.
 */
class WallpaperService(
    private val db: Database,
    private val paths: AppPaths,
    private val windows: DesktopWallpaperApi,
    private val live: LivePlayer,
) {
    private val settings = Settings(db)

    /** Automatic pausing: the reason we paused, if we did. */
    @Volatile var autoPauseReason: PauseReason? = null
        private set
    /** A reason the user overrode by pressing Resume (we don't fight the user). */
    private var overriddenReason: PauseReason? = null

    // -- state ------------------------------------------------------------------

    val state: DesktopState get() = settings.obj("desktop_wallpaper", DesktopState.serializer()) ?: DesktopState()

    private fun saveState(state: DesktopState) = settings.set("desktop_wallpaper", DesktopState.serializer(), state)

    fun currentItem(): MediaItem? = state.mediaId?.let(db::getMedia)

    /** The picture Windows shows right now (under any video), even if this app didn't set it. */
    fun currentPicture(): Path? {
        val static = settings.obj("static_wallpaper", StaticWallpaper.serializer())?.file?.let(::Path)?.takeIf { it.exists() }
        return static
            ?: windows.currentWallpaper()?.let(::Path)?.takeIf { it.exists() }
            ?: windows.transcodedWallpaper()
    }

    // -- static images ------------------------------------------------------------

    /** Makes an image the desktop wallpaper (stops a live wallpaper). */
    fun setImage(item: MediaItem, style: String? = null) {
        require(item.isImage) { "Use setVideo for videos" }
        val local = item.localPath ?: throw IllegalArgumentException("Pictures must be on this PC")
        if (!local.exists()) throw FileNotFoundException("File not found: $local")
        val chosen = style ?: settings.string("image_style", "fill")
        backupOriginal()
        apply(prepareImage(item, local), chosen, item.id)
        live.stop() // after applying the image, so the old picture never flashes up
        autoPauseReason = null
        settings.set("image_style", chosen)
        saveState(DesktopState("image", item.id, item.path, chosen))
        log.info("Desktop wallpaper set to ${item.path} ($chosen)")
    }

    /** Changes how the current image is positioned (fill, fit, ...). */
    fun setImageStyle(style: String) {
        val item = currentItem()
        if (state.kind == "image" && item != null) setImage(item, style) else settings.set("image_style", style)
    }

    /** A file Windows can use: the original for JPG/PNG/BMP, otherwise a PNG copy (first frame for GIFs). */
    private fun prepareImage(item: MediaItem, local: Path): String {
        if (local.extension.lowercase() in NATIVE_WALLPAPER_FORMATS) return local.toString()
        // One file per item, so a converted copy is never overwritten while Windows still reads it.
        val out = paths.cacheDir.resolve("desktop-${item.id}.png")
        Imaging.load(local).use { Imaging.savePng(it, out) }
        return out.toString()
    }

    private fun apply(file: String, style: String, mediaId: Long? = null) {
        windows.setStyle(style)
        windows.setWallpaper(file)
        settings.set("static_wallpaper", StaticWallpaper.serializer(), StaticWallpaper(file, style, mediaId))
    }

    /** Re-applies the static picture so Explorer repaints the area a video covered. */
    private fun repaintStatic() {
        val static = settings.obj("static_wallpaper", StaticWallpaper.serializer())
        val original = settings.obj("original_wallpaper", OriginalWallpaper.serializer())
        val file = listOfNotNull(static?.file, original?.path).firstOrNull { Path(it).exists() } ?: return
        runCatching { windows.setWallpaper(file, persist = false) }.onFailure { log.warning("Could not repaint the desktop: $it") }
    }

    // -- live wallpapers (video, web page, shader) --------------------------------------

    val muted: Boolean get() = settings.boolean("video_muted", true)
    val videoActive: Boolean get() = live.active
    val videoPaused: Boolean get() = live.paused
    /** Why the live wallpaper couldn't play (e.g. a stream is offline), or null. */
    val liveError: String? get() = live.lastError

    /** Turns a library item into what the live engines play (web and shader items need the web engine). */
    var sourceFor: (MediaItem, style: String) -> LiveSource = { item, style ->
        LiveSource(LiveKind.Video, item.source, fill = style == "fill", muted = muted)
    }

    /** Plays a video, web page or shader behind the desktop icons. */
    fun setLive(item: MediaItem, style: String? = null) {
        require(item.isLive) { "Use setImage for images" }
        if (!item.available()) throw FileNotFoundException("Not found: ${item.path}")
        val chosen = style ?: settings.string("video_style", "fill")
        backupOriginal()
        live.play(sourceFor(item, chosen))
        autoPauseReason = null // a new wallpaper starts playing; the next check re-pauses if needed
        if (item.isVideo) settings.set("video_style", chosen)
        saveState(DesktopState(item.mediaType, item.id, item.source, chosen))
        log.info("Live wallpaper set to ${item.source} ($chosen)")
    }

    fun setVideo(item: MediaItem, style: String? = null) = setLive(item, style)

    /** Sets any library item as the desktop wallpaper. */
    fun set(item: MediaItem) = if (item.isImage) setImage(item) else setLive(item)

    /** Plays the current live wallpaper again (e.g. after its options changed). */
    fun restartLive() {
        val item = currentItem() ?: return
        if (state.kind != "image" && item.isLive) setLive(item, state.style)
    }

    fun setVideoStyle(style: String) {
        settings.set("video_style", style)
        live.setFill(style == "fill")
        if (state.kind == "video") saveState(state.copy(style = style))
    }

    fun setMuted(muted: Boolean) {
        settings.set("video_muted", muted)
        live.setMuted(muted)
    }

    /** Pause requested by the user: stays paused until the user resumes. */
    fun pauseVideo() {
        live.pause()
        autoPauseReason = null
    }

    /** Resume requested by the user, even if an automatic reason still applies. */
    fun resumeVideo() {
        live.resume()
        autoPauseReason?.let { overriddenReason = it }
        autoPauseReason = null
    }

    /**
     * Called every few seconds with why the video should be paused right now, or null.
     * Only resumes what it paused itself. Returns true if it changed anything.
     */
    fun updateAutoPause(reason: PauseReason?): Boolean {
        if (!live.active) {
            autoPauseReason = null
            overriddenReason = null
            return false
        }
        if (reason == null) {
            overriddenReason = null
            val was = autoPauseReason ?: return false
            log.info("Resuming live wallpaper (${was.text} no longer)")
            autoPauseReason = null
            live.resume()
            return true
        }
        if (autoPauseReason == null && !live.paused && reason != overriddenReason) {
            log.info("Pausing live wallpaper automatically (${reason.text})")
            autoPauseReason = reason
            live.pause()
            return true
        }
        return false
    }

    /** Stops the live wallpaper and goes back to the static picture underneath. */
    fun stopVideo() {
        if (!live.active && (state.kind == null || state.kind == "image")) return
        live.stop()
        autoPauseReason = null
        repaintStatic()
        val static = settings.obj("static_wallpaper", StaticWallpaper.serializer())
        val item = static?.mediaId?.let(db::getMedia)
        saveState(if (item != null) DesktopState("image", item.id, item.path, static.style) else DesktopState())
    }

    /** Starts the saved live wallpaper again after the app was restarted. Returns true if started. */
    fun restoreOnStartup(): Boolean {
        val saved = state
        if (saved.kind !in LIVE_KINDS || !settings.boolean("resume_live_wallpaper", true)) return false
        val item = currentItem()
        if (item == null || !item.available() || !playable(item)) {
            log.warning("Saved live wallpaper is no longer available: ${saved.path}")
            return false
        }
        live.play(sourceFor(item, saved.style))
        return true
    }

    /** Called periodically: recovers after an Explorer restart and follows resolution changes. */
    fun checkLive() {
        if (!live.active) return
        if (!live.isHealthy()) {
            log.warning("Live wallpaper window was lost (Explorer restarted?) - recreating it")
            val wasPaused = autoPauseReason != null
            val item = currentItem()
            live.stop()
            if (item != null && item.isLive) {
                live.play(sourceFor(item, state.style))
                if (wasPaused) live.pause()
            }
        } else {
            live.fitToScreen()
        }
    }

    fun competingApps(): List<String> = if (live.active) live.competingApps() else emptyList()

    /** The app is closing: removes the video window but keeps the saved choice for next start. */
    fun shutdown() {
        if (live.active) {
            live.stop()
            repaintStatic()
        }
    }

    // -- library shortcuts ---------------------------------------------------------

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
        val item = db.listMedia().filter { it.id != current && playable(it) && it.available() }.randomOrNull() ?: return null
        set(item)
        return item
    }

    /** Videos only count when the video engine is available. */
    var videosAvailable: Boolean = true

    private fun playable(item: MediaItem): Boolean = !item.isVideo || videosAvailable

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

    fun restoreOriginal() {
        val original = settings.obj("original_wallpaper", OriginalWallpaper.serializer()) ?: return
        val path = original.path
        if (path != null && Path(path).exists()) apply(path, original.style) else windows.setWallpaper("") // solid colour
        live.stop()
        autoPauseReason = null
        saveState(DesktopState())
        log.info("Restored the original wallpaper")
    }
}
