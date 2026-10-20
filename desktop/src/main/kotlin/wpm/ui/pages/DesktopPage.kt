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
import wpm.services.DisplayChoice
import wpm.win32.Monitor
import wpm.win32.Monitors
import wpm.services.IMAGE_STYLES
import wpm.services.PauseReason
import wpm.services.VIDEO_STYLES
import wpm.ui.components.MediaThumbnail
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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
    var live by remember { mutableStateOf(LiveInfo()) }
    var displays by remember { mutableStateOf(emptyList<Monitor>()) }
    var choices by remember { mutableStateOf(emptyMap<String, DisplayChoice>()) }
    var target by remember { mutableStateOf<String?>(null) } // null = all displays
    LaunchedEffect(app.wallpaperVersion, app.libraryVersion) {
        withContext(Dispatchers.IO) {
            state = app.wallpapers.state
            current = app.wallpapers.currentItem()
            hasOriginal = app.wallpapers.hasOriginal()
            displays = Monitors.all()
            choices = app.wallpapers.displayChoices()
            live = LiveInfo(
                active = app.wallpapers.videoActive,
                paused = app.wallpapers.videoPaused,
                autoReason = app.wallpapers.autoPauseReason,
                error = app.wallpapers.liveError,
                muted = app.wallpapers.muted,
                competing = app.wallpapers.competingApps(),
            )
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
                    val item = current
                    if (state.kind == "video" && item != null) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    } else {
                        val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                        if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Current wallpaper")
                    Text(current?.filename ?: if (state.kind == null) "Set outside this app" else "Removed from the library",
                        color = Theme.colors.text, fontSize = 15.sp)
                    if (state.kind == "video" || state.kind == "web" || state.kind == "shader") {
                        VideoControls(app, state, live)
                    } else {
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

        live.error?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Text("Couldn't play this wallpaper: $error. " +
                    (if (current?.isRemote == true) "The stream may be offline, or the site changed - try “Update yt-dlp” in Settings." else ""),
                    color = Theme.colors.danger)
            }
        }
        if (live.competing.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("${live.competing.joinToString()} is also drawing on the desktop and may cover this wallpaper. " +
                    "Close it or pause its wallpaper.", color = Theme.colors.danger)
            }
        }
        if (!app.videoSupport) {
            MutedText("Video wallpapers need libmpv-2.dll in the app's bin folder.")
        }
        if (displays.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                SectionTitle("Displays")
                Spacer(Modifier.height(8.dp))
                val options = listOf<Pair<String?, String>>(null to "All displays") + displays.map { it.id to it.label + if (it.primary) " (main)" else "" }
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { i, (id, label) ->
                        SegmentedButton(target == id, { target = id }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val chosen = target?.let(choices::get)
                val targetDisplay = displays.firstOrNull { it.id == target }
                when {
                    targetDisplay == null -> MutedText(
                        if (choices.isEmpty()) "Pick a wallpaper below to show it on every display."
                        else "${choices.size} display(s) have their own wallpaper. Picking one below for all displays replaces them.",
                    )
                    chosen == null -> MutedText("${targetDisplay.label} shows the same wallpaper as the others. Pick one below to give it its own.")
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        MutedText("${targetDisplay.label} has its own wallpaper: ${app.db.getMedia(chosen.mediaId)?.filename ?: "removed"}",
                            Modifier.weight(1f))
                        TextButton(onClick = {
                            app.runTask(failure = "Couldn't change the display") { app.changeWallpaper { app.wallpapers.clearDisplay(targetDisplay) } }
                        }) { Text("Use the shared wallpaper") }
                    }
                }
            }
        }
        val pickTarget = displays.firstOrNull { it.id == target }
        ChooserCard(
            app, if (pickTarget == null) "Choose a wallpaper" else "Choose a wallpaper for ${pickTarget.label}",
            onPick = { item -> if (pickTarget == null) app.setDesktop(item) else app.setOnDisplay(pickTarget, item) },
            filter = { !it.isVideo || app.videoSupport },
            highlighted = pickTarget?.let { choices[it.id]?.mediaId } ?: state.mediaId,
        )
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

private data class LiveInfo(
    val active: Boolean = false,
    val paused: Boolean = false,
    val autoReason: PauseReason? = null,
    val muted: Boolean = true,
    val competing: List<String> = emptyList(),
    val error: String? = null,
)

@Composable
private fun VideoControls(app: AppState, state: DesktopState, live: LiveInfo) {
    val w = app.wallpapers
    app.webEngineStatus?.let { Text(it, color = Theme.colors.accent) }
    MutedText(
        when {
            !live.active -> "Live wallpaper (not running)"
            live.autoReason != null -> "Paused because ${live.autoReason.text}"
            live.paused -> "Paused"
            else -> "Playing"
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live.active && live.paused) {
            Button(onClick = { app.videoAction { w.resumeVideo() } }) { Icon(Icons.Outlined.PlayArrow, null); Text("  Resume") }
        } else if (live.active) {
            OutlinedButton(onClick = { app.videoAction { w.pauseVideo() } }) { Icon(Icons.Outlined.Pause, null); Text("  Pause") }
        } else {
            Button(onClick = { app.videoAction { w.currentItem()?.let(w::set) } }) { Icon(Icons.Outlined.PlayArrow, null); Text("  Start") }
        }
        OutlinedButton(onClick = { app.videoAction("Back to the picture underneath") { w.stopVideo() } }) {
            Icon(Icons.Outlined.Stop, null); Text("  Stop")
        }
        if (state.kind == "video") {
            OutlinedButton(onClick = { app.videoAction { w.setMuted(!live.muted) } }) {
                Icon(if (live.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, null)
                Text(if (live.muted) "  Sound off" else "  Sound on")
            }
        }
    }
    if (state.kind == "video") SingleChoiceSegmentedButtonRow {
        VIDEO_STYLES.forEachIndexed { i, s ->
            SegmentedButton(state.style == s, { app.videoAction { w.setVideoStyle(s) } }, SegmentedButtonDefaults.itemShape(i, VIDEO_STYLES.size)) {
                Text(s.replaceFirstChar(Char::uppercase))
            }
        }
    }
}
