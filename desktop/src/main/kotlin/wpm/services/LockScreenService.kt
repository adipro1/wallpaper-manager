package wpm.services

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import wpm.AppPaths
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.logger
import wpm.media.Imaging
import wpm.media.VideoReader
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

private val log = logger("lockscreen")
private val NATIVE_FORMATS = setOf("jpg", "jpeg", "png")

/** The Windows lock screen picture. An interface so tests can use a fake. */
interface LockScreenApi {
    fun setImage(file: Path)
    /** Saves the picture currently shown (also for Windows Spotlight) to [target]. */
    fun readImage(target: Path)
    fun spotlightEnabled(): Boolean
    fun setSpotlight(enabled: Boolean)
}

/** Calls the WinRT LockScreen API through a small PowerShell script (no admin rights needed). */
class PowerShellLockScreen(private val scriptDir: Path) : LockScreenApi {
    private val spotlightKey = "Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager"

    private val script: Path by lazy {
        Files.createDirectories(scriptDir)
        scriptDir.resolve("lockscreen.ps1").also { target ->
            javaClass.getResourceAsStream("/powershell/lockscreen.ps1")!!.use { Files.write(target, it.readBytes()) }
        }
    }

    private fun run(action: String, path: Path) {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePathString(), action, path.absolutePathString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Windows didn't respond in time")
        }
        if (process.exitValue() != 0) {
            log.warning("lockscreen.ps1 $action failed: $output")
            throw IOException(output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "PowerShell error")
        }
    }

    override fun setImage(file: Path) = run("set", file)
    override fun readImage(target: Path) = run("read", target)

    override fun spotlightEnabled(): Boolean = try {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, spotlightKey, "RotatingLockScreenEnabled") != 0
    } catch (e: Exception) {
        false
    }

    override fun setSpotlight(enabled: Boolean) =
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, spotlightKey, "RotatingLockScreenEnabled", if (enabled) 1 else 0)
}

@Serializable
data class LockScreenState(
    @SerialName("media_id") val mediaId: Long? = null,
    val path: String? = null,
    val timestamp: Double? = null, // seconds into a video
)

@Serializable
data class OriginalLockScreen(val path: String? = null, val spotlight: Boolean = false)

fun formatTimestamp(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    return "%d:%05.2f".format(java.util.Locale.ROOT, (s / 60).toInt(), s % 60)
}

/**
 * Windows only supports still images on the lock screen, so for videos a single frame
 * is extracted and used. All methods block (PowerShell): call from a background dispatcher.
 */
class LockScreenService(
    private val db: Database,
    private val paths: AppPaths,
    private val api: LockScreenApi,
    var videos: VideoReader? = null,
) {
    private val settings = Settings(db)
    private val folder = paths.dataDir.resolve("lockscreen")

    val state: LockScreenState get() = settings.obj("lock_screen", LockScreenState.serializer()) ?: LockScreenState()

    fun currentItem(): MediaItem? = state.mediaId?.let(db::getMedia)

    /** Copies the picture now on the lock screen into the cache and returns that file. */
    fun currentImage(): Path {
        val target = paths.cacheDir.resolve("current_lockscreen")
        api.readImage(target)
        return target
    }

    fun setImage(item: MediaItem) {
        require(item.isImage) { "Pick a frame with setVideoFrame for videos" }
        val local = item.localPath ?: throw IllegalArgumentException("Only pictures on this PC can be used")
        if (!local.exists()) throw FileNotFoundException("File not found: $local")
        backupOriginal()
        var file = local
        if (file.extension.lowercase() !in NATIVE_FORMATS) { // WEBP / GIF / BMP -> PNG
            file = folder.resolve("lockscreen-${item.id}.png")
            Imaging.load(local).use { Imaging.savePng(it, file) }
        }
        set(file, LockScreenState(item.id, item.path, null))
    }

    /** Extracts the frame at [seconds] and uses it as the lock screen. Returns the frame file. */
    fun setVideoFrame(item: MediaItem, seconds: Double): Path {
        require(item.isVideo) { "Not a video" }
        val local = item.localPath ?: throw IllegalArgumentException("Save the video for offline use first")
        if (!local.exists()) throw FileNotFoundException("File not found: $local")
        val reader = videos ?: throw IllegalStateException("Video playback isn't available")
        backupOriginal()
        Files.createDirectories(folder)
        folder.listDirectoryEntries("frame-*.jpg").forEach { Files.deleteIfExists(it) } // keep only the current frame
        val file = folder.resolve("frame-${item.id}-${(seconds * 1000).toLong()}.jpg")
        reader.frame(local, seconds).use { Imaging.saveJpeg(it, file, 95) }
        set(file, LockScreenState(item.id, item.path, seconds))
        return file
    }

    private fun set(file: Path, state: LockScreenState) {
        api.setImage(file.toAbsolutePath().normalize())
        settings.set("lock_screen", LockScreenState.serializer(), state)
        log.info("Lock screen set to $file")
    }

    /** Before the first change, saves the current picture and whether Windows Spotlight was on. */
    fun backupOriginal() {
        if (db.getSetting("original_lock_screen") != null) return
        var backup: String? = null
        try {
            val dir = paths.dataDir.resolve("backup")
            Files.createDirectories(dir)
            val temp = dir.resolve("original_lockscreen.tmp")
            api.readImage(temp)
            val target = dir.resolve("original_lockscreen${if (Imaging.sniffExtension(temp) == ".png") ".png" else ".jpg"}")
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            backup = target.toString()
        } catch (e: Exception) { // a missing backup must not block setting a new image
            log.warning("Could not back up the current lock screen: $e")
        }
        settings.set("original_lock_screen", OriginalLockScreen.serializer(), OriginalLockScreen(backup, api.spotlightEnabled()))
        log.info("Backed up original lock screen to $backup")
    }

    fun hasOriginal(): Boolean = db.getSetting("original_lock_screen") != null

    fun restoreOriginal() {
        val original = settings.obj("original_lock_screen", OriginalLockScreen.serializer()) ?: return
        original.path?.let(::Path)?.takeIf { it.exists() }?.let(api::setImage) // the old picture, even if Spotlight doesn't come back
        if (original.spotlight) runCatching { api.setSpotlight(true) } // best effort
        settings.set("lock_screen", LockScreenState.serializer(), LockScreenState())
        log.info("Restored the original lock screen")
    }
}
