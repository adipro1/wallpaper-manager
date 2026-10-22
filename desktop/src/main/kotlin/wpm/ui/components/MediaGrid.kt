package wpm.ui.components

import wpm.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.lerp
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
        contentPadding = PaddingValues(top = 6.dp, start = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
    val c = Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Hovering lifts the sheet and folds its corner further down; the picture leans in.
    val lift by animateFloatAsState(if (hovered) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    val fold = lerp(10.dp, 26.dp, lift)
    val shape = FoldShape(fold, 5.dp)
    val border by animateColorAsState(if (selected) c.accent else if (hovered) c.accent.copy(alpha = 0.5f) else c.cardBorder, tween(160))
    Column(
        Modifier
            .graphicsLayer { translationY = -4.dp.toPx() * lift }
            .shadow(lerp(2.dp, 14.dp, lift), shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.cardBg)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .drawWithContent {
                drawContent()
                drawFoldFlap(fold.toPx(), if (selected) c.accent else c.flap, c.shadow)
            }
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(interaction, indication = null, onClick = onClick, onDoubleClick = onDoubleClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clipToBounds().background(c.previewBg)) {
            MediaThumbnail(item, thumbs, Modifier.fillMaxSize().graphicsLayer {
                val s = 1f + 0.06f * lift
                scaleX = s
                scaleY = s
            })
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) {
                    Box(Modifier.size(22.dp).background(c.accent, CutCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, tr("Selected"), tint = c.accentText, modifier = Modifier.size(15.dp))
                    }
                }
                if (item.favorite) {
                    Box(Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), CutCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, tr("Favorite"), tint = c.star, modifier = Modifier.size(14.dp))
                    }
                }
            }
            val badge = when {
                item.isShader -> tr("Shader")
                item.isWeb && item.isRemote -> tr("Website")
                item.isWeb -> tr("Web")
                item.isRemote -> tr("Stream") + item.durationText.let { if (it.isEmpty()) "" else " · $it" }
                item.isVideo -> item.durationText.ifEmpty { tr("Video") }
                item.filename.endsWith(".gif", ignoreCase = true) -> tr("GIF")
                else -> null
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.BottomEnd).padding(8.dp), live = item.isLive)
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) c.accent else c.text,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun MediaThumbnail(item: MediaItem, thumbs: ThumbnailCache, modifier: Modifier = Modifier) {
    val bitmap = rememberThumbnail(thumbs, item)
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> {
                val shown = remember(bitmap) { Animatable(0f) }
                LaunchedEffect(bitmap) { shown.animateTo(1f, tween(280)) }
                Image(bitmap, item.filename, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop)
            }
            !item.available() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, tint = Color.Gray)
                Text(tr("File missing"), color = Color.Gray, fontSize = 12.sp)
            }
            item.isVideo -> Icon(Icons.Outlined.Movie, null, tint = Color.Gray)
        }
    }
}

/** An angular tag on a thumbnail; live wallpapers get an accent fold. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    val c = Theme.colors
    Row(
        modifier.background(Color.Black.copy(alpha = 0.62f), foldedShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(6.dp).background(c.accent, CutCornerShape(50)))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
