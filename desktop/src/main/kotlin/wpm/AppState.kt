package wpm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wpm.data.Database
import wpm.data.Settings

enum class Page(val label: String) {
    Home("Home"),
    Desktop("Desktop"),
    LockScreen("Lock Screen"),
    Library("Library"),
    Playlists("Playlists"),
    Schedule("Schedule"),
    Settings("Settings"),
}

enum class ToastKind { Info, Success, Error }

data class ToastMessage(val text: String, val kind: ToastKind, val id: Long = System.nanoTime())

/** Everything the UI needs, shared by all pages. Lives as long as the process. */
class AppState(val paths: AppPaths, val db: Database, startMinimized: Boolean) {
    val settings = Settings(db)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var page by mutableStateOf(Page.Home)
    var windowVisible by mutableStateOf(!startMinimized)
    var appearance by mutableStateOf(settings.string("appearance_mode", "dark"))
        private set
    var systemDark by mutableStateOf(systemUsesDarkTheme())
        private set
    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    val darkTheme: Boolean
        get() = when (appearance) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

    init {
        // Follow the Windows theme while "system" is selected.
        scope.launch {
            while (true) {
                delay(5_000)
                systemDark = systemUsesDarkTheme()
            }
        }
    }

    fun updateAppearance(mode: String) {
        appearance = mode
        settings.set("appearance_mode", mode)
    }

    /** Brief confirmation at the bottom of the window. Skipped while the window is hidden. */
    fun toast(text: String, kind: ToastKind = ToastKind.Info) {
        if (windowVisible) toast = ToastMessage(text, kind)
    }

    fun dismissToast(message: ToastMessage) {
        if (toast?.id == message.id) toast = null
    }

    fun close() {
        scope.cancel()
    }
}

private fun systemUsesDarkTheme(): Boolean = try {
    Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    ) == 0
} catch (e: Exception) {
    true
}
