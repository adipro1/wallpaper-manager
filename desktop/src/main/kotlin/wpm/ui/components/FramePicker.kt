package wpm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import wpm.AppState
import wpm.data.MediaItem
import wpm.media.Imaging
import wpm.services.formatTimestamp
import wpm.ui.AppTheme
import wpm.ui.Theme
import java.awt.FileDialog
import java.awt.Frame
import kotlin.io.path.Path

/** Scrub through a video to pick the frame for the lock screen, or save it as a picture. */
@Composable
fun FramePickerDialog(app: AppState, item: MediaItem, onClose: () -> Unit) {
    val controller = remember { VideoController() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    DialogWindow(
        onCloseRequest = onClose,
        title = "Pick a frame – ${item.filename}",
        state = rememberDialogState(width = 900.dp, height = 640.dp),
    ) {
        AppTheme(app.darkTheme) {
            Column(Modifier.fillMaxSize().background(Theme.colors.windowBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoView(item.path, Modifier.fillMaxWidth().weight(1f), paused = true, loop = false, controller = controller)
                val duration = controller.duration.takeIf { it > 0 } ?: item.duration ?: 0.0
                Slider(
                    value = dragging ?: controller.position.toFloat(),
                    onValueChange = { dragging = it; controller.seek(it.toDouble()) },
                    onValueChangeFinished = { dragging = null },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
                    enabled = controller.ready,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${formatTimestamp(controller.position)} / ${formatTimestamp(duration)}", color = Theme.colors.text)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { controller.seek((controller.position - 1).coerceAtLeast(0.0)) }) { Text("−1 s") }
                    OutlinedButton(onClick = { controller.frameBackStep() }) { Text("◀ Frame") }
                    OutlinedButton(onClick = { controller.frameStep() }) { Text("Frame ▶") }
                    OutlinedButton(onClick = { controller.seek((controller.position + 1).coerceAtMost(duration)) }) { Text("+1 s") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val at = controller.position
                        val dialog = FileDialog(window as? Frame, "Save frame", FileDialog.SAVE).apply {
                            file = "${item.filename.substringBeforeLast('.')}-${formatTimestamp(at).replace(':', '.')}.png"
                        }
                        dialog.isVisible = true
                        val name = dialog.file ?: return@OutlinedButton
                        val target = Path(dialog.directory, if ('.' in name) name else "$name.png")
                        app.runTask("Frame saved", "Couldn't save the frame") {
                            val reader = app.library.videos ?: error("Video support is unavailable")
                            reader.frame(Path(item.path), at).use {
                                if (target.toString().lowercase().endsWith(".jpg") || target.toString().lowercase().endsWith(".jpeg")) {
                                    Imaging.saveJpeg(it, target, 95)
                                } else {
                                    Imaging.savePng(it, target)
                                }
                            }
                        }
                    }, enabled = controller.ready) { Text("Save frame…") }
                    Button(onClick = {
                        val at = controller.position
                        app.runTask("Lock screen updated", "Couldn't set the lock screen") {
                            app.changeWallpaper { app.lockScreen.setVideoFrame(item, at) }
                        }
                        onClose()
                    }, enabled = controller.ready) { Text("Set as lock screen") }
                }
            }
        }
    }
}
