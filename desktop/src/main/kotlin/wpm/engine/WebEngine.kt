package wpm.engine

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import wpm.logger
import wpm.win32.Monitors
import wpm.win32.ScreenRect
import wpm.win32.attachToWallpaperLayer
import wpm.win32.findChildWindows
import wpm.win32.moveInWallpaperLayer
import wpm.win32.windowPid
import wpm.win32.windowProcessName
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.io.path.exists

private val log = logger("web")

/** Chromium (via JCEF), started once per app run. The first start downloads it (~150 MB). */
object Chromium {
    private var app: CefApp? = null

    /** Installed already, so starting it doesn't download anything. */
    fun installed(installDir: Path): Boolean = installDir.resolve("install.lock").exists()

    @Synchronized
    fun get(installDir: Path, muted: Boolean, progress: (String) -> Unit = {}): CefApp {
        app?.let { return it }
        Files.createDirectories(installDir)
        val builder = CefAppBuilder().apply {
            setInstallDir(installDir.toFile())
            setProgressHandler { state, percent ->
                when (state) {
                    EnumProgress.DOWNLOADING -> progress("Downloading the web engine… ${percent.toInt().coerceAtLeast(0)}%")
                    EnumProgress.EXTRACTING -> progress("Unpacking the web engine…")
                    EnumProgress.INITIALIZING -> progress("Starting the web engine…")
                    else -> {}
                }
            }
            cefSettings.windowless_rendering_enabled = false
            cefSettings.cache_path = installDir.resolve("profile").toString()
            cefSettings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
            addJcefArgs(
                "--autoplay-policy=no-user-gesture-required", // wallpapers start their own videos and animations
                "--disable-features=CalculateNativeWinOcclusion", // we pause ourselves; Windows' occlusion would stop rendering
                "--disable-background-timer-throttling",
            )
            if (muted) addJcefArgs("--mute-audio")
        }
        return builder.build().also {
            app = it
            log.info("Chromium ${it.version?.chromeVersion ?: "?"} started")
        }
    }

    fun version(): String? = app?.version?.chromeVersion

    /** Ends Chromium's helper processes when the app exits. */
    @Synchronized
    fun shutdown() {
        val running = app ?: return
        app = null
        runCatching { SwingUtilities.invokeAndWait { running.dispose() } }.onFailure { log.warning("Chromium shutdown: $it") }
    }
}

/**
 * A web page behind the desktop icons. Chromium draws into an AWT window that is moved into
 * the wallpaper layer (the same layer the video window uses); mouse input over the empty
 * desktop is forwarded to it by [InputForwarder].
 */
class WebLivePlayer(
    private val installDir: Path,
    private val onStatus: (String?) -> Unit = {},
    /** The display area this player covers (looked up again when displays change). */
    private val area: () -> ScreenRect = { Monitors.primary().bounds },
) : LivePlayer {
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var hwnd: HWND? = null
    private var parent: HWND? = null
    private var rect: ScreenRect? = null
    private var forwarder: InputForwarder? = null
    @Volatile private var interactive = false
    /** The page wants system audio levels (an audio-reactive wallpaper). */
    @Volatile var wantsAudio = false
        private set
    @Volatile override var paused: Boolean = false
        private set
    @Volatile override var source: String? = null
        private set
    @Volatile override var lastError: String? = null
        private set
    /** The page's <title>, as Chromium reports it. */
    @Volatile var pageTitle: String? = null
        private set

    override val active: Boolean get() = browser != null

    /** Runs JavaScript in the page (e.g. to deliver audio levels). Safe to call from any thread. */
    fun runScript(code: String) {
        browser?.executeJavaScript(code, source ?: "", 0)
    }

    /**
     * Sends audio levels to the page: Lively's livelyAudioListener(array) if the page defines it,
     * otherwise wpm.onAudio(array). Values are 0..1, bass first.
     */
    fun deliverAudio(levels: FloatArray) {
        if (browser == null || paused) return
        val values = levels.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) }
        runScript("(window.livelyAudioListener||(window.wpm&&window.wpm.onAudio)||function(){})([$values]);")
    }

    @Synchronized
    override fun play(source: LiveSource) {
        lastError = null
        val app = try {
            Chromium.get(installDir, source.muted) { onStatus(it) }
        } finally {
            onStatus(null)
        }
        if (browser == null || !isHealthy()) {
            stopLocked()
            create(app, source.target)
        } else {
            onEdt { browser?.loadURL(source.target) }
        }
        this.source = source.target
        interactive = source.interactive
        wantsAudio = source.audio
        forwarder?.enabled = interactive
        resume()
        log.info("Web wallpaper showing ${source.target}")
    }

    private fun create(app: CefApp, url: String) {
        val monitor = area()
        val opened = openBrowserWindow(app, url,
            onTitle = { pageTitle = it },
            onError = { error, failed ->
                lastError = error
                log.warning("Web wallpaper couldn't load $failed: $error")
            })
        client = opened.client
        browser = opened.browser
        hwnd = opened.hwnd
        parent = onEdt { attachToWallpaperLayer(opened.hwnd, monitor) }
        fillChildren(opened.hwnd, monitor.width, monitor.height)
        rect = monitor
        forwarder = InputForwarder(monitor) { renderWidget() }.also {
            it.enabled = interactive
            it.start()
        }
    }

    /** Chromium's input window, which receives the forwarded mouse messages. */
    private fun renderWidget(): HWND? = hwnd?.let { findChildWindows(it, "Chrome_RenderWidgetHostHWND").firstOrNull() }

    /** What the page shows right now (for thumbnails), or null. */
    fun snapshot(): java.awt.image.BufferedImage? = hwnd?.let { wpm.win32.WindowCapture.capture(it) }

    /** For diagnostics and tests: Chromium has created its input window. */
    fun hasRenderWidget(): Boolean = renderWidget() != null

    /** Tests: posts a click at window coordinates the same way [InputForwarder] does. */
    internal fun postClick(x: Int, y: Int) {
        val widget = renderWidget() ?: return
        val pos = com.sun.jna.platform.win32.WinDef.LPARAM(((y shl 16) or (x and 0xFFFF)).toLong())
        User32.INSTANCE.PostMessage(widget, 0x0201, com.sun.jna.platform.win32.WinDef.WPARAM(1), pos)
        User32.INSTANCE.PostMessage(widget, 0x0202, com.sun.jna.platform.win32.WinDef.WPARAM(0), pos)
    }

    /** For diagnostics: class names of the child windows Chromium created. */
    fun childWindowClasses(): List<String> {
        val h = hwnd ?: return emptyList()
        val names = mutableListOf<String>()
        User32.INSTANCE.EnumChildWindows(h, { child, _ -> names += wpm.win32.className(child); true }, null)
        return names
    }

    override fun pause() {
        paused = true
        forwarder?.enabled = false
        // Hidden pages stop drawing and throttle their timers (document.visibilityState = "hidden").
        // Hiding our window too shows the normal desktop picture instead of a black area.
        browser?.let { b -> onEdt { b.setWindowVisibility(false) } }
        hwnd?.let { User32.INSTANCE.ShowWindow(it, User32.SW_HIDE) }
    }

    override fun resume() {
        paused = false
        forwarder?.enabled = interactive
        hwnd?.let { User32.INSTANCE.ShowWindow(it, User32.SW_SHOWNA) }
        browser?.let { b -> onEdt { b.setWindowVisibility(true) } }
    }

    fun setInteractive(on: Boolean) {
        interactive = on
        forwarder?.enabled = on && !paused
    }

    override fun setMuted(muted: Boolean) {} // decided when Chromium starts (Settings)
    override fun setFill(fill: Boolean) {} // pages lay themselves out

    override fun isHealthy(): Boolean {
        val h = hwnd ?: return browser == null
        return User32.INSTANCE.IsWindow(h) && User32.INSTANCE.GetParent(h) == parent
    }

    override fun competingApps(): List<String> {
        val p = parent ?: return emptyList()
        val own = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
        val result = mutableListOf<String>()
        var child = User32.INSTANCE.FindWindowEx(p, null, null, null)
        while (child != null) {
            if (child != hwnd && User32.INSTANCE.IsWindowVisible(child) && windowPid(child) != own &&
                wpm.win32.className(child) != "SHELLDLL_DefView") {
                result += windowProcessName(child)
            }
            child = User32.INSTANCE.FindWindowEx(p, child, null, null)
        }
        return result.distinct().sorted()
    }

    @Synchronized
    override fun fitToScreen() {
        val monitor = area()
        val h = hwnd ?: return
        val p = parent ?: return
        if (monitor != rect && isHealthy()) {
            moveInWallpaperLayer(h, p, monitor)
            fillChildren(h, monitor.width, monitor.height)
            rect = monitor
            forwarder?.monitor = monitor
        }
    }

    @Synchronized
    override fun stop() = stopLocked()

    private fun stopLocked() {
        forwarder?.stop()
        forwarder = null
        val b = browser
        val c = client
        browser = null
        client = null
        hwnd = null
        parent = null
        if (b != null) {
            onEdt {
                b.close(true)
                c?.dispose()
            }
            log.info("Web wallpaper stopped")
        }
        source = null
        paused = false
    }
}

internal fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}

/** A Chromium browser and the native window it draws into. */
class BrowserWindow(val client: CefClient, val browser: CefBrowser, val hwnd: HWND) {
    fun close() = onEdt {
        browser.close(true)
        client.dispose()
    }
}

/**
 * Opens a page in a new Chromium window that isn't inside any AWT component. JCEF parks such
 * browsers in a hidden holder window; we find Chromium's window there so the caller can put it
 * where it wants (behind the desktop icons, or inside a preview area).
 */
fun openBrowserWindow(app: CefApp, url: String, onTitle: (String?) -> Unit = {}, onError: (String, String?) -> Unit = { _, _ -> }): BrowserWindow {
    val (client, browser, before) = onEdt {
        val client = app.createClient()
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(b: CefBrowser?, frame: CefFrame?, code: CefLoadHandler.ErrorCode?, text: String?, failed: String?) {
                if (frame?.isMain == true && code != CefLoadHandler.ErrorCode.ERR_ABORTED) onError(text?.ifBlank { null } ?: code.toString(), failed)
            }
        })
        client.addDisplayHandler(object : org.cef.handler.CefDisplayHandlerAdapter() {
            override fun onTitleChange(b: CefBrowser?, title: String?) = onTitle(title)
        })
        val before = cefBrowserWindows()
        val browser = client.createBrowser(url, false, false)
        browser.createImmediately()
        Triple(client, browser, before)
    }
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        (cefBrowserWindows() - before).firstOrNull()?.let { return BrowserWindow(client, browser, HWND(com.sun.jna.Pointer(it))) }
        Thread.sleep(50)
    }
    onEdt { browser.close(true); client.dispose() }
    throw IllegalStateException("The web engine didn't create its window")
}

/** Chromium's browser windows in this process (JCEF's "CefBrowserWindow"), as handle values. */
private fun cefBrowserWindows(): Set<Long> {
    val own = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
    val found = mutableSetOf<Long>()
    User32.INSTANCE.EnumWindows({ top, _ ->
        if (windowPid(top) == own) {
            findChildWindows(top, "CefBrowserWindow").forEach { found += com.sun.jna.Pointer.nativeValue(it.pointer) }
        }
        true
    }, null)
    return found
}

/** Sizes a browser window's direct children (Chromium's own window) to fill it. */
fun fillChildren(handle: HWND, width: Int, height: Int) {
    var child = User32.INSTANCE.FindWindowEx(handle, null, null, null)
    while (child != null) {
        User32.INSTANCE.SetWindowPos(child, null, 0, 0, width, height, 0x0004 or 0x0010) // NOZORDER | NOACTIVATE
        child = User32.INSTANCE.FindWindowEx(handle, child, null, null)
    }
}
