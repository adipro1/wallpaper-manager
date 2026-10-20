package wpm.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wpm.data.Settings
import wpm.logger
import wpm.win32.DisplayState
import wpm.win32.Monitors
import wpm.win32.SystemState

private val log = logger("automation")

/** Where the auto-pause checks get their information; a fake in tests. */
interface SystemProbe {
    fun sessionLocked(): Boolean
    fun screenOff(): Boolean
    fun fullscreenOnWallpaperMonitor(): Boolean
    fun wallpaperMonitorCovered(): Boolean
    fun onBattery(): Boolean
}

object WindowsSystemProbe : SystemProbe {
    override fun sessionLocked() = SystemState.sessionLocked()
    override fun screenOff() = !DisplayState.isOn
    override fun fullscreenOnWallpaperMonitor() = SystemState.fullscreenMonitor() == Monitors.primary().bounds
    override fun wallpaperMonitorCovered() = Monitors.primary().bounds in SystemState.coveredMonitors()
    override fun onBattery() = SystemState.onBattery()
}

/** Why the live wallpaper should be paused right now (most important first), or null. */
fun pauseReason(settings: Settings, probe: SystemProbe): PauseReason? {
    if (settings.boolean("pause_when_hidden", true)) {
        if (probe.sessionLocked()) return PauseReason.Locked
        if (probe.screenOff()) return PauseReason.ScreenOff
    }
    if (settings.boolean("pause_on_fullscreen", true) && probe.fullscreenOnWallpaperMonitor()) return PauseReason.Fullscreen
    if (settings.boolean("pause_when_covered", true) && probe.wallpaperMonitorCovered()) return PauseReason.Covered
    if (settings.boolean("pause_on_battery", false) && probe.onBattery()) return PauseReason.Battery
    return null
}

/**
 * Background jobs while the app runs:
 * - start the saved live wallpaper after launch, and keep its window alive (watchdog)
 * - pause the live wallpaper while nobody can see it, and resume it afterwards
 */
class Automation(
    private val scope: CoroutineScope,
    private val wallpapers: WallpaperService,
    private val settings: Settings,
    private val probe: SystemProbe,
    /** Runs a wallpaper change exclusively (shared with the UI) and refreshes the pages. */
    private val exclusive: suspend (() -> Unit) -> Unit,
) {
    fun start() {
        DisplayState.start()
        scope.launch(Dispatchers.IO) {
            delay(500)
            runCatching { exclusive { wallpapers.restoreOnStartup() } }
                .onFailure { log.warning("Could not restore the live wallpaper: $it") }
        }
        every(3_000) {
            if (wallpapers.videoActive) exclusive { wallpapers.checkLive() }
        }
        every(2_000) {
            if (wallpapers.videoActive) {
                val reason = runCatching { pauseReason(settings, probe) }.getOrNull()
                if (wallpapers.updateAutoPause(reason)) exclusive {}
            }
        }
    }

    private fun every(periodMs: Long, job: suspend () -> Unit) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(periodMs)
            try {
                job()
            } catch (e: Exception) { // keep the job running
                log.warning("Background job failed: $e")
            }
        }
    }
}
