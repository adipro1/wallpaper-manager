package wpm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem
import wpm.ui.AppTheme
import wpm.ui.Theme

/**
 * Pick one or more library items in a separate window. With [multiple], clicks toggle
 * the selection; otherwise a click picks the item and closes the window.
 */
@Composable
fun MediaPickerDialog(
    app: AppState,
    title: String,
    multiple: Boolean,
    onDismiss: () -> Unit,
    exclude: Set<Long> = emptySet(),
    confirmLabel: String = "Add",
    onPick: (List<MediaItem>) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(search, app.libraryVersion) {
        items = withContext(Dispatchers.IO) {
            app.db.listMedia(search = search).filter { it.id !in exclude && (!it.isVideo || app.videoSupport) }
        }
    }
    DialogWindow(onCloseRequest = onDismiss, title = title, state = rememberDialogState(width = 860.dp, height = 640.dp)) {
        AppTheme(app.darkTheme) {
            Column(Modifier.fillMaxSize().background(Theme.colors.windowBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.width(280.dp))
                    Spacer(Modifier.weight(1f))
                    if (multiple) MutedText("${selected.size} selected")
                }
                if (items.isEmpty()) {
                    MutedText(if (search.isBlank()) "Nothing to add. Import wallpapers on the Library page." else "Nothing matches.")
                    Spacer(Modifier.weight(1f))
                } else {
                    MediaGrid(items, app.thumbs, selected, { item, _ ->
                        if (multiple) {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        } else {
                            onDismiss()
                            onPick(listOf(item))
                        }
                    }, Modifier.fillMaxWidth().weight(1f), minTileWidth = 160)
                }
                if (multiple) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected = items.mapTo(HashSet()) { it.id } }) { Text("Select all") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = {
                            val picked = items.filter { it.id in selected }
                            onDismiss()
                            onPick(picked)
                        }, enabled = selected.isNotEmpty()) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}
