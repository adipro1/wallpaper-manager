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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import wpm.services.DesktopState
import wpm.services.IMAGE_STYLES
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.ChooserCard
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.MutedText
import wpm.ui.components.SectionTitle
import wpm.ui.components.PageScaffold
import wpm.ui.rememberPreview
import java.nio.file.Path

@Composable
fun DesktopPage(app: AppState) {
    var state by remember { mutableStateOf(DesktopState()) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var picture by remember { mutableStateOf<Path?>(null) }
    var hasOriginal by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(app.wallpaperVersion, app.libraryVersion) {
        withContext(Dispatchers.IO) {
            state = app.wallpapers.state
            current = app.wallpapers.currentItem()
            hasOriginal = app.wallpapers.hasOriginal()
            picture = app.wallpapers.currentPicture()
        }
    }

    PageScaffold("Desktop", "Choose what's behind your desktop icons.", actions = {
        OutlinedButton(onClick = app::nextWallpaper) { Icon(Icons.Outlined.SkipNext, null); Text("  Next") }
        OutlinedButton(onClick = app::randomWallpaper) { Icon(Icons.Outlined.Shuffle, null); Text("  Random") }
    }) {
        Card(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(360.dp).height(203.dp).clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg)) {
                    val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                    if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Current wallpaper")
                    Text(current?.filename ?: if (state.kind == null) "Set outside this app" else "Removed from the library",
                        color = Theme.colors.text, fontSize = 15.sp)
                    if (state.kind == "image" || state.kind == null) {
                        MutedText("Position")
                        val style = state.style
                        SingleChoiceSegmentedButtonRow {
                            IMAGE_STYLES.forEachIndexed { i, s ->
                                SegmentedButton(style == s, {
                                    app.runTask(failure = "Couldn't change the position") {
                                        app.changeWallpaper { app.wallpapers.setImageStyle(s) }
                                    }
                                }, SegmentedButtonDefaults.itemShape(i, IMAGE_STYLES.size)) {
                                    Text(s.replaceFirstChar(Char::uppercase))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (hasOriginal) {
                        TextButton(onClick = { confirmRestore = true }) { Text("Restore original wallpaper") }
                    }
                }
            }
        }

        ChooserCard(app, "Choose a wallpaper", onPick = app::setDesktop, filter = { !it.isVideo }, highlighted = state.mediaId)
    }

    if (confirmRestore) {
        ConfirmDialog(
            "Restore original wallpaper?", "Puts back the wallpaper you had before using this app.", "Restore",
            onDismiss = { confirmRestore = false },
            onConfirm = {
                app.runTask("Original wallpaper restored", "Couldn't restore the wallpaper") {
                    app.changeWallpaper { app.wallpapers.restoreOriginal() }
                }
            },
        )
    }
}
