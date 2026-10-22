package wpm.ui.pages

import wpm.tr
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import wpm.ui.components.Adaptive
import wpm.ui.components.Eyebrow
import wpm.ui.components.PreviewFrame
import wpm.ui.components.Tag
import wpm.ui.components.ButtonIcon
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import wpm.ui.components.TextButton
import wpm.ui.components.OutlinedButton
import wpm.ToastKind
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

    val clock = remember { java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("H:mm")) }
    PageScaffold(tr("Lock Screen"), tr("The picture you see before signing in.")) {
        Card(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp), fold = 26.dp) {
            Adaptive(820.dp, gap = 28.dp, first = { m ->
                PreviewFrame(m.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                    val preview = rememberPreview(picture, 1000, app.wallpaperVersion)
                    when {
                        preview != null -> Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        loading -> CircularProgressIndicator(color = Theme.colors.accent)
                        else -> Text(tr("Couldn't read the lock screen picture"), color = Color.White.copy(alpha = 0.7f))
                    }
                    // A clock, like on the real lock screen.
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(clock, color = Color.White, fontFamily = wpm.ui.DisplayFont, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }) { m ->
                Column(m.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow(tr("Current lock screen"))
                    Text(current?.filename ?: tr("Set outside this app"), color = Theme.colors.text, style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    timestamp?.let { Tag(tr("Frame at {0}", formatTimestamp(it)), Theme.colors.indigo) }
                    MutedText(tr("Windows only shows still pictures here. For videos you pick one frame."))
                    if (hasOriginal) {
                        TextButton(onClick = { confirmRestore = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                            ButtonIcon(Icons.Outlined.Restore); Text(tr("Restore original lock screen"))
                        }
                    }
                }
            }
        }
        SlideshowCard(app)
        ChooserCard(
            app, if (app.videoSupport) tr("Choose a picture or a video frame") else tr("Choose a picture"),
            filter = { it.isImage || (it.isVideo && !it.isRemote && app.videoSupport) }, highlighted = current?.id,
            onPick = { item ->
                if (item.isVideo) {
                    picking = item
                } else {
                    app.runTask(tr("Lock screen updated"), tr("Couldn't set the lock screen")) {
                        app.changeWallpaper { app.lockScreen.setImage(item) }
                    }
                }
            },
        )
    }

    picking?.let { FramePickerDialog(app, it, onClose = { picking = null }) }

    if (confirmRestore) {
        ConfirmDialog(
            tr("Restore original lock screen?"), tr("Puts back the lock screen picture you had before using this app."), tr("Restore"),
            onDismiss = { confirmRestore = false },
            onConfirm = {
                app.runTask(tr("Original lock screen restored"), tr("Couldn't restore the lock screen")) {
                    app.changeWallpaper { app.lockScreen.restoreOriginal() }
                }
            },
        )
    }
}

/** A playlist that changes the lock screen picture every so often. */
@Composable
private fun SlideshowCard(app: AppState) {
    var running by remember { mutableStateOf<wpm.services.LockSlideshowState?>(null) }
    var playlists by remember { mutableStateOf(emptyList<wpm.data.Playlist>()) }
    var chosen by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("1") }
    var hours by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(app.wallpaperVersion, app.libraryVersion) {
        withContext(Dispatchers.IO) {
            running = app.lockSlideshow.state
            playlists = app.db.listPlaylists()
        }
        running?.let {
            chosen = it.playlistId
            hours = it.intervalMinutes % 60 == 0
            amount = (if (hours) it.intervalMinutes / 60 else it.intervalMinutes).toString()
        }
        if (chosen == null) chosen = playlists.firstOrNull()?.id
    }
    val minutes = amount.trim().toIntOrNull()?.let { if (hours) it * 60 else it }
    val valid = chosen != null && minutes != null && minutes >= wpm.services.MIN_LOCK_INTERVAL_MINUTES
    wpm.ui.components.Card(Modifier.fillMaxWidth(), accent = if (running != null) Theme.colors.teal else null) {
        SectionTitle(tr("Slideshow"))
        Spacer(Modifier.height(6.dp))
        val current = running
        MutedText(
            if (current != null) {
                val name = playlists.firstOrNull { it.id == current.playlistId }?.name ?: tr("a playlist")
                val next = app.lockSlideshow.secondsUntilChange()?.let { ((it + 59) / 60).toInt() }
                tr("Showing “{0}” on the lock screen", name) + (next?.let { tr(", next picture in {0} min", it) } ?: "")
            } else {
                tr("Let a playlist change the lock screen picture (at most every {0} minutes).", wpm.services.MIN_LOCK_INTERVAL_MINUTES)
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = playlists.isNotEmpty()) {
                    Text(playlists.firstOrNull { it.id == chosen }?.name ?: if (playlists.isEmpty()) tr("Make a playlist first") else tr("Choose a playlist"))
                }
                androidx.compose.material3.DropdownMenu(menuOpen, { menuOpen = false }) {
                    playlists.forEach { p ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(p.name) }, onClick = { chosen = p.id; menuOpen = false })
                    }
                }
            }
            Text("every", color = Theme.colors.textMuted)
            wpm.ui.components.NumberField(amount, { amount = it.filter(Char::isDigit).take(4) })
            wpm.ui.components.SingleChoiceSegmentedButtonRow {
                wpm.ui.components.SegmentedButton(!hours, { hours = false }, wpm.ui.components.SegmentedButtonDefaults.itemShape(0, 2)) { Text("min") }
                wpm.ui.components.SegmentedButton(hours, { hours = true }, wpm.ui.components.SegmentedButtonDefaults.itemShape(1, 2)) { Text("h") }
            }
            Spacer(Modifier.weight(1f))
            if (current != null) {
                TextButton(onClick = {
                    app.runTask(tr("Slideshow stopped")) { app.lockSlideshow.stop(); app.wallpaperChanged() }
                }) { Text(tr("Stop")) }
            }
            wpm.ui.components.Button(onClick = {
                app.runTask(failure = tr("Couldn't start the slideshow")) {
                    val item = app.changeWallpaper { app.lockSlideshow.start(chosen!!, minutes!!) }
                    app.toast(tr("Lock screen slideshow: {0}", item.filename), ToastKind.Success)
                }
            }, enabled = valid) { Text(if (current != null) tr("Restart") else tr("Start")) }
        }
    }
}
