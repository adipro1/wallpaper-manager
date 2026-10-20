package wpm.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
        s >= 3600 -> "in ${s / 3600} h ${s % 3600 / 60} min"
        s >= 60 -> "in ${(s + 59) / 60} min"
        else -> "in $s s"
    }
}

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

    PageScaffold("Playlists", "Change the wallpaper automatically at an interval.", scroll = false, actions = {
        Button(onClick = { creating = true }) { Icon(Icons.Outlined.Add, null); Text("  New playlist") }
    }) {
        if (playlists.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                SectionTitle("No playlists yet")
                Spacer(Modifier.height(6.dp))
                MutedText("Create one, add wallpapers, choose an interval and press Play.")
            }
            return@PageScaffold
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.width(260.dp).fillMaxHeight(), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                LazyColumn {
                    items(playlists, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (p.id == selectedId) Theme.colors.navSelected else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedId = p.id }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.itemCount} wallpapers · ${p.intervalText}", color = Theme.colors.textMuted, fontSize = 12.sp)
                            }
                            if (p.id == activeId) Icon(Icons.Outlined.PlayArrow, "Playing", tint = Theme.colors.accent, modifier = Modifier.size(18.dp))
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
        TextInputDialog("New playlist", "Name", confirm = "Create", onDismiss = { creating = false }, onConfirm = { name ->
            app.runTask {
                selectedId = app.db.createPlaylist(name)
                app.libraryChanged()
            }
        })
    }
}

@Composable
private fun PlaylistEditor(app: AppState, playlist: Playlist, playing: Boolean, countdown: Double?, modifier: Modifier) {
    var items by remember(playlist.id) { mutableStateOf(emptyList<MediaItem>()) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    val hours = playlist.intervalSeconds % 3600 == 0 && playlist.intervalSeconds >= 3600
    var unitHours by remember(playlist.id) { mutableStateOf(hours) }
    var amount by remember(playlist.id) { mutableStateOf((playlist.intervalSeconds / if (hours) 3600 else 60).toString()) }
    LaunchedEffect(playlist.id, app.libraryVersion) {
        items = withContext(Dispatchers.IO) { app.db.playlistItems(playlist.id) }
    }

    fun saveInterval(text: String = amount, inHours: Boolean = unitHours) {
        val value = text.toIntOrNull()?.takeIf { it > 0 } ?: return
        val seconds = maxOf(value * if (inHours) 3600 else 60, MIN_INTERVAL_SECONDS)
        app.runTask { app.db.updatePlaylist(playlist.id, intervalSeconds = seconds); app.libraryChanged() }
    }

    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
            if (name.isNotBlank() && name != playlist.name) {
                TextButton(onClick = { app.runTask { app.db.updatePlaylist(playlist.id, name = name.trim()); app.libraryChanged() } }) { Text("Rename") }
            }
            if (playing) {
                OutlinedButton(onClick = { app.runTask("Playlist stopped") { app.playlists.stop(); app.wallpaperChanged() } }) {
                    Icon(Icons.Outlined.Stop, null); Text("  Stop")
                }
            } else {
                Button(onClick = {
                    app.runTask("Playing ${playlist.name}", "Couldn't start the playlist") { app.changeWallpaper { app.playlists.start(playlist.id) } }
                }, enabled = items.isNotEmpty()) { Icon(Icons.Outlined.PlayArrow, null); Text("  Play") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MutedText("Order")
            val modes = listOf("sequential" to "In order", "random" to "Shuffle")
            SingleChoiceSegmentedButtonRow {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(playlist.mode == key, {
                        app.runTask { app.db.updatePlaylist(playlist.id, mode = key); app.libraryChanged() }
                    }, SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label) }
                }
            }
            Spacer(Modifier.width(12.dp))
            MutedText("Change every")
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(4); saveInterval(amount) }, singleLine = true, modifier = Modifier.width(80.dp))
            SingleChoiceSegmentedButtonRow {
                listOf(false to "min", true to "h").forEachIndexed { i, (inHours, label) ->
                    SegmentedButton(unitHours == inHours, { unitHours = inHours; saveInterval(inHours = inHours) },
                        SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
        if (playing) {
            Spacer(Modifier.height(8.dp))
            Text("Playing · next change ${countdown?.let(::formatCountdown) ?: "soon"}", color = Theme.colors.accent)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("${items.size} wallpapers")
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, null); Text("  Add wallpapers") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", color = Theme.colors.textSubtle, modifier = Modifier.width(24.dp))
                    Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(4.dp)).background(Theme.colors.previewBg)) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.filename, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (item.isVideo) "Video ${item.durationText}" else item.resolutionText, color = Theme.colors.textMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { app.runTask { app.db.moveInPlaylist(playlist.id, item.id, -1); app.libraryChanged() } }, enabled = index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, "Move up")
                    }
                    IconButton(onClick = { app.runTask { app.db.moveInPlaylist(playlist.id, item.id, +1); app.libraryChanged() } }, enabled = index < items.lastIndex) {
                        Icon(Icons.Outlined.ArrowDownward, "Move down")
                    }
                    IconButton(onClick = { app.runTask { app.db.removeFromPlaylist(playlist.id, item.id); app.libraryChanged() } }) {
                        Icon(Icons.Outlined.Close, "Remove from playlist")
                    }
                }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete playlist", color = Theme.colors.danger) }
        }
    }

    if (adding) {
        MediaPickerDialog(app, "Add to ${playlist.name}", multiple = true, exclude = items.mapTo(HashSet()) { it.id },
            onDismiss = { adding = false }) { picked ->
            app.runTask("Added ${picked.size}") { app.db.addToPlaylist(playlist.id, picked.map { it.id }); app.libraryChanged() }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete “${playlist.name}”?", "The wallpapers stay in your library. Schedule rules that use this playlist are removed too.",
            "Delete", danger = true, onDismiss = { confirmDelete = false }, onConfirm = {
                app.runTask("Playlist deleted") {
                    if (playing) app.playlists.stop()
                    app.db.deletePlaylist(playlist.id)
                    app.libraryChanged()
                }
            })
    }
}
