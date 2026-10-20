package wpm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wpm.data.MediaItem
import wpm.ui.Theme
import wpm.ui.ThumbnailCache
import wpm.ui.rememberThumbnail
import kotlin.io.path.Path
import kotlin.io.path.exists

/** How a tile was clicked: plain, with Ctrl (toggle) or with Shift (range). */
enum class ClickKind { Plain, Toggle, Range }

/** Responsive grid of library tiles (no tile limit - the grid is lazy). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    thumbs: ThumbnailCache,
    selected: Set<Long>,
    onClick: (MediaItem, ClickKind) -> Unit,
    modifier: Modifier = Modifier,
    onDoubleClick: (MediaItem) -> Unit = {},
    state: LazyGridState = rememberLazyGridState(),
    minTileWidth: Int = 190,
) {
    val keys = LocalWindowInfo.current.keyboardModifiers
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minTileWidth.dp),
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            MediaTile(
                item, thumbs, item.id in selected,
                onClick = {
                    val kind = when {
                        keys.isShiftPressed -> ClickKind.Range
                        keys.isCtrlPressed -> ClickKind.Toggle
                        else -> ClickKind.Plain
                    }
                    onClick(item, kind)
                },
                onDoubleClick = { onDoubleClick(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(item: MediaItem, thumbs: ThumbnailCache, selected: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.clip(shape)
            .border(if (selected) BorderStroke(2.dp, Theme.colors.accent) else BorderStroke(1.dp, Theme.colors.cardBorder), shape)
            .background(Theme.colors.cardBg)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Theme.colors.previewBg)) {
            MediaThumbnail(item, thumbs, Modifier.fillMaxSize())
            if (item.favorite) {
                Icon(Icons.Filled.Star, "Favorite", tint = Theme.colors.star, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
            }
            val badge = when {
                item.isVideo -> item.durationText.ifEmpty { "Video" }
                item.filename.endsWith(".gif", ignoreCase = true) -> "GIF"
                else -> null
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Text(
            item.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Theme.colors.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun MediaThumbnail(item: MediaItem, thumbs: ThumbnailCache, modifier: Modifier = Modifier) {
    val bitmap = rememberThumbnail(thumbs, item)
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, item.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !Path(item.path).exists() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, tint = Color.Gray)
                Text("File missing", color = Color.Gray, fontSize = 12.sp)
            }
            item.isVideo -> Icon(Icons.Outlined.Movie, null, tint = Color.Gray)
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 11.sp,
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
