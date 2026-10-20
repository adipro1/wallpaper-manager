package wpm.engine

import wpm.logger
import wpm.mpv.Mpv
import wpm.mpv.MpvPlayer
import wpm.win32.DesktopHost
import wpm.win32.Monitors
import wpm.win32.windowProcessName

/** One live wallpaper behind the desktop icons. An interface so tests can use a fake. */
interface LivePlayer {
    val active: Boolean
    val paused: Boolean
    /** What is playing: a file path or URL. */
    val source: String?

    /** Starts (or switches to) a video. Switching reuses the window, so there is no flicker. */
    fun play(source: String, fill: Boolean, muted: Boolean)
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
}

/**
 * Video playback with libmpv inside a [DesktopHost] window. mpv decodes on the GPU
 * where possible and draws straight into the window, so a looping 1080p video costs
 * very little CPU. Thread-safe: play/stop run on workers while the watchdog checks health.
 */
class MpvLivePlayer : LivePlayer {
    private val log = logger("live")
    private var host: DesktopHost? = null
    private var mpv: MpvPlayer? = null
    @Volatile override var source: String? = null
        private set

    override val active: Boolean get() = mpv != null
    override val paused: Boolean get() = mpv?.get("pause") == "yes"

    @Synchronized
    override fun play(source: String, fill: Boolean, muted: Boolean) {
        if (mpv == null || host?.isAlive() != true) create()
        val player = mpv!!
        player["panscan"] = if (fill) "1.0" else "0.0" // 1.0 = crop to fill the screen, 0 = letterbox
        player["mute"] = if (muted) "yes" else "no"
        player["pause"] = "no"
        player.command("loadfile", source)
        this.source = source
        log.info("Live wallpaper playing $source")
    }

    private fun create() {
        stopLocked()
        val newHost = DesktopHost(Monitors.primary().bounds)
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
                    "idle" to "yes", "config" to "no", "terminal" to "no", "ytdl" to "no",
                ),
            ).also { it.startEventThread("mpv-live") }
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

    override fun competingApps(): List<String> =
        host?.foreignWindows()?.map(::windowProcessName)?.distinct()?.sorted() ?: emptyList()

    @Synchronized
    override fun fitToScreen() {
        host?.moveTo(Monitors.primary().bounds)
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
