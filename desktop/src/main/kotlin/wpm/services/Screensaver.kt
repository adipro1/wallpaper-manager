package wpm.services

import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinReg
import kotlinx.serialization.Serializable
import wpm.data.Settings
import wpm.logger
import wpm.tr
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = logger("screensaver")

/** The Windows screensaver settings (Control Panel\Desktop); null = the value didn't exist. */
@Serializable
data class ScreensaverValues(
    /** SCRNSAVE.EXE: the screensaver program. */
    val program: String? = null,
    /** ScreenSaveActive: "1" = on. */
    val active: String? = null,
    /** ScreenSaveTimeOut: seconds of no input before it starts. */
    val timeout: String? = null,
)

/** Reads and writes the Windows screensaver. An interface so tests don't change the real one. */
interface ScreensaverApi {
    fun read(): ScreensaverValues
    fun write(values: ScreensaverValues)
}

class WindowsScreensaver : ScreensaverApi {
    private val key = "Control Panel\\Desktop"
    private val hkcu = WinReg.HKEY_CURRENT_USER

    private fun string(name: String): String? = runCatching {
        if (Advapi32Util.registryValueExists(hkcu, key, name)) Advapi32Util.registryGetStringValue(hkcu, key, name) else null
    }.getOrNull()

    override fun read() = ScreensaverValues(string("SCRNSAVE.EXE"), string("ScreenSaveActive"), string("ScreenSaveTimeOut"))

    override fun write(values: ScreensaverValues) {
        fun set(name: String, value: String?) {
            if (value != null) Advapi32Util.registrySetStringValue(hkcu, key, name, value)
            else if (Advapi32Util.registryValueExists(hkcu, key, name)) Advapi32Util.registryDeleteValue(hkcu, key, name)
        }
        set("SCRNSAVE.EXE", values.program)
        set("ScreenSaveActive", values.active)
        set("ScreenSaveTimeOut", values.timeout)
        // Tell Windows now, not at the next sign-in.
        val user32 = wpm.win32.User32Ex.INSTANCE
        user32.SystemParametersInfoW(SPI_SETSCREENSAVEACTIVE, if (values.active == "1") 1 else 0, null as com.sun.jna.WString?, SPIF_UPDATE)
        values.timeout?.toIntOrNull()?.let { user32.SystemParametersInfoW(SPI_SETSCREENSAVETIMEOUT, it, null as com.sun.jna.WString?, SPIF_UPDATE) }
    }

    private companion object {
        const val SPI_SETSCREENSAVETIMEOUT = 0x000F
        const val SPI_SETSCREENSAVEACTIVE = 0x0011
        const val SPIF_UPDATE = 0x3 // SPIF_UPDATEINIFILE | SPIF_SENDCHANGE
    }
}

/**
 * Wallpaper Manager as the Windows screensaver. Windows runs a ".scr" program with "/s"; ours is
 * a copy of the app's launcher next to it (the launcher finds its settings by its base name), which
 * hands the job to the running app. The user's own screensaver settings are saved first and put
 * back when this is switched off.
 */
class ScreensaverSetup(
    private val settings: Settings,
    private val api: ScreensaverApi = WindowsScreensaver(),
    /** The installed app's launcher (WallpaperManager.exe); null when running from a build. */
    private val launcher: () -> Path? = { System.getProperty("jpackage.app-path")?.let(Path::of)?.takeIf(Files::isRegularFile) },
) {
    val enabled: Boolean get() = settings.boolean("screensaver", false)
    /** Minutes without input before it starts. */
    val minutes: Int get() = settings.string("screensaver_minutes", "10").toIntOrNull()?.coerceIn(1, 120) ?: 10
    /** What it shows: "wallpaper" (the desktop wallpaper, live ones playing) or "playlist:<id>" (a slideshow). */
    val source: String get() = settings.string("screensaver_source", "wallpaper")

    /** Switches it on or off. Throws IllegalStateException with a message for the user when it can't. */
    fun setEnabled(on: Boolean) {
        if (on) {
            val program = install()
            if (!enabled) settings.set(ORIGINAL, ScreensaverValues.serializer(), api.read())
            settings.set("screensaver", true)
            api.write(ScreensaverValues(program, "1", (minutes * 60).toString()))
            log.info("Screensaver on ($program, after $minutes min)")
        } else if (enabled) {
            settings.set("screensaver", false)
            api.write(settings.obj(ORIGINAL, ScreensaverValues.serializer()) ?: ScreensaverValues())
            settings.remove(ORIGINAL)
            log.info("Screensaver off; the previous one is back")
        }
    }

    fun setMinutes(value: Int) {
        settings.set("screensaver_minutes", value.coerceIn(1, 120).toString())
        if (enabled) api.write(api.read().copy(timeout = (minutes * 60).toString()))
    }

    fun setSource(value: String) = settings.set("screensaver_source", value)

    /** After an update the launcher may have changed: the screensaver copy follows it. */
    fun refresh() {
        if (enabled) runCatching { install() }.onFailure { log.warning("Couldn't update the screensaver file: $it") }
    }

    /** Copies the launcher to WallpaperManager.scr beside it; returns the path Windows should run. */
    private fun install(): String {
        val exe = launcher() ?: throw IllegalStateException(tr("The screensaver works in the installed app only."))
        val scr = exe.resolveSibling(exe.fileName.toString().substringBeforeLast('.') + ".scr")
        try {
            if (!Files.exists(scr) || Files.size(scr) != Files.size(exe)) Files.copy(exe, scr, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw IllegalStateException(tr("Couldn't create the screensaver file: {0}", e.message ?: e.toString()))
        }
        return shortPath(scr)
    }

    companion object {
        private const val ORIGINAL = "original_screensaver"

        /** The 8.3 form of a path with spaces (what Windows itself writes for screensavers), if the drive has one. */
        fun shortPath(path: Path): String {
            val long = path.toAbsolutePath().toString()
            return runCatching {
                val buffer = CharArray(1024)
                val length = Kernel32.INSTANCE.GetShortPathName(long, buffer, buffer.size)
                if (length in 1 until buffer.size) Native.toString(buffer) else long
            }.getOrDefault(long)
        }
    }
}
