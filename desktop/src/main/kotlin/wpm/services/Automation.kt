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
import wpm.win32.Monitor
import wpm.win32.ScreenRect
import wpm.win32.SystemState

private val log = logger("automation")

/** Where the auto-pause checks get their information; a fake in tests. */
interface SystemProbe {
    fun sessionLocked(): Boolean
    fun screenOff(): Boolean
    /** The display a game or video runs full screen on, if any. */
    fun fullscreenDisplay(): ScreenRect?
    /** Displays whose desktop is hidden by maximized windows. */
    fun coveredDisplays(): Set<ScreenRect>
    fun onBattery(): Boolean
    fun displays(): List<Monitor>
}

object WindowsSystemProbe : SystemProbe {
    override fun sessionLocked() = SystemState.sessionLocked()
    override fun screenOff() = !DisplayState.isOn
    override fun fullscreenDisplay() = SystemState.fullscreenMonitor()
    override fun coveredDisplays() = SystemState.coveredMonitors()
    override fun onBattery() = SystemState.onBattery()
    override fun displays() = Monitors.all()
}

/** What the auto-pause check decided: a reason for pausing everything, and displays nobody can see. */
data class PauseDecision(val reason: PauseReason?, val hiddenDisplays: Set<String> = emptySet())

/**
 * Why live wallpapers should be paused right now (most important first). A fullscreen app or
 * maximized windows pause everything only when they hide every display showing a live
 * wallpaper ([liveDisplays]); otherwise just the hidden displays pause.
 */
fun pauseDecision(settings: Settings, probe: SystemProbe, liveDisplays: Set<String>): PauseDecision {
    if (settings.boolean("pause_when_hidden", true)) {
        if (probe.sessionLocked()) return PauseDecision(PauseReason.Locked)
        if (probe.screenOff()) return PauseDecision(PauseReason.ScreenOff)
    }
    val displays = probe.displays()
    fun idsOf(rects: Set<ScreenRect>) = displays.filter { it.bounds in rects }.map { it.id }.toSet()
    val fullscreen = if (settings.boolean("pause_on_fullscreen", true)) idsOf(setOfNotNull(probe.fullscreenDisplay())) else emptySet()
    val covered = if (settings.boolean("pause_when_covered", true)) idsOf(probe.coveredDisplays()) else emptySet()
    val hidden = fullscreen + covered
    val showing = liveDisplays.ifEmpty { displays.filter { it.primary }.map { it.id }.toSet() }
    if (showing.isNotEmpty() && hidden.containsAll(showing)) {
        return PauseDecision(if (showing.any { it in fullscreen }) PauseReason.Fullscreen else PauseReason.Covered)
    }
    if (settings.boolean("pause_on_battery", false) && probe.onBattery()) return PauseDecision(PauseReason.Battery)
    return PauseDecision(null, hidden)
}

/** Why live wallpapers should be paused everywhere right now, or null (single display, as in 1.x). */
fun pauseReason(settings: Settings, probe: SystemProbe): PauseReason? = pauseDecision(settings, probe, emptySet()).reason

/**
 * Background jobs while the app runs:
 * - start the saved live wallpaper after launch, and keep its window alive (watchdog)
 * - apply due schedule rules and advance the running playlist
 * - pause the live wallpaper while nobody can see it, and resume it afterwards
 * - check for a newer release once a day
 */
class Automation(
    private val scope: CoroutineScope,
    private val wallpapers: WallpaperService,
    private val playlists: PlaylistPlayer,
    private val scheduler: Scheduler,
    private val updates: UpdateChecker,
    private val settings: Settings,
    private val probe: SystemProbe,
    /** Runs a wallpaper change exclusively (shared with the UI) and refreshes the pages. */
    private val exclusive: suspend (() -> Unit) -> Unit,
    /** Shows a tray notification. */
    private val notify: (String) -> Unit,
    /** Displays that currently show a live wallpaper. */
    private val liveDisplays: () -> Set<String> = { emptySet() },
    /** Called once the saved wallpaper has been restored (or that failed). */
    private val onStarted: () -> Unit = {},
) {
    fun start() {
        DisplayState.start()
        scope.launch(Dispatchers.IO) {
            delay(500)
            runCatching { exclusive { wallpapers.restoreOnStartup() } }
                .onFailure { log.warning("Could not restore the live wallpaper: $it") }
            onStarted()
        }
        every(3_000) {
            if (wallpapers.videoActive) exclusive { wallpapers.checkLive() }
        }
        // Schedule and playlist changes, one at a time (the schedule wins when both are due).
        every(5_000) {
            when {
                scheduler.isDue() -> exclusive { runCatching { scheduler.applyDue() }.onFailure { log.warning("Schedule change failed: $it") } }
                playlists.isDue() -> exclusive { runCatching { playlists.advance() }.onFailure { log.warning("Playlist change failed: $it") } }
            }
        }
        scope.launch(Dispatchers.IO) {
            delay(15_000) // look for updates shortly after start, not during it
            while (isActive) {
                runCatching { updates.checkIfDue() }.getOrNull()?.let { version ->
                    if (settings.string("notified_version", "") != version) {
                        settings.set("notified_version", version)
                        notify("Version $version is available. Open Settings to download it.")
                    }
                }
                delay(3_600_000)
            }
        }
        every(2_000) {
            if (wallpapers.videoActive) {
                val decision = runCatching { pauseDecision(settings, probe, liveDisplays()) }.getOrNull() ?: PauseDecision(null)
                wallpapers.hideDisplays(decision.hiddenDisplays)
                if (wallpapers.updateAutoPause(decision.reason)) exclusive {}
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
