package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
            SearchField(search, { search = it }, Modifier.width(240.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            EmptyState(
                if (search.isBlank()) tr("Nothing here yet") else tr("Nothing matches"),
                if (search.isBlank()) tr("Import wallpapers on the Library page.") else tr("Try a different search."),
            )
        } else {
            MediaGrid(items, app.thumbs, setOfNotNull(highlighted), { item, _ -> onPick(item) },
                Modifier.fillMaxWidth().heightIn(max = 560.dp), minTileWidth = 170)
        }
    }
}
