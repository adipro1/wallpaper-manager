package wpm.engine

import wpm.logger
import wpm.win32.Monitor
import java.nio.file.Path

private val log = logger("transitions")

/**
 * Plays a transition page (see `Shaders.transitionPage`) over the desktop on every display, then
 * lets Windows show the new picture underneath and removes the page.
 *
 * The page starts hidden and shows the old picture exactly as the desktop does, so nothing
 * flashes: it appears only once it is drawn, plays, and holds the new picture until Windows
 * has painted it.
 */
class PictureTransitions(
    private val installDir: Path,
    private val displays: () -> List<Monitor>,
) {
    /** Plays [page] for [seconds]; [apply] sets the new wallpaper (always called once, also when the page fails). */
    fun play(page: String, seconds: Double, apply: () -> Unit) {
        var applied = false
        val players = displays().map { display -> WebLivePlayer(installDir, {}, { display.bounds }) }
        try {
            players.forEach { it.play(LiveSource(LiveKind.Web, page, startHidden = true)) }
            if (!waitFor(5_000) { players.all { it.pageTitle == "ready" } }) {
                log.info("Transition page wasn't ready in time (${players.map { it.pageTitle }}); changing without it")
                return
            }
            players.forEach { it.resume() }
            Thread.sleep(80) // one or two frames of the old picture, as it already was
            players.forEach { it.runScript("window.wpm&&wpm.go&&wpm.go();") }
            waitFor((seconds * 1000).toLong() + 2_000) { players.all { it.pageTitle == "done" } }
            apply()
            applied = true
            Thread.sleep(450) // Explorer paints the new picture under the page
        } catch (e: Exception) {
            log.warning("Transition failed: $e")
        } finally {
            if (!applied) apply()
            players.forEach { runCatching { it.stop() } }
        }
    }

    private fun waitFor(millis: Long, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!done()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(25)
        }
        return true
    }
}
