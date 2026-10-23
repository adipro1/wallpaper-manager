package wpm.win32

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import wpm.logger

private val log = logger("overlay")
private val user32 = User32.INSTANCE

private const val WS_CHILD = 0x40000000
private const val WS_VISIBLE = 0x10000000
private const val WS_EX_LAYERED = 0x00080000
private const val WS_EX_TRANSPARENT = 0x00000020
private const val WS_EX_NOACTIVATE = 0x08000000
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val SWP_SHOWWINDOW = 0x0040
private const val SWP_HIDEWINDOW = 0x0080
private const val GW_HWNDPREV = 3
private const val WM_DESTROY = 0x0002
private const val WM_CLOSE = 0x0010
private const val ULW_ALPHA = 0x2
private const val AC_SRC_ALPHA = 0x1

/** A picture with per-pixel transparency: rows of premultiplied BGRA bytes (Skia's N32 on Windows). */
class OverlayPicture(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(pixels.size == width * height * 4) }
}

/** A see-through picture on the desktop. An interface so tests don't need Explorer. */
interface Overlay {
    fun start()
    fun isAlive(): Boolean
    /** Keeps it above the wallpaper; the other overlays in [ours] may stay above it. */
    fun raise(ours: Collection<Overlay>)
    /** Shows [picture] with its top-left corner at [x], [y] (screen pixels). */
    fun show(picture: OverlayPicture, x: Int, y: Int)
    fun hide()
    fun close()
}

/**
 * A see-through picture behind the desktop icons, above the wallpaper (e.g. a clock widget).
 * It is a layered child window in the wallpaper layer, drawn with UpdateLayeredWindow, so its
 * soft edges blend with whatever is underneath: a picture, a video or a web page. Mouse
 * clicks pass through it to the desktop.
 */
class DesktopOverlay(private val name: String) : Overlay {
    @Volatile private var hwnd: HWND? = null
    private var parent: HWND? = null
    /** 24H2+: the icon window to stay directly below. */
    private var below: HWND? = null
    private var thread: Thread? = null
    private var shown: ScreenRect? = null

    val started: Boolean get() = hwnd != null

    override fun start() {
        val (window, t) = messageLoopThread("overlay-$name", 5_000) {
            val layer = findWallpaperLayer()
            val hwnd = HostWindowClass.create(WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE,
                "Wallpaper Manager $name", WS_CHILD, ScreenRect(0, 0, 1, 1), layer.parent)
            HostWindowClass.handle(hwnd) { msg, _, _ ->
                if (msg == WM_DESTROY) { user32.PostQuitMessage(0); WinDef.LRESULT(0) } else null
            }
            parent = layer.parent
            below = layer.insertAfter
            hwnd
        }
        hwnd = window
        thread = t
        shown = null
        raise(emptyList())
    }

    /** False if Explorer was restarted (our window dies with its parent). */
    override fun isAlive(): Boolean {
        val h = hwnd ?: return false
        return user32.IsWindow(h) && user32.GetParent(h) == parent
    }

    /** A live wallpaper started later goes on top of the overlay, so this moves it back up. */
    override fun raise(ours: Collection<Overlay>) {
        val h = hwnd ?: return
        val mine = ours.filterIsInstance<DesktopOverlay>().mapNotNull { it.hwnd?.let(Pointer2::value) }.toSet()
        var above = user32.GetWindow(h, WinDef.DWORD(GW_HWNDPREV.toLong()))
        while (above != null && (Pointer2.value(above) in mine)) above = user32.GetWindow(above, WinDef.DWORD(GW_HWNDPREV.toLong()))
        val target = below
        val onTop = if (target != null) above == target else above == null
        if (!onTop) {
            user32.SetWindowPos(h, target ?: HWND(Pointer.createConstant(0)), 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE)
        }
    }

    override fun show(picture: OverlayPicture, x: Int, y: Int) {
        val h = hwnd ?: return
        val p = parent ?: return
        val rect = ScreenRect(x, y, picture.width, picture.height)
        if (rect != shown) {
            val client = toClient(p, rect)
            user32.SetWindowPos(h, null, client.x, client.y, client.width, client.height, SWP_NOZORDER or SWP_NOACTIVATE or SWP_SHOWWINDOW)
            shown = rect
        }
        val gdi = GDI32.INSTANCE
        val screen = user32.GetDC(null) ?: return
        val memory = gdi.CreateCompatibleDC(screen)
        val info = WinGDI.BITMAPINFO().apply {
            bmiHeader.biWidth = picture.width
            bmiHeader.biHeight = -picture.height // top-down rows
            bmiHeader.biPlanes = 1
            bmiHeader.biBitCount = 32
            bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val bits = PointerByReference()
        val bitmap = gdi.CreateDIBSection(memory, info, WinGDI.DIB_RGB_COLORS, bits, null, 0)
        if (bitmap == null) {
            gdi.DeleteDC(memory)
            user32.ReleaseDC(null, screen)
            return
        }
        val old = gdi.SelectObject(memory, bitmap)
        try {
            bits.value.write(0, picture.pixels, 0, picture.pixels.size)
            val blend = WinUser.BLENDFUNCTION().apply {
                BlendOp = 0 // AC_SRC_OVER
                SourceConstantAlpha = 255.toByte()
                AlphaFormat = AC_SRC_ALPHA.toByte()
            }
            val ok = user32.UpdateLayeredWindow(h, screen, null, WinUser.SIZE(picture.width, picture.height),
                memory, WinDef.POINT(0, 0), 0, blend, ULW_ALPHA)
            if (!ok) log.fine("UpdateLayeredWindow failed for $name (${com.sun.jna.Native.getLastError()})")
        } finally {
            gdi.SelectObject(memory, old)
            gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory)
            user32.ReleaseDC(null, screen)
        }
    }

    override fun hide() {
        val h = hwnd ?: return
        if (shown == null) return
        user32.SetWindowPos(h, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_HIDEWINDOW)
        shown = null
    }

    override fun close() {
        val h = hwnd ?: return
        if (user32.IsWindow(h)) user32.PostMessage(h, WM_CLOSE, null, null)
        thread?.join(3_000)
        HostWindowClass.forget(h)
        hwnd = null
        thread = null
        shown = null
    }
}
