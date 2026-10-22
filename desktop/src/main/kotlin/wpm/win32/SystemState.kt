package wpm.win32

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/** Windows system state used to pause live wallpapers while nobody can see them. */
object SystemState {
    private val user32 = User32.INSTANCE
    private val ownPid = Kernel32.INSTANCE.GetCurrentProcessId()

    // Values of SHQueryUserNotificationState that mean "the user is busy with something full screen"
    private val BUSY_STATES = setOf(2 /* busy */, 3 /* D3D fullscreen */, 4 /* presentation mode */)
    // The desktop and the taskbar cover the screen too, but they are not "fullscreen apps".
    private val SHELL_CLASSES = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")
    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val DWMWA_CLOAKED = 14
    private const val DESKTOP_SWITCHDESKTOP = 0x0100

    /** True while the lock screen (or another secure desktop, e.g. UAC) shows: we can't open the input desktop then. */
    fun sessionLocked(): Boolean {
        val desktop = User32Ex.INSTANCE.OpenInputDesktop(0, false, DESKTOP_SWITCHDESKTOP) ?: return true
        User32Ex.INSTANCE.CloseDesktop(desktop)
        return false
    }

    private fun monitorRect(hwnd: WinDef.HWND?, flags: Int): ScreenRect? {
        val monitor = user32.MonitorFromWindow(hwnd, flags) ?: return null
        val info = WinUser.MONITORINFO()
        return if (user32.GetMonitorInfo(monitor, info).booleanValue()) ScreenRect.of(info.rcMonitor) else null
    }

    private fun windowRect(hwnd: WinDef.HWND): ScreenRect? {
        val rect = WinDef.RECT()
        return if (user32.GetWindowRect(hwnd, rect)) ScreenRect.of(rect) else null
    }

    private fun cloaked(hwnd: WinDef.HWND): Boolean {
        // Suspended Store apps and windows on other virtual desktops are "visible" but cloaked.
        val value = IntByReference()
        return DwmApi.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, value, 4) == 0 && value.value != 0
    }

    /**
     * The monitor a game, video player or presentation is running full screen on, or null.
     * (Busy states reported by Windows count for the monitor of the foreground window.)
     */
    fun fullscreenMonitor(): ScreenRect? {
        val state = IntByReference()
        val hwnd = user32.GetForegroundWindow()
        if (Shell32Ex.INSTANCE.SHQueryUserNotificationState(state) == 0 && state.value in BUSY_STATES) {
            return monitorRect(hwnd, WinUser.MONITOR_DEFAULTTOPRIMARY)
        }
        // Catches "borderless fullscreen" games, which Windows doesn't always report as busy.
        if (hwnd == null || className(hwnd) in SHELL_CLASSES || windowPid(hwnd) == ownPid) return null
        // A normal maximized window (title bar) is not fullscreen, even with an auto-hiding taskbar.
        if (User32Ex.INSTANCE.IsZoomed(hwnd) && user32.GetWindowLong(hwnd, GWL_STYLE) and WS_CAPTION == WS_CAPTION) return null
        val rect = windowRect(hwnd) ?: return null
        val monitor = monitorRect(hwnd, WinUser.MONITOR_DEFAULTTONEAREST) ?: return null
        return monitor.takeIf { rect.contains(it) }
    }

    /**
     * Monitors whose desktop is hidden by a maximized window. Ignores this app's own
     * windows and tool windows, which don't really hide the wallpaper.
     */
    fun coveredMonitors(): Set<ScreenRect> {
        val covered = mutableSetOf<ScreenRect>()
        user32.EnumWindows({ hwnd, _ ->
            if (user32.IsWindowVisible(hwnd) && User32Ex.INSTANCE.IsZoomed(hwnd) && !User32Ex.INSTANCE.IsIconic(hwnd) &&
                user32.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_TOOLWINDOW == 0 && windowPid(hwnd) != ownPid && !cloaked(hwnd)
            ) {
                monitorRect(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)?.let(covered::add)
            }
            true
        }, null)
        return covered
    }

    /**
     * The program (e.g. "chrome.exe", lower case) whose window is in front, or null when the
     * desktop, the taskbar or this app is in front.
     */
    fun foregroundApp(): String? {
        val hwnd = user32.GetForegroundWindow() ?: return null
        if (className(hwnd) in SHELL_CLASSES || windowPid(hwnd) == ownPid) return null
        return windowProcessName(hwnd).lowercase().takeIf { it.endsWith(".exe") }
    }

    /** Programs running right now (e.g. "chrome.exe", lower case). */
    fun runningApps(): Set<String> {
        val k32 = Kernel32.INSTANCE
        val snapshot = k32.CreateToolhelp32Snapshot(com.sun.jna.platform.win32.Tlhelp32.TH32CS_SNAPPROCESS, com.sun.jna.platform.win32.WinDef.DWORD(0))
            ?: return emptySet()
        val names = mutableSetOf<String>()
        try {
            val entry = com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32.ByReference()
            var more = k32.Process32First(snapshot, entry)
            while (more) {
                names += Native.toString(entry.szExeFile).lowercase()
                more = k32.Process32Next(snapshot, entry)
            }
        } finally {
            k32.CloseHandle(snapshot)
        }
        return names
    }

    /** Programs with a visible window right now (for picking one in Settings), lower case and sorted. */
    fun windowedApps(): List<String> {
        val names = sortedSetOf<String>()
        user32.EnumWindows({ hwnd, _ ->
            if (user32.IsWindowVisible(hwnd) && user32.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_TOOLWINDOW == 0 &&
                user32.GetWindowTextLength(hwnd) > 0 && windowPid(hwnd) != ownPid && !cloaked(hwnd) && className(hwnd) !in SHELL_CLASSES
            ) {
                windowProcessName(hwnd).lowercase().takeIf { it.endsWith(".exe") }?.let(names::add)
            }
            true
        }, null)
        return names.toList()
    }

    /** True when running on battery (ACLineStatus 0). Desktops without a battery report AC power. */
    fun onBattery(): Boolean {
        val status = PowerStatus()
        return Kernel32Ex.INSTANCE.GetSystemPowerStatus(status) && status.acLineStatus.toInt() == 0
    }
}

@Structure.FieldOrder("acLineStatus", "batteryFlag", "batteryLifePercent", "systemStatusFlag", "batteryLifeTime", "batteryFullLifeTime")
class PowerStatus : Structure() {
    @JvmField var acLineStatus: Byte = 0
    @JvmField var batteryFlag: Byte = 0
    @JvmField var batteryLifePercent: Byte = 0
    @JvmField var systemStatusFlag: Byte = 0
    @JvmField var batteryLifeTime: Int = 0
    @JvmField var batteryFullLifeTime: Int = 0
}

@Suppress("FunctionName")
internal interface Kernel32Ex : StdCallLibrary {
    fun GetSystemPowerStatus(status: PowerStatus): Boolean

    companion object {
        val INSTANCE: Kernel32Ex = Native.load("kernel32", Kernel32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}
