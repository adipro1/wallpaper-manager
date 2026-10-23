package wpm.engine

import wpm.logger
import wpm.mpv.Mpv
import wpm.mpv.MpvPlayer
import wpm.mpv.NativeBin
import wpm.win32.DesktopHost
import wpm.win32.Monitors
import wpm.win32.ScreenRect
import wpm.win32.windowProcessName

enum class LiveKind { Video, Web }

/** What a live wallpaper shows: a video file or stream, or a web page (also used for shaders). */
data class LiveSource(
    val kind: LiveKind,
    /** File path or URL. */
    val target: String,
    val fill: Boolean = true,
    val muted: Boolean = true,
    /** Web pages: pass mouse input from the desktop through to the page. */
    val interactive: Boolean = false,
    /** Web pages: send system audio levels to the page. */
    val audio: Boolean = false,
    /** Web pages: JavaScript run each time the page has loaded (e.g. the wallpaper's settings). */
    val onLoadScript: String? = null,
    /** Web pages: keep the window hidden (still drawing) until resume() shows it. */
    val startHidden: Boolean = false,
)

/** One live wallpaper behind the desktop icons. An interface so tests can use a fake. */
interface LivePlayer {
    val active: Boolean
    val paused: Boolean
    /** What is playing: a file path or URL. */
    val source: String?
    /** Why the last source couldn't be played (e.g. a stream is offline), or null. */
    val lastError: String?

    /** Starts (or switches to) a wallpaper. Switching within the same engine reuses its window, so there is no flicker. */
    fun play(source: LiveSource)
    fun pause()
    fun resume()
    fun setMuted(muted: Boolean)
    fun setFill(fill: Boolean)
    /** False when Explorer restarted and took our window with it. */
    fun isHealthy(): Boolean
    /** Names of other apps (e.g. Lively.exe) drawing in the same desktop layer. */
    fun competingApps(): List<String>
    /** Follows resolution changes. */
    fun fitToScreen()
    fun stop()
    /** The window the wallpaper is drawn in (e.g. to lend it to the screensaver), or null. */
    val window: com.sun.jna.platform.win32.WinDef.HWND? get() = null
}

/**
 * Video playback with libmpv inside a [DesktopHost] window. mpv decodes on the GPU
 * where possible and draws straight into the window, so a looping 1080p video costs
 * very little CPU. Thread-safe: play/stop run on workers while the watchdog checks health.
 */
class MpvLivePlayer(
    /** The display area this player covers (looked up again when displays change). */
    private val area: () -> ScreenRect = { Monitors.primary().bounds },
) : LivePlayer {
    private val log = logger("live")
    private var host: DesktopHost? = null
    private var mpv: MpvPlayer? = null
    @Volatile override var source: String? = null
        private set
    @Volatile override var lastError: String? = null
        private set

    override val active: Boolean get() = mpv != null
    override val paused: Boolean get() = mpv?.get("pause") == "yes"

    @Synchronized
    override fun play(source: LiveSource) {
        if (mpv == null || host?.isAlive() != true) create()
        val player = mpv!!
        player["panscan"] = if (source.fill) "1.0" else "0.0" // 1.0 = crop to fill the screen, 0 = letterbox
        player["mute"] = if (source.muted) "yes" else "no"
        player["pause"] = "no"
        lastError = null
        player.command("loadfile", source.target)
        this.source = source.target
        log.info("Live wallpaper playing ${source.target}")
    }

    private fun create() {
        stopLocked()
        val monitor = area()
        val newHost = DesktopHost(monitor)
        newHost.start()
        try {
            mpv = MpvPlayer(
                mapOf(
                    "wid" to newHost.windowId.toString(), // draw into our behind-the-icons window
                    "loop-file" to "inf",
                    "mute" to "yes",
                    "hwdec" to "auto-safe", // GPU video decoding when available
                    "keepaspect" to "yes",
                    "osc" to "no", "osd-level" to "0", // no on-screen controls or messages
                    "input-default-bindings" to "no", "input-vo-keyboard" to "no", "input-cursor" to "no",
                    "cursor-autohide" to "always",
                    "idle" to "yes", "config" to "no", "terminal" to "no",
                    // Video pages (YouTube etc.) through yt-dlp, never fetching more than the screen can show.
                    "ytdl" to "yes",
                    "script-opts" to "ytdl_hook-ytdl_path=${NativeBin.dir.resolve("yt-dlp.exe").toAbsolutePath()}",
                    "ytdl-format" to "bv*[height<=?${monitor.height}]+ba/b[height<=?${monitor.height}]/bv*+ba/b",
                    "cache" to "yes", "demuxer-max-bytes" to "150MiB", "demuxer-max-back-bytes" to "50MiB",
                ),
            ).also { player ->
                player.startEventThread("mpv-live") { event ->
                    if (event.id == Mpv.EVENT_END_FILE && event.endReason == Mpv.END_ERROR) {
                        lastError = player.errorString(event.endError)
                        log.warning("Live wallpaper couldn't play $source: $lastError")
                    }
                }
            }
            host = newHost
        } catch (e: Exception) {
            newHost.close()
            throw e
        }
    }

    override fun pause() { mpv?.set("pause", "yes") }
    override fun resume() { mpv?.set("pause", "no") }
    override fun setMuted(muted: Boolean) { mpv?.set("mute", if (muted) "yes" else "no") }
    override fun setFill(fill: Boolean) { mpv?.set("panscan", if (fill) "1.0" else "0.0") }

    override fun isHealthy(): Boolean = mpv == null || host?.isAlive() == true
    override val window get() = host?.hwnd

    override fun competingApps(): List<String> =
        host?.foreignWindows()?.map(::windowProcessName)?.distinct()?.sorted() ?: emptyList()

    @Synchronized
    override fun fitToScreen() {
        host?.moveTo(area())
    }

    @Synchronized
    override fun stop() = stopLocked()

    private fun stopLocked() {
        mpv?.let {
            runCatching { it.close() }.onFailure { e -> log.warning("Error while stopping mpv: $e") }
            mpv = null
        }
        host?.close()
        host = null
        if (source != null) log.info("Live wallpaper stopped")
        source = null
    }

    companion object {
        val available: Boolean get() = Mpv.available
    }
}

/**
 * Routes each wallpaper to the engine that can show it (mpv for videos, Chromium for web
 * pages and shaders) and stops the other one, so only one live wallpaper runs at a time.
 * The web engine is created on first use (it may have to download Chromium).
 */
class LiveRouter(private val video: LivePlayer, private val webFactory: () -> LivePlayer) : LivePlayer {
    /** The web engine, once it has been needed. */
    var web: LivePlayer? = null
        private set
    @Volatile private var current: LivePlayer? = null

    private fun engineFor(kind: LiveKind): LivePlayer = when (kind) {
        LiveKind.Video -> video
        LiveKind.Web -> web ?: webFactory().also { web = it }
    }

    override val active: Boolean get() = current?.active == true
    override val paused: Boolean get() = current?.paused == true
    override val source: String? get() = current?.source
    override val lastError: String? get() = current?.lastError
    /** Which engine is showing the wallpaper, if any. */
    val kind: LiveKind? get() = current?.let { if (it === video) LiveKind.Video else LiveKind.Web }

    @Synchronized
    override fun play(source: LiveSource) {
        val engine = engineFor(source.kind)
        if (current != null && current !== engine) current?.stop()
        current = engine
        engine.play(source)
    }

    override fun pause() { current?.pause() }
    override fun resume() { current?.resume() }
    override fun setMuted(muted: Boolean) { current?.setMuted(muted) }
    override fun setFill(fill: Boolean) { current?.setFill(fill) }
    override fun isHealthy(): Boolean = current?.isHealthy() ?: true
    override fun competingApps(): List<String> = current?.competingApps() ?: emptyList()
    override fun fitToScreen() { current?.fitToScreen() }
    override val window get() = current?.window

    @Synchronized
    override fun stop() {
        current?.stop()
        current = null
    }
}
