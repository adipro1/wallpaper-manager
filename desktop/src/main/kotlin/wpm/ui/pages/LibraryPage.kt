package wpm.ui.pages

import wpm.tr
import wpm.trCount
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.MaterialTheme
import wpm.ui.components.EmptyState
import wpm.ui.components.Notice
import wpm.ui.components.PreviewFrame
import wpm.ui.components.PropertyEditor
import wpm.ui.components.TagEditor
import wpm.ui.components.MissingFilesDialog
import wpm.ui.components.TextInputDialog
import wpm.ui.components.SearchField
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.StarBorder
import wpm.ui.components.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import wpm.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import wpm.ui.components.Switch
import wpm.ui.components.ButtonIcon
import wpm.ui.components.AddToPlaylistButton
import wpm.ui.components.AddWebDialog
import wpm.ui.components.Card
import wpm.ui.components.ClickKind
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.FramePickerDialog
import wpm.ui.components.VideoView
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

private val SORTS = listOf("newest" to tr("Newest first"), "oldest" to tr("Oldest first"), "name" to tr("Name A–Z"), "name_desc" to tr("Name Z–A"))
private val TYPES = listOf(null to tr("All"), "image" to tr("Images"), "video" to tr("Videos"), "interactive" to tr("Interactive"))

private sealed interface PendingAction {
    data class Remove(val ids: List<Long>) : PendingAction
    data class DeleteFile(val item: MediaItem) : PendingAction
}

@OptIn(ExperimentalLayoutApi::class)
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
    var pickerOpen by remember { mutableStateOf(false) }
    var addingWeb by remember { mutableStateOf(false) }
    var importingEngine by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    var tags by remember { mutableStateOf(emptyList<Pair<String, Int>>()) }
    var missing by remember { mutableStateOf(emptyList<MediaItem>()) }
    var reviewingMissing by remember { mutableStateOf(false) }
    var taggingSelection by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(app.libraryVersion) {
        withContext(Dispatchers.IO) {
            tags = app.db.listTags()
            missing = runCatching { app.missing.find() }.getOrDefault(emptyList())
        }
        if (tag != null && tags.none { it.first.equals(tag, ignoreCase = true) }) tag = null
        if (missing.isEmpty()) reviewingMissing = false
    }
    LaunchedEffect(search, type, favoritesOnly, sort, tag, app.libraryVersion) {
        val loaded = withContext(Dispatchers.IO) { app.db.listMedia(search, type, favoritesOnly, sort, tag = tag) }
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
        tr("Library"),
        items?.let { trCount(it.size, "{0} wallpaper", "{0} wallpapers") + if (selected.size > 1) tr(" · {0} selected", selected.size) else "" },
        scroll = false,
        actions = {
            var addMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { addMenu = true }) { ButtonIcon(Icons.Outlined.Language); Text(tr("Add from…")) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text(tr("A web page or web wallpaper")) }, leadingIcon = { Icon(Icons.Outlined.Language, null) },
                        onClick = { addMenu = false; addingWeb = true })
                    DropdownMenuItem(text = { Text(tr("A video link (YouTube, …)")) }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                        onClick = { addMenu = false; app.addUrl = "" })
                    DropdownMenuItem(text = { Text(tr("Wallpaper Engine")) }, leadingIcon = { Icon(Icons.Outlined.Wallpaper, null) },
                        onClick = { addMenu = false; importingEngine = true })
                }
            }
            OutlinedButton(onClick = { pickFolder(app.window)?.let { app.import(listOf(it)) } }) {
                ButtonIcon(Icons.Outlined.CreateNewFolder); Text(tr("Import folder"))
            }
            Button(onClick = { pickMediaFiles(app.window).takeIf { it.isNotEmpty() }?.let(app::import) }) {
                ButtonIcon(Icons.Outlined.FileUpload); Text(tr("Import files"))
            }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(search, { search = it }, Modifier.width(260.dp).focusRequester(searchFocus), placeholder = tr("Search by name"))
            SingleChoiceSegmentedButtonRow {
                TYPES.forEachIndexed { i, (key, label) ->
                    SegmentedButton(type == key, { type = key }, SegmentedButtonDefaults.itemShape(i, TYPES.size)) { Text(label) }
                }
            }
            FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text(tr("Favorites")) },
                leadingIcon = { Icon(if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder, null, Modifier.size(18.dp)) })
            SortMenu(sort) { sort = it; app.settings.set("library_sort", it) }
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { (name, count) ->
                    FilterChip(tag == name, { tag = if (tag == name) null else name }, label = { Text("#$name  $count") })
                }
            }
        }
        if (missing.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Notice(trCount(missing.size, "{0} wallpaper can't be found (moved, renamed or deleted).", "{0} wallpapers can't be found (moved, renamed or deleted)."),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { reviewingMissing = true }) { Text(tr("Review")) }
            }
        }

        app.importProgress?.let { progress ->
            Column(Modifier.fillMaxWidth()) {
                MutedText(if (progress.total > 0) tr("Importing {0} of {1}: {2}", progress.done, progress.total, progress.current) else progress.current)
                Spacer(Modifier.height(6.dp))
                if (progress.total > 0) LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (selected.size > 1) BulkBar(app, selected.toList(), onRemove = { pending = PendingAction.Remove(selected.toList()) },
            onTag = { taggingSelection = true }, onClear = { selected = emptySet() })

        val list = items
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    list == null -> {}
                    list.isEmpty() -> EmptyLibrary(app, filtered = search.isNotBlank() || type != null || favoritesOnly || tag != null)
                    else -> MediaGrid(list, app.thumbs, selected, ::onTileClick, Modifier.fillMaxSize(),
                        onDoubleClick = { if (!it.isVideo || app.videoSupport) app.setDesktop(it) })
                }
            }
            val single = list?.firstOrNull { selected.size == 1 && it.id in selected }
            if (single != null) {
                DetailsPanel(app, single, Modifier.width(340.dp).fillMaxHeight(), showVideo = pending == null && !pickerOpen,
                    onPickFrame = { pickerOpen = true },
                    onRemove = { pending = PendingAction.Remove(listOf(single.id)) },
                    onDelete = { pending = PendingAction.DeleteFile(single) })
            }
        }
    }

    if (addingWeb) AddWebDialog(app, onDismiss = { addingWeb = false })
    if (importingEngine) wpm.ui.components.EngineImportDialog(app, onDismiss = { importingEngine = false })
    if (reviewingMissing) MissingFilesDialog(app, missing, onDismiss = { reviewingMissing = false })
    if (taggingSelection) {
        val ids = selected.toList()
        TextInputDialog(tr("Tag {0} wallpapers", ids.size), tr("Tag"), confirm = tr("Add tag"), onDismiss = { taggingSelection = false }, onConfirm = { name ->
            app.runTask(tr("Tagged {0} with “{1}”", ids.size, name), tr("Couldn't add the tag")) {
                app.db.addTag(ids, name)
                app.libraryChanged()
            }
        })
    }

    if (pickerOpen) {
        items?.firstOrNull { selected.size == 1 && it.id in selected }?.let { FramePickerDialog(app, it, onClose = { pickerOpen = false }) }
            ?: run { pickerOpen = false }
    }

    when (val action = pending) {
        is PendingAction.Remove -> {
            val (playlistEntries, rules) = remember(action) { app.db.removalImpact(action.ids) }
            val count = action.ids.size
            val extra = buildList {
                if (playlistEntries > 0) add(trCount(playlistEntries, "{0} playlist entry", "{0} playlist entries"))
                if (rules > 0) add(trCount(rules, "{0} schedule rule", "{0} schedule rules"))
            }
            ConfirmDialog(
                title = if (count == 1) tr("Remove from library?") else tr("Remove {0} wallpapers from library?", count),
                text = tr("The files stay on your PC.") + if (extra.isEmpty()) "" else " " + tr(if (count == 1) "This also removes {0} that use it." else "This also removes {0} that use them.", extra.joinToString(tr(" and "))),
                confirm = tr("Remove"),
                onDismiss = { pending = null },
                onConfirm = {
                    app.runTask(tr("Removed {0} from the library", count)) {
                        action.ids.forEach { id -> app.db.getMedia(id)?.let(app.library::deleteThumbnail); app.db.removeMedia(id) }
                        selected = emptySet()
                        app.libraryChanged()
                    }
                },
            )
        }
        is PendingAction.DeleteFile -> ConfirmDialog(
            title = tr("Delete file?"),
            text = tr("{0} will be moved to the Recycle Bin and removed from the library.", action.item.filename),
            confirm = tr("Delete"), danger = true,
            onDismiss = { pending = null },
            onConfirm = {
                app.runTask(tr("Moved to the Recycle Bin"), tr("Couldn't delete the file")) {
                    sendToRecycleBin(action.item.path!!)
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
        TextButton(onClick = { open = true }) {
            ButtonIcon(Icons.AutoMirrored.Outlined.Sort)
            Text(SORTS.first { it.first == sort }.second, maxLines = 1, softWrap = false)
        }
        DropdownMenu(open, { open = false }) {
            SORTS.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(key) }) }
        }
    }
}

@Composable
private fun EmptyLibrary(app: AppState, filtered: Boolean) {
    EmptyState(
        if (filtered) tr("Nothing matches") else tr("Your library is empty"),
        if (filtered) tr("Try a different search or filter.") else tr("Import pictures and videos, or drag them onto this window."),
        Modifier.padding(top = 40.dp),
    ) {
        if (!filtered) {
            Button(onClick = { pickMediaFiles(app.window).takeIf { it.isNotEmpty() }?.let(app::import) }) {
                ButtonIcon(Icons.Outlined.FileUpload); Text(tr("Import files"))
            }
        }
    }
}

@Composable
private fun BulkBar(app: AppState, ids: List<Long>, onRemove: () -> Unit, onTag: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 26.dp, top = 10.dp, bottom = 10.dp),
        fold = 14.dp, accent = Theme.colors.accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ids.size} selected", color = Theme.colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { setFavorites(app, ids, true) }) { Text(tr("Add to favorites")) }
            OutlinedButton(onClick = { setFavorites(app, ids, false) }) { Text(tr("Remove from favorites")) }
            AddToPlaylistButton(app, { ids })
            OutlinedButton(onClick = onTag) { Text(tr("Add tag…")) }
            OutlinedButton(onClick = onRemove) { Text(tr("Remove")) }
            TextButton(onClick = onClear) { Text(tr("Clear")) }
        }
    }
}

private fun setFavorites(app: AppState, ids: List<Long>, favorite: Boolean) = app.runTask {
    ids.forEach { app.db.setFavorite(it, favorite) }
    app.libraryChanged()
}

@Composable
private fun DetailsPanel(
    app: AppState,
    item: MediaItem,
    modifier: Modifier,
    showVideo: Boolean,
    onPickFrame: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val exists = remember(item.id, app.libraryVersion) { item.available() }
    Card(modifier, padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PreviewFrame(Modifier.fillMaxWidth().aspectRatio(16f / 9f), fold = 18.dp) {
                if (item.isVideo && !item.isRemote && exists && app.videoSupport && showVideo) {
                    VideoView(item.source, Modifier.fillMaxSize())
                } else {
                    val preview = if (item.isImage && exists) rememberPreview(item.localPath, 900, item.id) else null
                    if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.filename, color = Theme.colors.text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            InfoRow(tr("Type"), when {
                item.isWeb && item.isRemote -> tr("Website")
                item.isWeb -> tr("Web wallpaper")
                item.isShader -> tr("Shader")
                item.isRemote -> tr("Online video (streamed)")
                item.isVideo -> tr("Video")
                else -> tr("Image")
            })
            if (item.isImage || item.isVideo) InfoRow(tr("Resolution"), item.resolutionText)
            if (item.isVideo) InfoRow(tr("Duration"), item.durationText.ifEmpty { tr("Unknown") })
            if (!item.isRemote && (item.isImage || item.isVideo)) InfoRow(tr("Size"), item.sizeText)
            InfoRow(tr("Added"), item.dateAdded.take(16))
            if (item.path != null) InfoRow(tr("Folder"), shortenMiddle(Path(item.path).parent?.pathString ?: "", 40))
            item.sourceUrl?.let { InfoRow(if (item.isRemote) tr("Streams from") else tr("Downloaded from"), shortenMiddle(it, 40)) }
            if (!exists) {
                Notice(tr("The file is missing. It may have been moved or deleted."))
                TextButton(onClick = {
                    val path = if (item.isWeb) pickFolder(app.window, tr("Where is {0} now?", item.filename)) else {
                        val dialog = java.awt.FileDialog(app.window as? java.awt.Frame, tr("Where is {0} now?", item.filename), java.awt.FileDialog.LOAD)
                            .apply { file = item.localPath?.fileName?.toString() }
                        dialog.isVisible = true
                        dialog.file?.let { java.nio.file.Path.of(dialog.directory, it) }
                    }
                    if (path != null) app.runTask(tr("Found {0}", item.filename), tr("Couldn't use that file")) { app.missing.relink(item, path); app.libraryChanged() }
                }) { Text(tr("Locate the file…")) }
            }

            Spacer(Modifier.height(4.dp))
            val usable = exists && (item.isImage || item.isWeb || item.isShader || app.videoSupport)
            Button(onClick = { app.setDesktop(item) }, enabled = usable, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.isLive) tr("Set as live wallpaper") else tr("Set as desktop wallpaper"))
            }
            if (item.isWeb || item.isShader) {
                WebOptions(app, item)
                PropertyEditor(app, item, Modifier.padding(top = 6.dp))
            }
            if (item.isImage || item.isVideo) {
                OutlinedButton(
                    onClick = {
                        if (item.isVideo) {
                            onPickFrame()
                        } else {
                            app.runTask(tr("Lock screen updated"), tr("Couldn't set the lock screen")) {
                                app.changeWallpaper { app.lockScreen.setImage(item) }
                            }
                        }
                    },
                    enabled = usable && !item.isRemote, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.isVideo) tr("Pick a frame for the lock screen…") else tr("Set as lock screen")) }
            }
            if (item.isImage && !item.isRemote) MakeAliveButton(app, item, enabled = usable)
            if (item.isRemote && item.isVideo) MutedText(tr("Streams play from the internet; save the video offline to use a frame on the lock screen."))
            if (item.isVideo && !app.videoSupport) MutedText(tr("Videos need libmpv-2.dll in the app's bin folder."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    app.runTask { app.db.setFavorite(item.id, !item.favorite); app.libraryChanged() }
                }) {
                    Icon(if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                        tint = if (item.favorite) Theme.colors.star else Theme.colors.text, modifier = Modifier.size(18.dp))
                    Text(if (item.favorite) "  Favorite" else tr("  Add favorite"))
                }
                OutlinedButton(onClick = {
                    runCatching { showInExplorer(item.path!!) }.onFailure { app.toast(tr("Couldn't open Explorer"), ToastKind.Error) }
                }, enabled = exists && !item.isRemote) { Text(tr("Show in folder")) }
            }
            AddToPlaylistButton(app, { listOf(item.id) })
            TagEditor(app, item)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRemove) { Text(tr("Remove")) }
                TextButton(onClick = onDelete, enabled = exists, danger = true) { Text(tr("Delete file…")) }
            }
        }
    }
}

/** Turns the picture into a live wallpaper with an effect (zoom, parallax, rain, snow). */
@Composable
private fun MakeAliveButton(app: AppState, item: MediaItem, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(Icons.Outlined.AutoAwesome)
            Text(tr("Make it alive…"))
        }
        DropdownMenu(open, { open = false }) {
            listOf(
                "Ken Burns" to tr("Slow zoom and pan"),
                "Parallax" to tr("Leans towards the mouse"),
                "Rain on glass" to tr("Drops run down the window"),
                "Snowfall" to tr("Flakes drift down"),
            ).forEach { (effect, hint) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(tr(effect))
                            Text(hint, color = Theme.colors.textMuted, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        open = false
                        app.runTask(failure = tr("Couldn't bring the picture to life")) {
                            val alive = app.makeAlive(item, effect)
                            app.toast(tr("Showing {0}. Its settings are in the Library and on the Customize page.", alive.filename), ToastKind.Success)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
        Text(value, color = Theme.colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WebOptions(app: AppState, item: MediaItem) {
    var interactive by remember(item.id) { mutableStateOf(item.interactive) }
    var audio by remember(item.id) { mutableStateOf(item.audio) }
    fun save() = app.runTask {
        app.db.updateItemOptions(item.id, interactive, audio)
        app.libraryChanged()
        if (app.wallpapers.state.mediaId == item.id) app.changeWallpaper { app.wallpapers.restartLive() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("React to the mouse"), color = Theme.colors.text, fontSize = 13.sp)
            Text(tr("Clicks and moves on the empty desktop reach the page"), color = Theme.colors.textMuted, fontSize = 12.sp)
        }
        Switch(interactive, { on -> interactive = on; save() })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("React to audio"), color = Theme.colors.text, fontSize = 13.sp)
            Text(tr("Gets the levels of what your PC is playing"), color = Theme.colors.textMuted, fontSize = 12.sp)
        }
        Switch(audio, { on -> audio = on; save() })
    }
}
