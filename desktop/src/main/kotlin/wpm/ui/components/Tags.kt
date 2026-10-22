package wpm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.ToastKind
import wpm.data.MediaItem
import wpm.ui.Theme
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** An item's tags, each removable, and a field to add one (Enter adds it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(app: AppState, item: MediaItem) {
    var tags by remember(item.id) { mutableStateOf(emptyList<String>()) }
    var text by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id, app.libraryVersion) { tags = withContext(Dispatchers.IO) { app.db.tagsOf(item.id) } }
    fun add() {
        val name = text.trim()
        if (name.isEmpty()) return
        text = ""
        app.runTask(failure = "Couldn't add the tag") {
            app.db.addTag(listOf(item.id), name)
            app.libraryChanged()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TAGS", color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall)
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    FilterChip(selected = true, onClick = {
                        app.runTask { app.db.removeTag(item.id, tag); app.libraryChanged() }
                    }, label = { Text(tag) }, leadingIcon = { Icon(Icons.Outlined.Close, "Remove $tag", Modifier.size(14.dp)) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(text, { text = it.take(40) }, Modifier.weight(1f).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { add(); true } else false
            })
            OutlinedButton(onClick = ::add, enabled = text.isNotBlank()) { Text("Add tag") }
        }
    }
}

/** Library items whose files can't be found: find them again (one by one or in a folder) or remove them. */
@Composable
fun MissingFilesDialog(app: AppState, missing: List<MediaItem>, onDismiss: () -> Unit) {
    fun locate(item: MediaItem) {
        val path: Path? = if (item.isWeb) pickFolder(app.window, "Where is ${item.filename} now?") else {
            val dialog = FileDialog(app.window as? Frame, "Where is ${item.filename} now?", FileDialog.LOAD).apply {
                file = item.localPath?.fileName?.toString()
            }
            dialog.isVisible = true
            dialog.file?.let { Path.of(dialog.directory, it) }
        }
        path ?: return
        app.runTask("Found ${item.filename}", "Couldn't use that file") {
            app.missing.relink(item, path)
            app.libraryChanged()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${missing.size} missing wallpaper${if (missing.size == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText("These were moved, renamed or deleted, or are on a drive that isn't connected. If you moved a folder, " +
                    "search it and they are found by name.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(missing, key = { it.id }) { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.filename, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.path ?: "", color = Theme.colors.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { locate(item) }) { Text("Locate…") }
                            TextButton(onClick = {
                                app.runTask { app.library.deleteThumbnail(item); app.db.removeMedia(item.id); app.libraryChanged() }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val folder = pickFolder(app.window, "Search a folder for the missing files") ?: return@Button
                app.runTask(failure = "Couldn't search the folder") {
                    val found = app.missing.searchFolder(folder, missing)
                    app.libraryChanged()
                    app.toast(if (found > 0) "Found $found of ${missing.size}" else "None of them are in that folder", if (found > 0) ToastKind.Success else ToastKind.Info)
                }
            }) { Text("Search a folder…") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    app.runTask("Removed ${missing.size} from the library") {
                        missing.forEach { app.library.deleteThumbnail(it); app.db.removeMedia(it.id) }
                        app.libraryChanged()
                    }
                }, danger = true) { Text("Remove all") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
