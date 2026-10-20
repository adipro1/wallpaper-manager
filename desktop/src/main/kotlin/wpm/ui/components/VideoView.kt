package wpm.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import wpm.logger
import wpm.mpv.MpvPlayer
import java.awt.Canvas

private val log = logger("video-view")

/** Controls for a [VideoView]; all calls are safe before the player exists (they do nothing). */
class VideoController {
    internal var player: MpvPlayer? = null

    /** Current position and length in seconds, refreshed a few times a second. */
    var position by mutableDoubleStateOf(0.0)
        internal set
    var duration by mutableDoubleStateOf(0.0)
        internal set
    var ready by mutableStateOf(false)
        internal set

    fun seek(seconds: Double) = runCatching { player?.command("seek", "%.3f".format(java.util.Locale.ROOT, seconds), "absolute+exact") }
    fun frameStep() = runCatching { player?.command("frame-step") }
    fun frameBackStep() = runCatching { player?.command("frame-back-step") }
    fun pause(paused: Boolean) = runCatching { player?.set("pause", if (paused) "yes" else "no") }
}

/**
 * A muted, looping video drawn by mpv into a native canvas inside the Compose layout.
 * Native windows are drawn above Compose content, so hide it while a dialog or menu would overlap it.
 */
@Composable
fun VideoView(source: String, modifier: Modifier = Modifier, paused: Boolean = false, loop: Boolean = true, controller: VideoController? = null) {
    val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
    var player by remember { mutableStateOf<MpvPlayer?>(null) }

    LaunchedEffect(source) {
        // The canvas needs a native window (peer) before mpv can draw into it.
        while (!canvas.isDisplayable) delay(30)
        val wid = Native.getComponentID(canvas)
        player = withContext(Dispatchers.IO) {
            runCatching {
                MpvPlayer(
                    mapOf(
                        "wid" to wid.toString(), "mute" to "yes", "hwdec" to "auto-safe", "keepaspect" to "yes",
                        "loop-file" to if (loop) "inf" else "no", "keep-open" to "always", "hr-seek" to "yes",
                        "pause" to if (paused) "yes" else "no",
                        "osc" to "no", "osd-level" to "0", "input-default-bindings" to "no", "input-vo-keyboard" to "no",
                        "input-cursor" to "no", "idle" to "yes", "config" to "no", "terminal" to "no", "ytdl" to "no",
                    ),
                ).also {
                    it.startEventThread("mpv-preview")
                    it.command("loadfile", source)
                }
            }.onFailure { log.warning("Video preview failed: $it") }.getOrNull()
        }
        controller?.player = player
        while (player != null && controller != null) {
            controller.position = player?.get("time-pos")?.toDoubleOrNull() ?: controller.position
            controller.duration = player?.get("duration")?.toDoubleOrNull() ?: controller.duration
            controller.ready = controller.duration > 0
            delay(150)
        }
    }
    DisposableEffect(source) {
        onDispose {
            val p = player
            controller?.player = null
            player = null
            if (p != null) Thread { p.close() }.start() // closing can take a moment
        }
    }
    Box(modifier) {
        SwingPanel(background = Color.Black, factory = { canvas }, modifier = Modifier.matchParentSize())
    }
}
