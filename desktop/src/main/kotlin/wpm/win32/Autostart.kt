package wpm.win32

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import wpm.logger
import kotlin.io.path.Path

/** "Start with Windows": a value under the current user's Run key. */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "WallpaperManager"
    private val log = logger("autostart")

    /**
     * Command Windows runs at sign-in: the app hidden in the tray. Packaged builds
     * are started through their launcher .exe (jpackage sets jpackage.app-path).
     */
    fun command(): String? {
        val exe = System.getProperty("jpackage.app-path") ?: return null
        return "\"${Path(exe)}\" --minimized"
    }

    val available: Boolean get() = command() != null

    fun isEnabled(): Boolean =
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE)

    /** Throws if the registry can't be written, so the caller can reset its switch. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val command = command() ?: error("Start with Windows only works in the installed app.")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE, command)
        } else if (isEnabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE)
        }
        log.info("Start with Windows ${if (enabled) "enabled" else "disabled"}")
    }
}
