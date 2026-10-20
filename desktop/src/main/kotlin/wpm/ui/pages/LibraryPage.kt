package wpm.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.Shortcut
import wpm.ToastKind
import wpm.data.MediaItem
import wpm.media.shortenMiddle
import wpm.ui.Theme
import wpm.ui.components.AddToPlaylistButton
import wpm.ui.components.Card
import wpm.ui.components.ClickKind
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.MediaGrid
import wpm.ui.components.MediaThumbnail
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.ui.components.pickFolder
import wpm.ui.components.pickMediaFiles
import wpm.ui.rememberPreview
import wpm.win32.sendToRecycleBin
import wpm.win32.showInExplorer
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

private val SORTS = listOf("newest" to "Newest first", "oldest" to "Oldest first", "name" to "Name A–Z", "name_desc" to "Name Z–A")
private val TYPES = listOf(null to "All", "image" to "Images", "video" to "Videos")

private sealed interface PendingAction {
    data class Remove(val ids: List<Long>) : PendingAction
    data class DeleteFile(val item: MediaItem) : PendingAction
}

@Composable
fun LibraryPage(app: AppState) {
    var search by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(app.settings.string("library_sort", "newest")) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var anchor by remember { mutableStateOf<Long?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(search, type, favoritesOnly, sort, app.libraryVersion) {
        val loaded = withContext(Dispatchers.IO) { app.db.listMedia(search, type, favoritesOnly, sort) }
        items = loaded
        val ids = loaded.mapTo(HashSet()) { it.id }
        selected = selected.filterTo(HashSet()) { it in ids }
    }
    LaunchedEffect(Unit) {
        app.shortcuts.collect { shortcut ->
            when (shortcut) {
                Shortcut.Find -> runCatching { searchFocus.requestFocus() }
                Shortcut.SelectAll -> selected = items.orEmpty().mapTo(HashSet()) { it.id }
                Shortcut.Delete -> if (selected.isNotEmpty()) pending = PendingAction.Remove(selected.toList())
                Shortcut.Escape -> selected = emptySet()
            }
        }
    }

    fun onTileClick(item: MediaItem, kind: ClickKind) {
        val list = items.orEmpty()
        selected = when (kind) {
            ClickKind.Plain -> setOf(item.id)
            ClickKind.Toggle -> if (item.id in selected) selected - item.id else selected + item.id
            ClickKind.Range -> {
                val from = list.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: list.indexOf(item)
                val to = list.indexOf(item)
                list.subList(minOf(from, to), maxOf(from, to) + 1).mapTo(HashSet()) { it.id }
            }
        }
        if (kind != ClickKind.Range) anchor = item.id
    }

    PageScaffold(
        "Library",
        items?.let { "${it.size} wallpaper${if (it.size == 1) "" else "s"}" + if (selected.size > 1) " · ${selected.size} selected" else "" },
        scroll = false,
        actions = {
            OutlinedButton(onClick = { pickFolder(app.window)?.let { app.import(listOf(it)) } }) {
                Icon(Icons.Outlined.CreateNewFolder, null); Text("  Import folder")
            }
            Button(onClick = { pickMediaFiles(app.window).takeIf { it.isNotEmpty() }?.let(app::import) }) {
                Icon(Icons.Outlined.FileUpload, null); Text("  Import files")
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                search, { search = it }, singleLine = true, placeholder = { Text("Search by name") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.width(260.dp).focusRequester(searchFocus),
            )
            SingleChoiceSegmentedButtonRow {
                TYPES.forEachIndexed { i, (key, label) ->
                    SegmentedButton(type == key, { type = key }, SegmentedButtonDefaults.itemShape(i, TYPES.size)) { Text(label) }
                }
            }
            FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") },
                leadingIcon = { Icon(if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder, null, Modifier.size(18.dp)) })
            SortMenu(sort) { sort = it; app.settings.set("library_sort", it) }
        }

        app.importProgress?.let { progress ->
            Column(Modifier.fillMaxWidth()) {
                MutedText(if (progress.total > 0) "Importing ${progress.done} of ${progress.total}: ${progress.current}" else progress.current)
                Spacer(Modifier.height(6.dp))
                if (progress.total > 0) LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (selected.size > 1) BulkBar(app, selected.toList(), onRemove = { pending = PendingAction.Remove(selected.toList()) }, onClear = { selected = emptySet() })

        val list = items
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    list == null -> {}
                    list.isEmpty() -> EmptyLibrary(filtered = search.isNotBlank() || type != null || favoritesOnly)
                    else -> MediaGrid(list, app.thumbs, selected, ::onTileClick, Modifier.fillMaxSize(),
                        onDoubleClick = { if (!it.isVideo) app.setDesktop(it) })
                }
            }
            val single = list?.firstOrNull { selected.size == 1 && it.id in selected }
            if (single != null) {
                DetailsPanel(app, single, Modifier.width(320.dp).fillMaxHeight(),
                    onRemove = { pending = PendingAction.Remove(listOf(single.id)) },
                    onDelete = { pending = PendingAction.DeleteFile(single) })
            }
        }
    }

    when (val action = pending) {
        is PendingAction.Remove -> {
            val (playlistEntries, rules) = remember(action) { app.db.removalImpact(action.ids) }
            val count = action.ids.size
            val extra = buildList {
                if (playlistEntries > 0) add("$playlistEntries playlist entr${if (playlistEntries == 1) "y" else "ies"}")
                if (rules > 0) add("$rules schedule rule${if (rules == 1) "" else "s"}")
            }
            ConfirmDialog(
                title = if (count == 1) "Remove from library?" else "Remove $count wallpapers from library?",
                text = "The files stay on your PC." + if (extra.isEmpty()) "" else " This also removes ${extra.joinToString(" and ")} that use ${if (count == 1) "it" else "them"}.",
                confirm = "Remove",
                onDismiss = { pending = null },
                onConfirm = {
                    app.runTask("Removed $count from the library") {
                        action.ids.forEach { id -> app.db.getMedia(id)?.let(app.library::deleteThumbnail); app.db.removeMedia(id) }
                        selected = emptySet()
                        app.libraryChanged()
                    }
                },
            )
        }
        is PendingAction.DeleteFile -> ConfirmDialog(
            title = "Delete file?",
            text = "${action.item.filename} will be moved to the Recycle Bin and removed from the library.",
            confirm = "Delete", danger = true,
            onDismiss = { pending = null },
            onConfirm = {
                app.runTask("Moved to the Recycle Bin", "Couldn't delete the file") {
                    sendToRecycleBin(action.item.path)
                    app.library.deleteThumbnail(action.item)
                    app.db.removeMedia(action.item.id)
                    selected = emptySet()
                    app.libraryChanged()
                }
            },
        )
        null -> {}
    }
}

@Composable
private fun SortMenu(sort: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${SORTS.first { it.first == sort }.second}", maxLines = 1, softWrap = false) }
        DropdownMenu(open, { open = false }) {
            SORTS.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(key) }) }
        }
    }
}

@Composable
private fun EmptyLibrary(filtered: Boolean) {
    Column(Modifier.fillMaxSize().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionTitle(if (filtered) "Nothing matches" else "Your library is empty")
        Spacer(Modifier.height(6.dp))
        MutedText(if (filtered) "Try a different search or filter." else "Import pictures and videos, or drag them onto this window.")
    }
}

@Composable
private fun BulkBar(app: AppState, ids: List<Long>, onRemove: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ids.size} selected", color = Theme.colors.text, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { setFavorites(app, ids, true) }) { Text("Add to favorites") }
            OutlinedButton(onClick = { setFavorites(app, ids, false) }) { Text("Remove from favorites") }
            AddToPlaylistButton(app, { ids })
            OutlinedButton(onClick = onRemove) { Text("Remove") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

private fun setFavorites(app: AppState, ids: List<Long>, favorite: Boolean) = app.runTask {
    ids.forEach { app.db.setFavorite(it, favorite) }
    app.libraryChanged()
}

@Composable
private fun DetailsPanel(app: AppState, item: MediaItem, modifier: Modifier, onRemove: () -> Unit, onDelete: () -> Unit) {
    val exists = remember(item.id, app.libraryVersion) { Path(item.path).exists() }
    Card(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg)) {
                val preview = if (!item.isVideo && exists) rememberPreview(Path(item.path), 900, item.id) else null
                if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
            }
            Text(item.filename, color = Theme.colors.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            InfoRow("Type", if (item.isVideo) "Video" else "Image")
            InfoRow("Resolution", item.resolutionText)
            if (item.isVideo) InfoRow("Duration", item.durationText.ifEmpty { "Unknown" })
            InfoRow("Size", item.sizeText)
            InfoRow("Added", item.dateAdded.take(16))
            InfoRow("Folder", shortenMiddle(Path(item.path).parent?.pathString ?: "", 40))
            if (!exists) Text("The file is missing. It may have been moved or deleted.", color = Theme.colors.danger, fontSize = 13.sp)

            Spacer(Modifier.height(4.dp))
            Button(onClick = { app.setDesktop(item) }, enabled = exists && !item.isVideo, modifier = Modifier.fillMaxWidth()) {
                Text("Set as desktop wallpaper")
            }
            OutlinedButton(
                onClick = {
                    app.runTask("Lock screen updated", "Couldn't set the lock screen") {
                        app.changeWallpaper { app.lockScreen.setImage(item) }
                    }
                },
                enabled = exists && !item.isVideo, modifier = Modifier.fillMaxWidth(),
            ) { Text("Set as lock screen") }
            if (item.isVideo) MutedText("Video wallpapers arrive with the video engine.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    app.runTask { app.db.setFavorite(item.id, !item.favorite); app.libraryChanged() }
                }) {
                    Icon(if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                        tint = if (item.favorite) Theme.colors.star else Theme.colors.text, modifier = Modifier.size(18.dp))
                    Text(if (item.favorite) "  Favorite" else "  Add favorite")
                }
                OutlinedButton(onClick = {
                    runCatching { showInExplorer(item.path) }.onFailure { app.toast("Couldn't open Explorer", ToastKind.Error) }
                }, enabled = exists) { Text("Show in folder") }
            }
            AddToPlaylistButton(app, { listOf(item.id) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDelete, enabled = exists) { Text("Delete file…", color = Theme.colors.danger) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = Theme.colors.textMuted, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(value, color = Theme.colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
