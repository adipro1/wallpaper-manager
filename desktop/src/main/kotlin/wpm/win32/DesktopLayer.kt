package wpm.win32

import wpm.tr
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import wpm.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * A native window that sits *behind the desktop icons*, for live wallpapers.
 *
 * The desktop is drawn by Explorer's "Progman" window. Sending it the undocumented
 * message 0x052C makes Explorer split the desktop into two layers: a "WorkerW" window
 * that paints the wallpaper, and the window that holds the icons ("SHELLDLL_DefView")
 * on top of it. A child window placed in that WorkerW is drawn above the wallpaper but
 * below the icons - exactly where a live wallpaper should be.
 *
 * - Windows 10 / Windows 11 up to 23H2: the wallpaper WorkerW is a top-level window
 *   directly behind the top-level window that contains SHELLDLL_DefView.
 * - Windows 11 24H2 and later: WorkerW and SHELLDLL_DefView are both children of
 *   Progman. We become a child of Progman too and put ourselves between them.
 *
 * Each window belongs to a dedicated thread that only runs a message loop. Windows links
 * the input of a child window's thread with its parent's (Explorer), so it must never block.
 */

private val log = logger("desktop-layer")
private val user32 = User32.INSTANCE

private const val SPAWN_WORKERW = 0x052C
private const val WS_CHILD = 0x40000000
private const val WS_VISIBLE = 0x10000000
private const val WS_CLIPSIBLINGS = 0x04000000
private const val WS_CLIPCHILDREN = 0x02000000
private const val WS_POPUP = 0x80000000.toInt()
private const val WS_EX_LAYERED = 0x00080000
private const val WS_EX_NOACTIVATE = 0x08000000
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val GW_HWNDPREV = 3
private const val LWA_ALPHA = 0x2
private const val WM_DESTROY = 0x0002
private const val WM_CLOSE = 0x0010
private const val BLACK_BRUSH = 4
private const val CLASS_NAME = "WallpaperManagerLiveHost"

private const val HELP_TEXT = "Live wallpapers could not be attached to the desktop on this PC. " +
    "Please save a diagnostics file (Settings → Diagnostics) and send it to the developer."

/** The desktop window layout is not what we expect (e.g. a new Windows version). */
class DesktopLayerException(detail: String) : RuntimeException(tr("{0}\n\n{1}", detail, HELP_TEXT))

/**
 * The window to attach to and, on 24H2+, the icon window to sit directly below (null for the
 * classic layout). [workerW] is the 24H2 wallpaper window inside Progman.
 */
data class WallpaperLayer(val parent: HWND, val insertAfter: HWND?, val workerW: HWND? = null)

fun findWallpaperLayer(): WallpaperLayer {
    val progman = user32.FindWindow("Progman", null)
        ?: throw DesktopLayerException(tr("Explorer's desktop window (Progman) was not found. Is Explorer running?"))
    val result = WinDef.DWORDByReference()
    user32.SendMessageTimeout(progman, SPAWN_WORKERW, WinDef.WPARAM(0xD), WinDef.LPARAM(0x1), 0, 1000, result)
    user32.SendMessageTimeout(progman, SPAWN_WORKERW, WinDef.WPARAM(0), WinDef.LPARAM(0), 0, 1000, result)

    // Windows 11 24H2+: WorkerW is a child of Progman, next to the icons.
    user32.FindWindowEx(progman, null, "WorkerW", null)?.let { workerW ->
        user32.FindWindowEx(progman, null, "SHELLDLL_DefView", null)?.let { defView ->
            log.fine("Desktop layout: 24H2 (WorkerW and icons inside Progman)")
            return WallpaperLayer(progman, defView, workerW)
        }
    }
    // Classic layout: the WorkerW that follows the top-level window holding the icons.
    var found: HWND? = null
    user32.EnumWindows({ hwnd, _ ->
        if (user32.FindWindowEx(hwnd, null, "SHELLDLL_DefView", null) != null) {
            user32.FindWindowEx(null, hwnd, "WorkerW", null)?.let { found = it }
        }
        true
    }, null)
    log.fine("Desktop layout: classic (top-level WorkerW behind the icons)")
    return WallpaperLayer(found ?: throw DesktopLayerException(tr("The desktop wallpaper layer (WorkerW) was not found.")), null)
}

fun className(hwnd: HWND): String {
    val buf = CharArray(128)
    user32.GetClassName(hwnd, buf, buf.size)
    return Native.toString(buf)
}

fun windowPid(hwnd: HWND): Int = IntByReference().also { user32.GetWindowThreadProcessId(hwnd, it) }.value

/** Executable name (e.g. "Lively.exe") of the process that owns a window. */
fun windowProcessName(hwnd: HWND): String {
    val k32 = Kernel32.INSTANCE
    val process = k32.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, windowPid(hwnd)) ?: return "another app"
    try {
        val buf = CharArray(512)
        val size = IntByReference(buf.size)
        if (!k32.QueryFullProcessImageName(process, 0, buf, size)) return "another app"
        return Native.toString(buf).substringAfterLast('\\')
    } finally {
        k32.CloseHandle(process)
    }
}

private fun children(parent: HWND): List<HWND> {
    val result = mutableListOf<HWND>()
    var child = user32.FindWindowEx(parent, null, null, null)
    while (child != null) {
        result += child
        child = user32.FindWindowEx(parent, child, null, null)
    }
    return result
}

/** Read-only description of Explorer's desktop windows, for diagnostics. */
fun describeDesktop(): List<String> {
    val lines = mutableListOf<String>()
    fun describe(hwnd: HWND, indent: String) {
        lines += "$indent${className(hwnd)}  hwnd=0x${java.lang.Long.toHexString(Pointer2.value(hwnd))}  " +
            "visible=${user32.IsWindowVisible(hwnd)}  process=${windowProcessName(hwnd)}"
    }
    user32.EnumWindows({ hwnd, _ ->
        if (className(hwnd) in setOf("Progman", "WorkerW")) {
            val kids = children(hwnd)
            if (kids.isNotEmpty() || user32.IsWindowVisible(hwnd)) { // skip Explorer's many empty helper windows
                describe(hwnd, "")
                kids.forEach { describe(it, "    ") }
            }
        }
        true
    }, null)
    return lines
}

internal object Pointer2 {
    fun value(hwnd: HWND): Long = com.sun.jna.Pointer.nativeValue(hwnd.pointer)
}

private const val GWL_STYLE = -16
private const val GWL_EXSTYLE = -20
private const val WS_CAPTION = 0x00C00000
private const val WS_THICKFRAME = 0x00040000
private const val WS_EX_APPWINDOW = 0x00040000
private const val SWP_FRAMECHANGED = 0x0020
private const val SWP_SHOWWINDOW = 0x0040
private const val SWP_HIDEWINDOW = 0x0080

/**
 * Moves another window (e.g. Chromium's) into the wallpaper layer as a child window covering
 * [screenRect]. On 24H2+ it goes inside the wallpaper WorkerW, which needs no layered style
 * (GPU-drawn children don't show in layered windows). Returns the new parent.
 */
fun attachToWallpaperLayer(hwnd: HWND, screenRect: ScreenRect, visible: Boolean = true): HWND {
    val layer = findWallpaperLayer()
    val parent = layer.workerW ?: layer.parent
    val style = user32.GetWindowLong(hwnd, GWL_STYLE)
    user32.SetWindowLong(hwnd, GWL_STYLE,
        (style and (WS_POPUP or WS_CAPTION or WS_THICKFRAME or WS_VISIBLE).inv()) or WS_CHILD or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or
            (if (visible) WS_VISIBLE else 0))
    val exStyle = user32.GetWindowLong(hwnd, GWL_EXSTYLE)
    user32.SetWindowLong(hwnd, GWL_EXSTYLE, (exStyle and WS_EX_APPWINDOW.inv()) or WS_EX_NOACTIVATE)
    user32.SetParent(hwnd, parent)
    val client = toClient(parent, screenRect)
    user32.SetWindowPos(hwnd, HWND(com.sun.jna.Pointer.createConstant(0)), client.x, client.y, client.width, client.height,
        SWP_NOACTIVATE or SWP_FRAMECHANGED or (if (visible) SWP_SHOWWINDOW else SWP_HIDEWINDOW))
    log.info("Window attached to the wallpaper layer at $screenRect (${if (layer.workerW != null) "24H2" else "classic"} layout)")
    return parent
}

/** Makes [child] a child window filling [parent] (e.g. a browser inside a preview area of our UI). */
fun embedWindow(child: HWND, parent: HWND) {
    val style = user32.GetWindowLong(child, GWL_STYLE)
    user32.SetWindowLong(child, GWL_STYLE, (style and (WS_POPUP or WS_CAPTION or WS_THICKFRAME).inv()) or WS_CHILD or WS_VISIBLE)
    user32.SetParent(child, parent)
    fitToParent(child, parent)
}

/** Resizes [child] to cover its parent's client area; returns that size. */
fun fitToParent(child: HWND, parent: HWND): Pair<Int, Int> {
    val rect = WinDef.RECT()
    user32.GetClientRect(parent, rect)
    val w = rect.right - rect.left
    val h = rect.bottom - rect.top
    user32.SetWindowPos(child, null, 0, 0, w, h, SWP_NOZORDER or SWP_NOACTIVATE or SWP_SHOWWINDOW)
    return w to h
}

/** Moves an attached child window to cover [screenRect]. */
fun moveInWallpaperLayer(hwnd: HWND, parent: HWND, screenRect: ScreenRect) {
    val client = toClient(parent, screenRect)
    user32.SetWindowPos(hwnd, null, client.x, client.y, client.width, client.height, SWP_NOZORDER or SWP_NOACTIVATE)
}

/** A window moved somewhere else for a while (see [lendWindow]); [giveBack] puts it where it was. */
class LentWindow internal constructor(
    val hwnd: HWND,
    private val parent: HWND,
    /** The sibling just above it, so it goes back to the same place in the z-order. */
    private val above: HWND?,
    private val rect: ScreenRect,
) {
    fun giveBack() {
        if (!user32.IsWindow(hwnd) || !user32.IsWindow(parent)) return
        user32.SetParent(hwnd, parent)
        val client = toClient(parent, rect)
        val after = above?.takeIf { user32.IsWindow(it) && user32.GetParent(it) == parent } ?: HWND(com.sun.jna.Pointer.createConstant(0))
        user32.SetWindowPos(hwnd, after, client.x, client.y, client.width, client.height, SWP_NOACTIVATE or SWP_SHOWWINDOW)
    }
}

/** Moves a child window (e.g. the live wallpaper's) into [to], filling it, until [LentWindow.giveBack]. */
fun lendWindow(hwnd: HWND, to: HWND): LentWindow? {
    val parent = user32.GetParent(hwnd) ?: return null
    val bounds = WinDef.RECT()
    if (!user32.GetWindowRect(hwnd, bounds)) return null
    val above = user32.GetWindow(hwnd, WinDef.DWORD(GW_HWNDPREV.toLong()))
    user32.SetParent(hwnd, to)
    fitToParent(hwnd, to)
    return LentWindow(hwnd, parent, above, ScreenRect.of(bounds))
}

/** Child windows of [parent] (recursively) with this class name. */
fun findChildWindows(parent: HWND, className: String): List<HWND> {
    val found = mutableListOf<HWND>()
    user32.EnumChildWindows(parent, { child, _ ->
        if (className(child) == className) found += child
        true
    }, null)
    return found
}

/** Converts a screen rectangle into [parent]'s client coordinates (the layer spans the virtual screen). */
internal fun toClient(parent: HWND, rect: ScreenRect): ScreenRect {
    val point = WinDef.POINT(rect.x, rect.y)
    User32Ex.INSTANCE.MapWindowPoints(null, parent, point, 1)
    return ScreenRect(point.x, point.y, rect.width, rect.height)
}

/**
 * Window class shared by our message-only threads. The window procedure forwards
 * messages to per-window handlers; returning null means "default handling".
 */
internal object HostWindowClass {
    private val handlers = ConcurrentHashMap<Long, (Int, WinDef.WPARAM, WinDef.LPARAM) -> WinDef.LRESULT?>()
    private var registered = false

    // Kept in a field so the JVM never garbage-collects the callback while Windows uses it.
    private val windowProc = WinUser.WindowProc { hwnd, msg, wParam, lParam ->
        handlers[Pointer2.value(hwnd)]?.invoke(msg, wParam, lParam)
            ?: when (msg) {
                WM_CLOSE -> { user32.DestroyWindow(hwnd); WinDef.LRESULT(0) }
                else -> user32.DefWindowProc(hwnd, msg, wParam, lParam)
            }
    }

    @Synchronized
    fun ensureRegistered() {
        if (registered) return
        val wc = WinUser.WNDCLASSEX().apply {
            lpfnWndProc = windowProc
            hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
            hbrBackground = Gdi32Ex.INSTANCE.GetStockObject(BLACK_BRUSH)
            lpszClassName = CLASS_NAME
        }
        if (user32.RegisterClassEx(wc) == null) throw IllegalStateException(tr("RegisterClassEx failed ({0})", Native.getLastError()))
        registered = true
    }

    fun create(exStyle: Int, title: String, style: Int, rect: ScreenRect, parent: HWND?): HWND {
        ensureRegistered()
        return user32.CreateWindowEx(exStyle, CLASS_NAME, title, style, rect.x, rect.y, rect.width, rect.height,
            parent, null, Kernel32.INSTANCE.GetModuleHandle(null), null)
            ?: throw IllegalStateException(tr("CreateWindowEx failed ({0})", Native.getLastError()))
    }

    fun handle(hwnd: HWND, handler: (Int, WinDef.WPARAM, WinDef.LPARAM) -> WinDef.LRESULT?) {
        handlers[Pointer2.value(hwnd)] = handler
    }

    fun forget(hwnd: HWND) {
        handlers.remove(Pointer2.value(hwnd))
    }
}

/** Runs [setup] on a new thread, then that thread's message loop until its window is destroyed. */
internal fun messageLoopThread(name: String, timeoutMs: Long, setup: () -> HWND): Pair<HWND, Thread> {
    val ready = CountDownLatch(1)
    var created: HWND? = null
    var error: Throwable? = null
    val t = thread(name = name, isDaemon = true) {
        try {
            created = setup()
        } catch (e: Throwable) {
            error = e
            return@thread
        } finally {
            ready.countDown()
        }
        val msg = WinUser.MSG()
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            user32.TranslateMessage(msg)
            user32.DispatchMessage(msg)
        }
    }
    if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException(tr("Timed out creating the {0} window", name))
    error?.let { throw it }
    return created!! to t
}

/** Owns one behind-the-icons window (covering [screenRect]) and the thread running its message loop. */
class DesktopHost(screenRect: ScreenRect) {
    var hwnd: HWND? = null
        private set
    private var parent: HWND? = null
    private var thread: Thread? = null
    private var rect: ScreenRect = screenRect
    private var clientRect: ScreenRect? = null

    /** The window handle as a number, for players that draw into it (mpv's "wid"). */
    val windowId: Long get() = Pointer2.value(hwnd ?: error("not started"))

    fun start(timeoutMs: Long = 5_000) {
        val (window, t) = messageLoopThread("desktop-host", timeoutMs) {
            val layer = findWallpaperLayer()
            val client = toClient(layer.parent, rect)
            val exStyle = WS_EX_NOACTIVATE or (if (layer.insertAfter != null) WS_EX_LAYERED else 0)
            val hwnd = HostWindowClass.create(exStyle, "Wallpaper Manager live wallpaper",
                WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS, client, layer.parent)
            HostWindowClass.handle(hwnd) { msg, _, _ ->
                if (msg == WM_DESTROY) { user32.PostQuitMessage(0); WinDef.LRESULT(0) } else null
            }
            if (layer.insertAfter != null) {
                // 24H2+: a layered (fully opaque) child placed just below the icon window.
                user32.SetLayeredWindowAttributes(hwnd, 0, 255.toByte(), LWA_ALPHA)
                user32.SetWindowPos(hwnd, layer.insertAfter, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE)
            } else {
                // New children go *below* existing siblings (e.g. another wallpaper app's player),
                // so move ours to the top of the wallpaper layer.
                user32.SetWindowPos(hwnd, HWND(com.sun.jna.Pointer.createConstant(0)), 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE)
            }
            parent = layer.parent
            clientRect = client
            log.info("Live wallpaper window attached at $rect (${if (layer.insertAfter != null) "24H2" else "classic"} layout)")
            hwnd
        }
        hwnd = window
        thread = t
    }

    /** False if Explorer was restarted (our window dies with its parent). */
    fun isAlive(): Boolean {
        val h = hwnd ?: return false
        return user32.IsWindow(h) && user32.GetParent(h) == parent
    }

    /** Follows resolution or layout changes. */
    fun moveTo(screenRect: ScreenRect) {
        val h = hwnd ?: return
        val p = parent ?: return
        if (!isAlive()) return
        val client = toClient(p, screenRect)
        if (client != clientRect || screenRect != rect) {
            user32.SetWindowPos(h, null, client.x, client.y, client.width, client.height, SWP_NOZORDER or SWP_NOACTIVATE)
            rect = screenRect
            clientRect = client
            log.info("Live wallpaper window moved to $screenRect")
        }
    }

    /** Other processes' windows in our layer, e.g. another live wallpaper app's player. */
    fun foreignWindows(): List<HWND> {
        val p = parent ?: return emptyList()
        if (!isAlive()) return emptyList()
        val own = Kernel32.INSTANCE.GetCurrentProcessId()
        return children(p).filter { it != hwnd && user32.IsWindowVisible(it) && windowPid(it) != own && className(it) != "SHELLDLL_DefView" }
    }

    fun close() {
        val h = hwnd ?: return
        if (user32.IsWindow(h)) user32.PostMessage(h, WM_CLOSE, null, null)
        thread?.join(3_000)
        HostWindowClass.forget(h)
        hwnd = null
        thread = null
    }
}

/**
 * Hidden window that receives "screen turned off/on" notifications (GUID_CONSOLE_DISPLAY_STATE).
 * Runs for the whole app lifetime, so the state is right even before a live wallpaper starts.
 */
object DisplayState {
    @Volatile var isOn: Boolean = true
        private set
    private var started = false
    private const val WM_POWERBROADCAST = 0x0218
    private const val PBT_POWERSETTINGCHANGE = 0x8013
    private val GUID_CONSOLE_DISPLAY_STATE = com.sun.jna.platform.win32.Guid.GUID.fromString("{6FE69556-704A-47A0-8F24-C28D936FDA47}")

    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching {
            messageLoopThread("display-state", 5_000) {
                val hwnd = HostWindowClass.create(0, "Wallpaper Manager power", WS_POPUP, ScreenRect(0, 0, 0, 0), null)
                HostWindowClass.handle(hwnd) { msg, wParam, lParam ->
                    if (msg == WM_POWERBROADCAST && wParam.toInt() == PBT_POWERSETTINGCHANGE && lParam.toLong() != 0L) {
                        // POWERBROADCAST_SETTING: GUID (16 bytes), DataLength (4), Data (4): 0 = off, 1 = on, 2 = dimmed.
                        val data = com.sun.jna.Pointer(lParam.toLong()).getInt(20)
                        isOn = data != 0
                        log.info("Screen is ${if (isOn) "on" else "off"}")
                        WinDef.LRESULT(1)
                    } else null
                }
                User32Ex.INSTANCE.RegisterPowerSettingNotification(WinNT.HANDLE(hwnd.pointer), GUID_CONSOLE_DISPLAY_STATE, 0)
                hwnd
            }
        }.onFailure { log.warning("Screen on/off notifications unavailable: $it") }
    }
}
