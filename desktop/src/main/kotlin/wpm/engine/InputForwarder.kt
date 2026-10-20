package wpm.engine

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import wpm.logger
import wpm.win32.ScreenRect
import wpm.win32.User32Ex
import wpm.win32.className
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

private val log = logger("input")

/**
 * Makes web wallpapers interactive, like Lively does: the desktop icon window sits above
 * the wallpaper and gets all mouse input, so a low-level mouse hook watches the mouse and,
 * while it is over the empty desktop, posts the same input to Chromium's window.
 *
 * Input is only copied, never swallowed: icons, the context menu and selection still work.
 * The hook callback must return quickly (Windows drops slow hooks), so it only queues
 * events; a second thread checks the window under the cursor and posts the messages.
 */
class InputForwarder(@Volatile var monitor: ScreenRect, private val target: () -> HWND?) {
    @Volatile var enabled = false

    private data class MouseEvent(val message: Int, val x: Int, val y: Int, val mouseData: Int)

    private val queue = LinkedBlockingQueue<MouseEvent>(256)
    private var hookThread: Thread? = null
    private var workerThread: Thread? = null
    @Volatile private var hookThreadId = 0
    @Volatile private var running = false
    private var cachedTarget: HWND? = null
    private var buttons = 0

    // Kept in a field so it isn't garbage-collected while Windows calls it.
    private val hookProc = WinUser.LowLevelMouseProc { nCode, wParam, info ->
        if (nCode >= 0 && enabled) {
            val message = wParam.toInt()
            if (message in FORWARDED) queue.offer(MouseEvent(message, info.pt.x, info.pt.y, info.mouseData))
        }
        User32.INSTANCE.CallNextHookEx(null, nCode, wParam, WinDef.LPARAM(Pointer.nativeValue(info.pointer)))
    }

    fun start() {
        running = true
        hookThread = thread(name = "mouse-hook", isDaemon = true) {
            hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val hook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, hookProc, Kernel32.INSTANCE.GetModuleHandle(null), 0)
            if (hook == null) {
                log.warning("Couldn't install the mouse hook; web wallpapers won't react to the mouse")
                return@thread
            }
            val msg = WinUser.MSG()
            while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
            User32.INSTANCE.UnhookWindowsHookEx(hook)
        }
        workerThread = thread(name = "mouse-forward", isDaemon = true) {
            while (running) {
                val event = runCatching { queue.take() }.getOrNull() ?: break
                if (event.message == WM_QUIT_MARKER) break
                runCatching { forward(event) }
            }
        }
    }

    private fun overDesktop(x: Int, y: Int): Boolean {
        val under = User32Ex.INSTANCE.WindowFromPoint(WinDef.POINT.ByValue(x, y)) ?: return false
        return className(under) in DESKTOP_CLASSES
    }

    private fun forward(event: MouseEvent) {
        val m = monitor
        if (event.x !in m.x until m.right || event.y !in m.y until m.bottom) return
        // Releases are always delivered, so a drag that leaves the desktop doesn't get stuck.
        val isRelease = event.message == WM_LBUTTONUP || event.message == WM_RBUTTONUP
        if (!isRelease && !overDesktop(event.x, event.y)) return
        val widget = cachedTarget?.takeIf { User32.INSTANCE.IsWindow(it) } ?: target().also { cachedTarget = it } ?: return

        val point = WinDef.POINT(event.x, event.y)
        User32Ex.INSTANCE.ScreenToClient(widget, point)
        val clientPos = (point.y shl 16) or (point.x and 0xFFFF)
        when (event.message) {
            WM_LBUTTONDOWN -> buttons = buttons or MK_LBUTTON
            WM_LBUTTONUP -> buttons = buttons and MK_LBUTTON.inv()
            WM_RBUTTONDOWN -> buttons = buttons or MK_RBUTTON
            WM_RBUTTONUP -> buttons = buttons and MK_RBUTTON.inv()
        }
        val (wParam, lParam) = when (event.message) {
            // Wheel messages carry the delta in the high word and screen coordinates.
            WM_MOUSEWHEEL -> ((event.mouseData.toLong() and 0xFFFF0000) or buttons.toLong()) to ((event.y shl 16) or (event.x and 0xFFFF)).toLong()
            else -> buttons.toLong() to clientPos.toLong()
        }
        User32.INSTANCE.PostMessage(widget, event.message, WinDef.WPARAM(wParam), WinDef.LPARAM(lParam))
    }

    fun stop() {
        running = false
        enabled = false
        if (hookThreadId != 0) User32.INSTANCE.PostThreadMessage(hookThreadId, WinUser.WM_QUIT, null, null)
        queue.offer(MouseEvent(WM_QUIT_MARKER, 0, 0, 0))
        hookThread?.join(1_000)
        workerThread?.join(1_000)
    }

    private companion object {
        const val WM_MOUSEMOVE = 0x0200
        const val WM_LBUTTONDOWN = 0x0201
        const val WM_LBUTTONUP = 0x0202
        const val WM_RBUTTONDOWN = 0x0204
        const val WM_RBUTTONUP = 0x0205
        const val WM_MOUSEWHEEL = 0x020A
        const val WM_QUIT_MARKER = -1
        const val MK_LBUTTON = 0x0001
        const val MK_RBUTTON = 0x0002
        val FORWARDED = setOf(WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_RBUTTONDOWN, WM_RBUTTONUP, WM_MOUSEWHEEL)
        // The icon list view and the wallpaper layers: the cursor is over the bare desktop.
        val DESKTOP_CLASSES = setOf("SysListView32", "SHELLDLL_DefView", "WorkerW", "Progman")
    }
}
