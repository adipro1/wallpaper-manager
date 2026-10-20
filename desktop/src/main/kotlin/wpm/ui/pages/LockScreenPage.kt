package wpm.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem
import wpm.logger
import wpm.services.formatTimestamp
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.ChooserCard
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.FramePickerDialog
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.ui.rememberPreview
import java.nio.file.Path

private val log = logger("lockscreen-page")

@Composable
fun LockScreenPage(app: AppState) {
    var picture by remember { mutableStateOf<Path?>(null) }
    var loading by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var timestamp by remember { mutableStateOf<Double?>(null) }
    var hasOriginal by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(app.wallpaperVersion) {
        loading = true
        withContext(Dispatchers.IO) {
            current = app.lockScreen.currentItem()
            timestamp = app.lockScreen.state.timestamp
            hasOriginal = app.lockScreen.hasOriginal()
            picture = try {
                app.lockScreen.currentImage()
            } catch (e: Exception) {
                log.warning("Couldn't read the lock screen: $e")
                null
            }
        }
        loading = false
    }

    PageScaffold("Lock Screen", "The picture you see before signing in.") {
        Card(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(360.dp).height(203.dp).clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg),
                    contentAlignment = Alignment.Center) {
                    val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                    when {
                        preview != null -> Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        loading -> CircularProgressIndicator()
                        else -> MutedText("Couldn't read the lock screen picture")
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Current lock screen")
                    Text(current?.filename ?: "Set outside this app", color = Theme.colors.text, fontSize = 15.sp)
                    timestamp?.let { MutedText("Frame at ${formatTimestamp(it)}") }
                    MutedText("Windows only shows still pictures here. For videos you pick one frame.")
                    Spacer(Modifier.weight(1f))
                    if (hasOriginal) TextButton(onClick = { confirmRestore = true }) { Text("Restore original lock screen") }
                }
            }
        }
        ChooserCard(
            app, if (app.videoSupport) "Choose a picture or a video frame" else "Choose a picture",
            filter = { !it.isVideo || app.videoSupport }, highlighted = current?.id,
            onPick = { item ->
                if (item.isVideo) {
                    picking = item
                } else {
                    app.runTask("Lock screen updated", "Couldn't set the lock screen") {
                        app.changeWallpaper { app.lockScreen.setImage(item) }
                    }
                }
            },
        )
    }

    picking?.let { FramePickerDialog(app, it, onClose = { picking = null }) }

    if (confirmRestore) {
        ConfirmDialog(
            "Restore original lock screen?", "Puts back the lock screen picture you had before using this app.", "Restore",
            onDismiss = { confirmRestore = false },
            onConfirm = {
                app.runTask("Original lock screen restored", "Couldn't restore the lock screen") {
                    app.changeWallpaper { app.lockScreen.restoreOriginal() }
                }
            },
        )
    }
}
