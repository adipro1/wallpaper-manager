package wpm.win32

import com.sun.jna.Memory
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import java.awt.image.BufferedImage

/** Pictures of a window's own content, even while other windows cover it. */
object WindowCapture {
    private const val PW_RENDERFULLCONTENT = 2 // includes GPU-drawn (DirectComposition) content

    fun capture(hwnd: WinDef.HWND): BufferedImage? {
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        val gdi = GDI32.INSTANCE
        val screen = User32.INSTANCE.GetDC(null) ?: return null
        val memory = gdi.CreateCompatibleDC(screen)
        val bitmap = gdi.CreateCompatibleBitmap(screen, width, height)
        val old = gdi.SelectObject(memory, bitmap)
        try {
            if (!User32.INSTANCE.PrintWindow(hwnd, memory, PW_RENDERFULLCONTENT)) return null
            val info = WinGDI.BITMAPINFO().apply {
                bmiHeader.biWidth = width
                bmiHeader.biHeight = -height // top-down rows
                bmiHeader.biPlanes = 1
                bmiHeader.biBitCount = 32
                bmiHeader.biCompression = WinGDI.BI_RGB
            }
            val pixels = Memory(width.toLong() * height * 4)
            if (gdi.GetDIBits(memory, bitmap, 0, height, pixels, info, WinGDI.DIB_RGB_COLORS) == 0) return null
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width)
            }
        } finally {
            gdi.SelectObject(memory, old)
            gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory)
            User32.INSTANCE.ReleaseDC(null, screen)
        }
    }
}
