package wpm

import wpm.engine.AudioCapture
import wpm.engine.Chromium
import wpm.engine.openBrowserWindow
import wpm.media.YtDlp
import wpm.mpv.Mpv
import wpm.mpv.MpvPlayer
import wpm.mpv.NativeBin
import wpm.win32.DesktopWallpaperCom
import wpm.win32.Monitors
import wpm.win32.findWallpaperLayer
import java.util.concurrent.atomic.AtomicInteger

/**
 * `WallpaperManager.exe --self-test`: checks that every engine works on this PC (and in the
 * packaged app) without changing anything visible, then exits. Results go to the log.
 */
fun runSelfTest(paths: AppPaths): Boolean {
    val log = logger("self-test")
    var ok = true
    fun check(name: String, block: () -> String) {
        val result = runCatching(block)
        result.onSuccess { log.info("PASS $name: $it") }.onFailure {
            ok = false
            log.warning("FAIL $name: $it")
        }
    }
    check("displays") { Monitors.all().joinToString { "${it.label} ${it.bounds.width}x${it.bounds.height}" } }
    check("desktop layer") { findWallpaperLayer().let { if (it.workerW != null) "24H2 layout" else "classic layout" } }
    check("IDesktopWallpaper") { "${DesktopWallpaperCom.displays().size} display(s)" }
    check("libmpv") {
        MpvPlayer(mapOf("vo" to "null", "ao" to "null", "idle" to "yes", "config" to "no")).use { it["mpv-version"] ?: "?" }
    }
    check("yt-dlp") { YtDlp(NativeBin.dir.resolve("yt-dlp.exe")).version() }
    check("audio capture") {
        val updates = AtomicInteger()
        val capture = AudioCapture { updates.incrementAndGet() }
        capture.start()
        Thread.sleep(1_500)
        capture.stop()
        check(updates.get() > 10) { "only ${updates.get()} updates" }
        "${updates.get()} updates in 1.5 s"
    }
    check("Chromium") {
        val app = Chromium.get(paths.dataDir.resolve("chromium"), muted = true) { log.info(it) }
        val window = openBrowserWindow(app, "about:blank")
        window.close()
        "Chromium ${Chromium.version()}"
    }
    log.info(if (ok) "Self-test passed" else "Self-test found problems")
    Chromium.shutdown()
    return ok && Mpv.available
}
