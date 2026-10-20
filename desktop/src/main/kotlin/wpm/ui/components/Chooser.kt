package wpm.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem

/** Card with a searchable grid of library items; clicking one picks it. */
@Composable
fun ChooserCard(
    app: AppState,
    title: String,
    onPick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    filter: (MediaItem) -> Boolean = { true },
    highlighted: Long? = null,
) {
    var search by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    LaunchedEffect(search, app.libraryVersion) {
        items = withContext(Dispatchers.IO) { app.db.listMedia(search = search).filter(filter) }
    }
    Card(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title)
            Spacer(Modifier.weight(1f))
            OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.width(240.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            MutedText(if (search.isBlank()) "Nothing in your library yet. Import wallpapers on the Library page." else "Nothing matches.")
        } else {
            MediaGrid(items, app.thumbs, setOfNotNull(highlighted), { item, _ -> onPick(item) },
                Modifier.fillMaxWidth().heightIn(max = 560.dp), minTileWidth = 170)
        }
    }
}
