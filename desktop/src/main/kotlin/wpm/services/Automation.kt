package wpm.services

import wpm.tr
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
    /** The program in front (e.g. "chrome.exe"), or null for the desktop, the taskbar or this app. */
    fun foregroundApp(): String? = null
    /** Programs running right now (lower case). */
    fun runningApps(): Set<String> = emptySet()
}

/** "Pause while chrome.exe is in front" ([whileRunning] = false) or "... is running at all". */
@kotlinx.serialization.Serializable
data class AppPauseRule(
    val exe: String,
    @kotlinx.serialization.SerialName("while_running") val whileRunning: Boolean = false,
)

val appRulesSerializer = kotlinx.serialization.builtins.ListSerializer(AppPauseRule.serializer())

fun appPauseRules(settings: Settings): List<AppPauseRule> = settings.obj("pause_apps", appRulesSerializer) ?: emptyList()

/** A full path, "game" or "Game.EXE" -> "game.exe". */
fun normalizeExe(text: String): String {
    val name = text.trim().trim('"').substringAfterLast('\\').substringAfterLast('/').lowercase()
    return if (name.isEmpty() || name.endsWith(".exe")) name else "$name.exe"
}

object WindowsSystemProbe : SystemProbe {
    override fun sessionLocked() = SystemState.sessionLocked()
    override fun screenOff() = !DisplayState.isOn
    override fun fullscreenDisplay() = SystemState.fullscreenMonitor()
    override fun coveredDisplays() = SystemState.coveredMonitors()
    override fun onBattery() = SystemState.onBattery()
    override fun displays() = Monitors.all()
    override fun foregroundApp() = SystemState.foregroundApp()
    override fun runningApps() = SystemState.runningApps()
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
    appPauseReason(settings, probe)?.let { return PauseDecision(it) }
    if (settings.boolean("pause_on_battery", false) && probe.onBattery()) return PauseDecision(PauseReason.Battery)
    return PauseDecision(null, hidden)
}

/** Pausing for apps: one of the user's app rules applies, or (optionally) any app is in front. */
private fun appPauseReason(settings: Settings, probe: SystemProbe): PauseReason? {
    val rules = appPauseRules(settings)
    val anyApp = settings.boolean("pause_when_app_focused", false)
    if (rules.isEmpty() && !anyApp) return null
    val front = probe.foregroundApp()
    if (front != null && (anyApp || rules.any { !it.whileRunning && it.exe == front })) return PauseReason.AppInFront
    val watched = rules.filter { it.whileRunning }.map { it.exe }
    if (watched.isNotEmpty() && probe.runningApps().any { it in watched }) return PauseReason.AppRunning
    return null
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
    private val lockSlideshow: LockScreenSlideshow? = null,
    /** The screensaver shows the live wallpaper: it is full screen, but must keep playing. */
    private val screensaverShowing: () -> Boolean = { false },
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
        // The lock screen slideshow (PowerShell, a second or two: not while other changes run).
        lockSlideshow?.let { slideshow ->
            every(30_000) {
                if (slideshow.isDue()) exclusive { runCatching { slideshow.advance() }.onFailure { log.warning("Lock screen slideshow: $it") } }
            }
        }
        scope.launch(Dispatchers.IO) {
            delay(15_000) // look for updates shortly after start, not during it
            while (isActive) {
                runCatching { updates.checkIfDue() }.getOrNull()?.let { version ->
                    if (settings.string("notified_version", "") != version) {
                        settings.set("notified_version", version)
                        notify(tr("Version {0} is available. Open Settings to install it.", version))
                    }
                }
                delay(3_600_000)
            }
        }
        every(2_000) {
            if (wallpapers.videoActive) {
                val decision = if (screensaverShowing()) PauseDecision(null)
                else runCatching { pauseDecision(settings, probe, liveDisplays()) }.getOrNull() ?: PauseDecision(null)
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
