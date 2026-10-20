package wpm.engine

import wpm.logger
import wpm.win32.Monitor

private val log = logger("displays")

/**
 * Live wallpapers on several displays. Each display has its own player (and window behind
 * the icons). [play] shows a wallpaper on every display (or only the main one, see
 * [allDisplays]); [playOn] gives one display a wallpaper of its own.
 *
 * Pausing works on two levels: [pause]/[resume] for everything (the user, a locked PC) and
 * [hideDisplays] for displays covered by a fullscreen app or maximized windows.
 */
class MultiLive(
    private val displays: () -> List<Monitor>,
    private val factory: (Monitor) -> LivePlayer,
) : LivePlayer {
    private val players = LinkedHashMap<String, LivePlayer>()
    /** What each display shows, and whether it was chosen for that display alone. */
    private val shown = HashMap<String, Pair<LiveSource, Boolean>>()
    private var hidden = emptySet<String>()
    @Volatile private var allPaused = false

    /** Show "all displays" wallpapers on every display (true) or only the main one. */
    @Volatile var allDisplays: Boolean = true

    private fun player(display: Monitor): LivePlayer = players.getOrPut(display.id) { factory(display) }

    private fun primaryId(): String? = displays().firstOrNull { it.primary }?.id ?: displays().firstOrNull()?.id

    /** Players that exist, by display id (e.g. to find web engines for audio). */
    @Synchronized
    fun players(): Map<String, LivePlayer> = LinkedHashMap(players)

    /** The primary display's player, if it has one. */
    fun primaryPlayer(): LivePlayer? = primaryId()?.let { players[it] }

    override val active: Boolean get() = players.values.any { it.active }
    override val paused: Boolean get() = players.values.filter { it.active }.let { it.isNotEmpty() && it.all { p -> p.paused } }
    override val source: String? get() = primaryPlayer()?.source ?: players.values.firstNotNullOfOrNull { it.source }
    override val lastError: String? get() = players.values.firstNotNullOfOrNull { it.lastError }

    /** Shows [source] on every display that hasn't been given a wallpaper of its own. */
    @Synchronized
    override fun play(source: LiveSource) {
        val targets = displays().filter { allDisplays || it.primary }
        for (display in targets) {
            if (shown[display.id]?.second == true) continue // has its own wallpaper
            shown[display.id] = source to false
            player(display).play(source)
        }
        // Displays no longer covered by the "all displays" choice stop.
        val targetIds = targets.map { it.id }.toSet()
        shown.filter { (id, entry) -> !entry.second && id !in targetIds }.keys.forEach { stopOn(it) }
        allPaused = false
        applyPauses()
    }

    /** Gives one display a wallpaper of its own. */
    @Synchronized
    fun playOn(displayId: String, source: LiveSource) {
        val display = displays().firstOrNull { it.id == displayId } ?: return
        shown[displayId] = source to true
        player(display).play(source)
        applyPauses()
    }

    /** Stops the live wallpaper on one display (e.g. it now shows a picture). */
    @Synchronized
    fun stopOn(displayId: String) {
        shown.remove(displayId)
        players.remove(displayId)?.stop()
    }

    /** Removes "all displays" wallpapers but keeps the ones chosen for a single display. */
    @Synchronized
    fun stopShared() {
        shown.filter { !it.value.second }.keys.forEach(::stopOn)
    }

    override fun pause() {
        allPaused = true
        applyPauses()
    }

    override fun resume() {
        allPaused = false
        applyPauses()
    }

    /** Displays that nobody can see right now (fullscreen app or maximized windows on them). */
    @Synchronized
    fun hideDisplays(ids: Set<String>) {
        if (ids == hidden) return
        hidden = ids
        applyPauses()
    }

    private fun applyPauses() {
        for ((id, player) in players) {
            if (!player.active) continue
            val shouldPause = allPaused || id in hidden
            if (shouldPause && !player.paused) player.pause() else if (!shouldPause && player.paused) player.resume()
        }
    }

    override fun setMuted(muted: Boolean) = players.values.forEach { it.setMuted(muted) }
    override fun setFill(fill: Boolean) = players.values.forEach { it.setFill(fill) }
    override fun isHealthy(): Boolean = players.values.all { !it.active || it.isHealthy() }
    override fun competingApps(): List<String> = players.values.flatMap { it.competingApps() }.distinct().sorted()

    /** Follows display changes: new displays get the shared wallpaper, removed ones stop, others resize. */
    @Synchronized
    override fun fitToScreen() {
        val current = displays()
        val ids = current.map { it.id }.toSet()
        (players.keys - ids).forEach {
            log.info("Display $it was removed")
            stopOn(it)
        }
        val sharedSource = shown.values.firstOrNull { !it.second }?.first
        if (sharedSource != null) {
            current.filter { (allDisplays || it.primary) && it.id !in shown }.forEach { display ->
                log.info("Display ${display.id} was added; showing the wallpaper there too")
                shown[display.id] = sharedSource to false
                player(display).play(sharedSource)
            }
        }
        players.values.forEach { it.fitToScreen() }
        applyPauses()
    }

    @Synchronized
    override fun stop() {
        players.values.forEach { it.stop() }
        players.clear()
        shown.clear()
        hidden = emptySet()
        allPaused = false
    }

    /** Re-creates players whose window was lost (Explorer restarted), keeping what they showed. */
    @Synchronized
    fun recover() {
        for ((id, player) in players.toList()) {
            if (player.active && !player.isHealthy()) {
                val source = shown[id]?.first ?: continue
                player.stop()
                player.play(source)
            }
        }
        applyPauses()
    }
}
