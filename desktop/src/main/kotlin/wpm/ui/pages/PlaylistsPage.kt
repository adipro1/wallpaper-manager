package wpm.ui.pages

import wpm.tr
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import wpm.ui.components.EmptyState
import wpm.ui.components.FoldShape
import wpm.ui.components.ListEntry
import wpm.ui.components.NumberField
import wpm.ui.components.Tag
import wpm.ui.components.foldedShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import wpm.ui.components.Button
import androidx.compose.material3.Icon
import wpm.ui.components.IconButton
import wpm.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem
import wpm.data.Playlist
import wpm.services.MIN_INTERVAL_SECONDS
import wpm.ui.Theme
import wpm.ui.components.ButtonIcon
import wpm.ui.components.Card
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.MediaPickerDialog
import wpm.ui.components.MediaThumbnail
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.ui.components.TextInputDialog

/** "in 4 min" / "in 1 h 5 min" / "in 20 s". */
fun formatCountdown(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return when {
        s >= 3600 -> tr("in {0} h {1} min", s / 3600, s % 3600 / 60)
        s >= 60 -> tr("in {0} min", (s + 59) / 60)
        else -> tr("in {0} s", s)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsPage(app: AppState) {
    var playlists by remember { mutableStateOf(emptyList<Playlist>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var countdown by remember { mutableStateOf<Double?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(app.libraryVersion, app.wallpaperVersion) {
        withContext(Dispatchers.IO) {
            playlists = app.db.listPlaylists()
            activeId = app.playlists.activePlaylist()?.id
        }
        if (selectedId == null || playlists.none { it.id == selectedId }) selectedId = activeId ?: playlists.firstOrNull()?.id
    }
    LaunchedEffect(activeId) {
        while (activeId != null) {
            countdown = withContext(Dispatchers.IO) { app.playlists.secondsUntilChange() }
            delay(1_000)
        }
        countdown = null
    }

    PageScaffold(tr("Playlists"), tr("Change the wallpaper automatically at an interval."), scroll = false, actions = {
        OutlinedButton(onClick = {
            val dialog = java.awt.FileDialog(app.window as? java.awt.Frame, tr("Choose a dynamic theme"), java.awt.FileDialog.LOAD).apply {
                file = "*.ddw"
                setFilenameFilter { _, name -> name.endsWith(".ddw", ignoreCase = true) }
            }
            dialog.isVisible = true
            val chosen = dialog.file?.let { java.nio.file.Path.of(dialog.directory, it) } ?: return@OutlinedButton
            app.runTask(failure = tr("Couldn't import the theme")) {
                selectedId = app.importDynamicTheme(chosen)
                app.toast(tr("Imported. Press Play: the pictures follow the sun through the day."), wpm.ToastKind.Success)
            }
        }) { ButtonIcon(Icons.Outlined.WbTwilight); Text(tr("Import dynamic theme…")) }
        Button(onClick = { creating = true }) { ButtonIcon(Icons.Outlined.Add); Text(tr("New playlist")) }
    }) {
        if (playlists.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(tr("No playlists yet"), tr("Create one, add wallpapers, choose an interval and press Play.")) {
                    Button(onClick = { creating = true }) { ButtonIcon(Icons.Outlined.Add); Text(tr("New playlist")) }
                }
            }
            return@PageScaffold
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.width(270.dp).fillMaxHeight(), padding = androidx.compose.foundation.layout.PaddingValues(10.dp), fold = 14.dp) {
                Text(tr("YOUR PLAYLISTS"), color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        ListEntry(p.id == selectedId, { selectedId = p.id }) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (p.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                                Text(tr("{0} wallpapers · {1}", p.itemCount, if (p.mode == "sun") tr("follows the sun") else p.intervalText),
                                    color = Theme.colors.textMuted, fontSize = 12.sp)
                            }
                            if (p.id == activeId) Tag(tr("Playing"), Theme.colors.teal)
                        }
                    }
                }
            }
            val playlist = playlists.firstOrNull { it.id == selectedId }
            if (playlist != null) {
                PlaylistEditor(app, playlist, playing = playlist.id == activeId, countdown = countdown, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    if (creating) {
        TextInputDialog(tr("New playlist"), tr("Name"), confirm = tr("Create"), onDismiss = { creating = false }, onConfirm = { name ->
            app.runTask {
                selectedId = app.db.createPlaylist(name)
                app.libraryChanged()
            }
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistEditor(app: AppState, playlist: Playlist, playing: Boolean, countdown: Double?, modifier: Modifier) {
    var items by remember(playlist.id) { mutableStateOf(emptyList<MediaItem>()) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    val hours = playlist.intervalSeconds % 3600 == 0 && playlist.intervalSeconds >= 3600
    var unitHours by remember(playlist.id) { mutableStateOf(hours) }
    var amount by remember(playlist.id) { mutableStateOf((playlist.intervalSeconds / if (hours) 3600 else 60).toString()) }
    var dayCount by remember(playlist.id) { mutableStateOf(0) }
    var dayText by remember(playlist.id) { mutableStateOf("") }
    LaunchedEffect(playlist.id, app.libraryVersion) {
        items = withContext(Dispatchers.IO) { app.db.playlistItems(playlist.id) }
        dayCount = withContext(Dispatchers.IO) { app.playlists.dayCount(playlist.id, items.size) }
        if (dayText.toIntOrNull() != dayCount) dayText = dayCount.toString()
    }

    fun saveInterval(text: String = amount, inHours: Boolean = unitHours) {
        val value = text.toIntOrNull()?.takeIf { it > 0 } ?: return
        val seconds = maxOf(value * if (inHours) 3600 else 60, MIN_INTERVAL_SECONDS)
        app.runTask { app.db.updatePlaylist(playlist.id, intervalSeconds = seconds); app.libraryChanged() }
    }

    Card(modifier, fold = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name")) }, modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.titleLarge)
            if (name.isNotBlank() && name != playlist.name) {
                TextButton(onClick = { app.runTask { app.db.updatePlaylist(playlist.id, name = name.trim()); app.libraryChanged() } }) { Text(tr("Rename")) }
            }
            if (playing) {
                OutlinedButton(onClick = { app.runTask(tr("Playlist stopped")) { app.playlists.stop(); app.wallpaperChanged() } }) {
                    ButtonIcon(Icons.Outlined.Stop); Text(tr("Stop"))
                }
            } else {
                Button(onClick = {
                    app.runTask(tr("Playing {0}", playlist.name), tr("Couldn't start the playlist")) { app.changeWallpaper { app.playlists.start(playlist.id) } }
                }, enabled = items.isNotEmpty()) { ButtonIcon(Icons.Outlined.PlayArrow); Text(tr("Play")) }
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("ORDER"), color = Theme.colors.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
            val modes = listOf("sequential" to tr("In order"), "random" to tr("Shuffle"), "sun" to tr("Follow the sun"))
            SingleChoiceSegmentedButtonRow {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(playlist.mode == key, {
                        app.runTask { app.db.updatePlaylist(playlist.id, mode = key); app.libraryChanged() }
                    }, SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label) }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (playlist.mode == "sun") {
                Text(tr("DAY PICTURES"), color = Theme.colors.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                NumberField(dayText, { text ->
                    dayText = text.filter(Char::isDigit).take(3)
                    dayText.toIntOrNull()?.let { n -> app.runTask { app.playlists.setDayCount(playlist.id, n.coerceAtMost(items.size)); app.libraryChanged() } }
                })
            } else {
                Text(tr("CHANGE EVERY"), color = Theme.colors.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                NumberField(amount, { amount = it.filter(Char::isDigit).take(4); saveInterval(amount) })
                SingleChoiceSegmentedButtonRow {
                    listOf(false to "min", true to "h").forEachIndexed { i, (inHours, label) ->
                        SegmentedButton(unitHours == inHours, { unitHours = inHours; saveInterval(inHours = inHours) },
                            SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
        }
        if (playlist.mode == "sun") {
            Spacer(Modifier.height(6.dp))
            MutedText(tr("The first {0} pictures are spread from sunrise to sunset, the others over the night (for the city on the Schedule page).", dayCount))
        }
        if (playing) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tag(tr("Playing"), Theme.colors.teal)
                Text(tr("Next change {0}", countdown?.let(::formatCountdown) ?: tr("soon")), color = Theme.colors.accent)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("${items.size} wallpapers")
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { adding = true }) { ButtonIcon(Icons.Outlined.Add); Text(tr("Add wallpapers")) }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            EmptyState(tr("Nothing to play yet"), tr("Add wallpapers from your library, then press Play."), Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                Row(
                    Modifier.fillMaxWidth().background(if (index % 2 == 0) Theme.colors.inset.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent,
                        foldedShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("%02d".format(index + 1), color = Theme.colors.accent, fontFamily = wpm.ui.DisplayFont, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.width(26.dp))
                    if (playlist.mode == "sun") {
                        val day = index < dayCount
                        Icon(if (day) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, if (day) tr("Day") else tr("Night"),
                            tint = if (day) Theme.colors.gold else Theme.colors.indigo, modifier = Modifier.size(18.dp))
                    }
                    Box(Modifier.width(96.dp).height(54.dp).clip(FoldShape(10.dp, 3.dp)).background(Theme.colors.previewBg)) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.filename, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (item.isVideo) tr("Video {0}", item.durationText) else item.resolutionText, color = Theme.colors.textMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { app.runTask { app.db.moveInPlaylist(playlist.id, item.id, -1); app.libraryChanged() } }, enabled = index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, tr("Move up"))
                    }
                    IconButton(onClick = { app.runTask { app.db.moveInPlaylist(playlist.id, item.id, +1); app.libraryChanged() } }, enabled = index < items.lastIndex) {
                        Icon(Icons.Outlined.ArrowDownward, tr("Move down"))
                    }
                    IconButton(onClick = { app.runTask { app.db.removeFromPlaylist(playlist.id, item.id); app.libraryChanged() } }) {
                        Icon(Icons.Outlined.Close, tr("Remove from playlist"))
                    }
                }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }, danger = true) { ButtonIcon(Icons.Outlined.Delete); Text(tr("Delete playlist")) }
        }
    }

    if (adding) {
        MediaPickerDialog(app, tr("Add to {0}", playlist.name), multiple = true, exclude = items.mapTo(HashSet()) { it.id },
            onDismiss = { adding = false }) { picked ->
            app.runTask(tr("Added {0}", picked.size)) { app.db.addToPlaylist(playlist.id, picked.map { it.id }); app.libraryChanged() }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(tr("Delete “{0}”?", playlist.name), tr("The wallpapers stay in your library. Schedule rules that use this playlist are removed too."),
            tr("Delete"), danger = true, onDismiss = { confirmDelete = false }, onConfirm = {
                app.runTask(tr("Playlist deleted")) {
                    if (playing) app.playlists.stop()
                    app.db.deletePlaylist(playlist.id)
                    app.libraryChanged()
                }
            })
    }
}
