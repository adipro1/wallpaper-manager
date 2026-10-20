package wpm.win32

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import wpm.logger
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

/** Win32 functions jna-platform doesn't declare the way we need them. */
@Suppress("FunctionName")
internal interface User32Ex : StdCallLibrary {
    fun SystemParametersInfoW(action: Int, param: Int, value: WString?, flags: Int): Boolean
    fun SystemParametersInfoW(action: Int, param: Int, value: CharArray, flags: Int): Boolean

    companion object {
        val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

@Suppress("FunctionName")
internal interface Shell32Ex : StdCallLibrary {
    fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int

    companion object {
        val INSTANCE: Shell32Ex = Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private val log = logger("win32")

/** Gives the process its own taskbar identity (icon and grouping). */
fun setAppUserModelId(appId: String) {
    runCatching { Shell32Ex.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(appId)) }
        .onFailure { log.fine("SetCurrentProcessExplicitAppUserModelID unavailable") }
}

/** The Windows desktop wallpaper settings. An interface so tests can use a fake. */
interface DesktopWallpaperApi {
    /** Path of the current wallpaper picture, or null if unknown. */
    fun currentWallpaper(): String?
    /** Windows' own copy of the current picture (the original file may be gone). */
    fun transcodedWallpaper(): Path?
    fun setWallpaper(path: String, persist: Boolean = true)
    fun style(): String
    fun setStyle(style: String)
}

/** How Windows positions the picture: (WallpaperStyle, TileWallpaper) under HKCU\Control Panel\Desktop. */
val WALLPAPER_STYLES = linkedMapOf(
    "fill" to ("10" to "0"),
    "fit" to ("6" to "0"),
    "stretch" to ("2" to "0"),
    "center" to ("0" to "0"),
    "tile" to ("0" to "1"),
)

object WindowsDesktop : DesktopWallpaperApi {
    private const val SPI_GETDESKWALLPAPER = 0x0073
    private const val SPI_SETDESKWALLPAPER = 0x0014
    private const val SPIF_UPDATEINIFILE = 0x01 // save the change in the user profile
    private const val SPIF_SENDCHANGE = 0x02 // tell other apps the setting changed
    private const val DESKTOP_KEY = "Control Panel\\Desktop"

    override fun currentWallpaper(): String? {
        val buf = CharArray(520)
        if (!User32Ex.INSTANCE.SystemParametersInfoW(SPI_GETDESKWALLPAPER, buf.size, buf, 0)) return null
        return Native.toString(buf).ifEmpty { null }
    }

    override fun transcodedWallpaper(): Path? {
        val appData = System.getenv("APPDATA") ?: return null
        return Path(appData, "Microsoft", "Windows", "Themes", "TranscodedWallpaper").takeIf { it.isRegularFile() }
    }

    override fun setWallpaper(path: String, persist: Boolean) {
        val flags = if (persist) SPIF_UPDATEINIFILE or SPIF_SENDCHANGE else 0
        if (!User32Ex.INSTANCE.SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, WString(path), flags)) {
            throw IOException("Windows refused the wallpaper (error ${Native.getLastError()})")
        }
    }

    override fun style(): String = try {
        val style = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "WallpaperStyle")
        val tile = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "TileWallpaper")
        WALLPAPER_STYLES.entries.firstOrNull { it.value == (style to tile) }?.key ?: "fill"
    } catch (e: Exception) {
        "fill"
    }

    /** Takes effect the next time the wallpaper is set. */
    override fun setStyle(style: String) {
        val (wallpaperStyle, tile) = WALLPAPER_STYLES.getValue(style)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "WallpaperStyle", wallpaperStyle)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "TileWallpaper", tile)
    }
}

/** Opens File Explorer with the file selected. */
fun showInExplorer(path: String) {
    ProcessBuilder("explorer.exe", "/select,", Path(path).normalize().toString()).start()
}

/** Moves a file to the Recycle Bin, so an accidental delete can be undone. */
fun sendToRecycleBin(path: String) {
    val desktop = java.awt.Desktop.getDesktop()
    if (!desktop.moveToTrash(Path(path).toFile())) throw IOException("Could not move the file to the Recycle Bin")
}
