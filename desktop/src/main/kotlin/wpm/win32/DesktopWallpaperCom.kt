package wpm.win32

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Windows' IDesktopWallpaper COM interface: a different picture on each display.
 * (SystemParametersInfo can only set one picture for all displays.)
 */
object DesktopWallpaperCom {
    private val CLSID_DesktopWallpaper = Guid.GUID.fromString("{C2CF3110-460E-4FC1-B9D0-8A1C0C9CC4BD}")
    private val IID_IDesktopWallpaper = Guid.GUID.fromString("{B92B56A9-8B55-4E14-9A89-0199BBB6F93B}")
    private const val CLSCTX_ALL = 0x17

    // DESKTOP_WALLPAPER_POSITION
    private val POSITIONS = mapOf("center" to 0, "tile" to 1, "stretch" to 2, "fit" to 3, "fill" to 4)

    private class Com(pointer: Pointer) : Unknown(pointer) {
        fun call(index: Int, vararg args: Any?): Int = _invokeNativeInt(index, arrayOf(pointer, *args))
    }

    private fun <T> withWallpaper(block: (Com) -> T): T {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        try {
            val ref = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(CLSID_DesktopWallpaper, null, CLSCTX_ALL, IID_IDesktopWallpaper, ref).toInt()
            if (hr < 0) throw IllegalStateException("IDesktopWallpaper unavailable (0x${Integer.toHexString(hr)})")
            val com = Com(ref.value)
            try {
                return block(com)
            } finally {
                com.Release()
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun check(hr: Int, what: String) {
        if (hr < 0) throw IllegalStateException("$what failed (0x${Integer.toHexString(hr)})")
    }

    /** Windows' display ids (device paths) with their screen rectangles. */
    fun displays(): List<Pair<String, ScreenRect>> = withWallpaper { com ->
        val count = IntByReference()
        check(com.call(6, count), "Counting displays") // GetMonitorDevicePathCount
        (0 until count.value).mapNotNull { i ->
            val idRef = PointerByReference()
            if (com.call(5, i, idRef) < 0) return@mapNotNull null // GetMonitorDevicePathAt
            val id = idRef.value.getWideString(0)
            Ole32.INSTANCE.CoTaskMemFree(idRef.value)
            val rect = WinDef.RECT()
            if (com.call(7, WString(id), rect) < 0) return@mapNotNull null // GetMonitorRECT (inactive displays fail)
            id to ScreenRect.of(rect)
        }
    }

    /** Shows [file] on the display covering [bounds]. The position style applies to all displays (a Windows limit). */
    fun setWallpaper(bounds: ScreenRect, file: String, style: String) = withWallpaper { com ->
        val count = IntByReference()
        check(com.call(6, count), "Counting displays")
        val id = (0 until count.value).firstNotNullOfOrNull { i ->
            val idRef = PointerByReference()
            if (com.call(5, i, idRef) < 0) return@firstNotNullOfOrNull null
            val candidate = idRef.value.getWideString(0)
            Ole32.INSTANCE.CoTaskMemFree(idRef.value)
            val rect = WinDef.RECT()
            candidate.takeIf { com.call(7, WString(it), rect) >= 0 && ScreenRect.of(rect) == bounds }
        } ?: throw IllegalStateException("That display isn't connected any more")
        POSITIONS[style]?.let { com.call(10, it) } // SetPosition
        check(com.call(3, WString(id), WString(file)), "Setting the wallpaper") // SetWallpaper
    }
}
