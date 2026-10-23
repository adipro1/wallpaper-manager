package wpm.ui.pages

import wpm.tr
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import wpm.ui.components.Adaptive
import wpm.ui.components.Eyebrow
import wpm.ui.components.Notice
import wpm.ui.components.PreviewFrame
import wpm.ui.components.Tag
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import wpm.ui.components.OutlinedButton
import wpm.ui.components.SegmentedButton
import wpm.ui.components.SegmentedButtonDefaults
import wpm.ui.components.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import wpm.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import wpm.ui.components.Button
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import wpm.ui.Theme
import wpm.ui.components.ButtonIcon
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

    PageScaffold(tr("Desktop"), tr("Choose what's behind your desktop icons."), actions = {
        OutlinedButton(onClick = app::nextWallpaper) { ButtonIcon(Icons.Outlined.SkipNext); Text(tr("Next")) }
        OutlinedButton(onClick = app::randomWallpaper) { ButtonIcon(Icons.Outlined.Shuffle); Text(tr("Random")) }
    }) {
        Card(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp), fold = 26.dp) {
            Adaptive(820.dp, gap = 28.dp, first = { m ->
                PreviewFrame(m.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                    val item = current
                    if (state.kind == "video" && item != null) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    } else {
                        val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                        if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }) { m ->
                Column(m.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow(tr("Current wallpaper"))
                    Text(current?.filename ?: if (state.kind == null) tr("Set outside this app") else tr("Removed from the library"),
                        color = Theme.colors.text, style = MaterialTheme.typography.headlineSmall, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (state.kind == "video" || state.kind == "web" || state.kind == "shader") {
                        VideoControls(app, state, live)
                    } else {
                        Text(tr("POSITION"), color = Theme.colors.textMuted, style = MaterialTheme.typography.labelSmall)
                        val style = state.style
                        SingleChoiceSegmentedButtonRow {
                            IMAGE_STYLES.forEachIndexed { i, s ->
                                SegmentedButton(style == s, {
                                    app.runTask(failure = tr("Couldn't change the position")) {
                                        app.changeWallpaper { app.wallpapers.setImageStyle(s) }
                                    }
                                }, SegmentedButtonDefaults.itemShape(i, IMAGE_STYLES.size)) {
                                    Text(s.replaceFirstChar(Char::uppercase))
                                }
                            }
                        }
                    }
                    if (hasOriginal) {
                        TextButton(onClick = { confirmRestore = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                            ButtonIcon(Icons.Outlined.Restore); Text(tr("Restore original wallpaper"))
                        }
                    }
                }
            }
        }

        live.error?.let { error ->
            Notice(tr("Couldn't play this wallpaper: {0}. ", error) +
                (if (current?.isRemote == true) tr("The stream may be offline, or the site changed - try “Update yt-dlp” in Settings.") else ""))
        }
        if (live.competing.isNotEmpty()) {
            Notice(tr("{0} is also drawing on the desktop and may cover this wallpaper. ", live.competing.joinToString()) +
                tr("Close it or pause its wallpaper."), Theme.colors.gold)
        }
        if (!app.videoSupport) {
            Notice(tr("Video wallpapers need libmpv-2.dll in the app's bin folder."), Theme.colors.gold)
        }
        wpm.ui.components.WidgetEditor(app) {
            val item = current
            if (state.kind == "video" && item != null) {
                MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
            } else {
                val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        if (displays.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                SectionTitle(tr("Displays"))
                Spacer(Modifier.height(14.dp))
                val options = listOf<Pair<String?, String>>(null to tr("All displays")) + displays.map { it.id to it.label + if (it.primary) tr(" (main)") else "" }
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { i, (id, label) ->
                        SegmentedButton(target == id, { target = id }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val chosen = target?.let(choices::get)
                val targetDisplay = displays.firstOrNull { it.id == target }
                when {
                    targetDisplay == null -> MutedText(
                        if (choices.isEmpty()) tr("Pick a wallpaper below to show it on every display.")
                        else tr("{0} display(s) have their own wallpaper. Picking one below for all displays replaces them.", choices.size),
                    )
                    chosen == null -> MutedText(tr("{0} shows the same wallpaper as the others. Pick one below to give it its own.", targetDisplay.label))
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        MutedText(tr("{0} has its own wallpaper: {1}", targetDisplay.label, app.db.getMedia(chosen.mediaId)?.filename ?: tr("removed")),
                            Modifier.weight(1f))
                        TextButton(onClick = {
                            app.runTask(failure = tr("Couldn't change the display")) { app.changeWallpaper { app.wallpapers.clearDisplay(targetDisplay) } }
                        }) { Text(tr("Use the shared wallpaper")) }
                    }
                }
            }
        }
        val pickTarget = displays.firstOrNull { it.id == target }
        ChooserCard(
            app, if (pickTarget == null) tr("Choose a wallpaper") else tr("Choose a wallpaper for {0}", pickTarget.label),
            onPick = { item -> if (pickTarget == null) app.setDesktop(item) else app.setOnDisplay(pickTarget, item) },
            filter = { !it.isVideo || app.videoSupport },
            highlighted = pickTarget?.let { choices[it.id]?.mediaId } ?: state.mediaId,
        )
    }

    if (confirmRestore) {
        ConfirmDialog(
            tr("Restore original wallpaper?"), tr("Puts back the wallpaper you had before using this app."), tr("Restore"),
            onDismiss = { confirmRestore = false },
            onConfirm = {
                app.runTask(tr("Original wallpaper restored"), tr("Couldn't restore the wallpaper")) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            !live.active -> Tag(tr("Live · stopped"), Theme.colors.textMuted)
            live.autoReason != null || live.paused -> Tag(tr("Live · paused"), Theme.colors.gold)
            else -> Tag(tr("Live · playing"), Theme.colors.teal)
        }
        live.autoReason?.let { MutedText(tr("because {0}", tr(it.text))) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live.active && live.paused) {
            Button(onClick = { app.videoAction { w.resumeVideo() } }) { ButtonIcon(Icons.Outlined.PlayArrow); Text(tr("Resume")) }
        } else if (live.active) {
            OutlinedButton(onClick = { app.videoAction { w.pauseVideo() } }) { ButtonIcon(Icons.Outlined.Pause); Text(tr("Pause")) }
        } else {
            Button(onClick = { app.videoAction { w.currentItem()?.let(w::set) } }) { ButtonIcon(Icons.Outlined.PlayArrow); Text(tr("Start")) }
        }
        OutlinedButton(onClick = { app.videoAction(tr("Back to the picture underneath")) { w.stopVideo() } }) {
            ButtonIcon(Icons.Outlined.Stop); Text(tr("Stop"))
        }
        if (state.kind == "video") {
            OutlinedButton(onClick = { app.videoAction { w.setMuted(!live.muted) } }) {
                Icon(if (live.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, null)
                Text(if (live.muted) tr("  Sound off") else tr("  Sound on"))
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
