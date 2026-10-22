package wpm.win32

import wpm.tr
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/** A rectangle in screen (virtual desktop) pixels. */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(other: ScreenRect) = other.x <= x && other.y <= y && other.right >= right && other.bottom >= bottom

    companion object {
        fun of(rect: WinDef.RECT) = ScreenRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }
}

/** A display. [id] is the device name (e.g. \\.\DISPLAY1), stable while the setup doesn't change. */
data class Monitor(val id: String, val bounds: ScreenRect, val primary: Boolean) {
    /** "Display 1", for the UI. */
    val label: String get() = tr("Display {0}", id.substringAfter("DISPLAY", id).trimStart('\\', '.'))
}

object Monitors {
    private const val MONITORINFOF_PRIMARY = 1

    fun all(): List<Monitor> {
        val result = mutableListOf<Monitor>()
        User32.INSTANCE.EnumDisplayMonitors(null, null, { hMonitor, _, _, _ ->
            val info = WinUser.MONITORINFOEX()
            if (User32.INSTANCE.GetMonitorInfo(hMonitor, info).booleanValue()) {
                result += Monitor(Native.toString(info.szDevice), ScreenRect.of(info.rcMonitor), info.dwFlags and MONITORINFOF_PRIMARY != 0)
            }
            1
        }, null)
        return result.sortedWith(compareByDescending<Monitor> { it.primary }.thenBy { it.id })
    }

    fun primary(): Monitor = all().firstOrNull { it.primary }
        ?: Monitor("primary", ScreenRect(0, 0, User32.INSTANCE.GetSystemMetrics(0), User32.INSTANCE.GetSystemMetrics(1)), true)
}
