package wpm.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import wpm.AppState
import wpm.data.Playlist

/** "Add to playlist" button with a menu of playlists plus "New playlist…". */
@Composable
fun AddToPlaylistButton(app: AppState, mediaIds: () -> List<Long>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(emptyList<Playlist>()) }
    var naming by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { playlists = app.db.listPlaylists(); open = true }) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null)
            Text("  Add to playlist")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            playlists.forEach { playlist ->
                DropdownMenuItem(text = { Text("${playlist.name}  (${playlist.itemCount})") }, onClick = {
                    open = false
                    val ids = mediaIds()
                    app.runTask("Added ${ids.size} to ${playlist.name}") {
                        app.db.addToPlaylist(playlist.id, ids)
                        app.libraryChanged()
                    }
                })
            }
            if (playlists.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(text = { Text("New playlist…") }, onClick = { open = false; naming = true })
        }
    }
    if (naming) {
        TextInputDialog("New playlist", "Name", confirm = "Create", onDismiss = { naming = false }, onConfirm = { name ->
            val ids = mediaIds()
            app.runTask("Created $name with ${ids.size} wallpapers") {
                val id = app.db.createPlaylist(name)
                app.db.addToPlaylist(id, ids)
                app.libraryChanged()
            }
        })
    }
}
