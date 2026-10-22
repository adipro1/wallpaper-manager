package wpm.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.Page
import wpm.ToastKind
import wpm.logger
import wpm.media.Imaging
import wpm.media.OnlineWallpaper
import wpm.ui.Theme
import wpm.ui.components.Button
import wpm.ui.components.ButtonIcon
import wpm.ui.components.EmptyState
import wpm.ui.components.FilterChip
import wpm.ui.components.MutedText
import wpm.ui.components.Notice
import wpm.ui.components.OutlinedButton
import wpm.ui.components.PageScaffold
import wpm.ui.components.SearchField
import wpm.ui.components.SegmentedButton
import wpm.ui.components.SegmentedButtonDefaults
import wpm.ui.components.SingleChoiceSegmentedButtonRow
import wpm.ui.components.TextButton

private val log = logger("discover")

private val SORTS = listOf("toplist" to "Top this month", "date_added" to "Latest", "random" to "Random")
private val IDEAS = listOf("Nature", "Mountains", "Space", "City", "Ocean", "Minimal", "Forest", "Abstract", "Night", "Japan")

/** Thumbnails already loaded (shared while the app runs). */
private val thumbnailCache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
}
private val thumbnailLimit = Semaphore(6)

/** Search wallhaven.cc and add pictures to the library with one click. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverPage(app: AppState) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("toplist") }
    var screenSize by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf(emptyList<OnlineWallpaper>()) }
    var page by remember { mutableStateOf(1) }
    var lastPage by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val added = remember { mutableStateMapOf<String, Long>() }
    val scope = rememberCoroutineScope()
    val atLeast = remember {
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        "${bounds.width}x${bounds.height}"
    }

    suspend fun load(next: Int) {
        loading = true
        error = null
        try {
            val found = withContext(Dispatchers.IO) { app.online.search(submitted, sort, next, if (screenSize) atLeast else null) }
            results = if (next == 1) found.items else (results + found.items).distinctBy { it.id }
            page = found.page
            lastPage = found.lastPage
        } catch (e: Exception) {
            log.info("Search failed: $e")
            error = "Couldn't reach wallhaven.cc: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(submitted, sort, screenSize) { load(1) }

    fun download(item: OnlineWallpaper, thenSet: Boolean) {
        busy[item.id] = true
        scope.launch {
            try {
                val media = app.downloadOnline(item)
                added[item.id] = media.id
                if (thenSet) app.setDesktop(media) else app.toast("Added to your library", ToastKind.Success)
            } catch (e: Exception) {
                app.toast("Couldn't download it: ${e.message}", ToastKind.Error)
            } finally {
                busy.remove(item.id)
            }
        }
    }

    PageScaffold("Discover", "Wallpapers from wallhaven.cc, free to use. Safe-for-work only.", scroll = false) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(query, { query = it }, Modifier.width(260.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submitted = query; true } else false
            }, placeholder = "Search, then press Enter")
            SingleChoiceSegmentedButtonRow {
                SORTS.forEachIndexed { i, (key, label) ->
                    SegmentedButton(sort == key, { sort = key }, SegmentedButtonDefaults.itemShape(i, SORTS.size)) { Text(label) }
                }
            }
            FilterChip(screenSize, { screenSize = !screenSize }, label = { Text("At least $atLeast") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IDEAS.forEach { idea ->
                FilterChip(submitted.equals(idea, ignoreCase = true), {
                    query = if (submitted.equals(idea, ignoreCase = true)) "" else idea
                    submitted = query
                }, label = { Text(idea) })
            }
        }
        error?.let { Notice(it) }
        Box(Modifier.fillMaxSize()) {
            if (results.isEmpty() && !loading && error == null) {
                EmptyState("Nothing found", "Try other words, or switch off “At least $atLeast”.")
            }
            val grid = rememberLazyGridState()
            // More results when scrolled to the end.
            LaunchedEffect(grid, results.size) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
                    if (last != null && last >= results.size - 4 && !loading && page < lastPage) load(page + 1)
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(250.dp), Modifier.fillMaxSize(), state = grid,
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(results, key = { it.id }) { item ->
                    OnlineTile(app, item, busy[item.id] == true, added.containsKey(item.id),
                        onAdd = { download(item, thenSet = false) }, onSet = { download(item, thenSet = true) },
                        onOpenLibrary = { app.page = Page.Library })
                }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Theme.colors.accent)
        }
    }
}

@Composable
private fun OnlineTile(
    app: AppState, item: OnlineWallpaper, busy: Boolean, added: Boolean,
    onAdd: () -> Unit, onSet: () -> Unit, onOpenLibrary: () -> Unit,
) {
    val c = Theme.colors
    var thumb by remember(item.id) { mutableStateOf(thumbnailCache[item.thumbUrl]) }
    LaunchedEffect(item.id) {
        if (thumb != null) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) {
            thumbnailLimit.withPermit {
                runCatching { Imaging.decode(app.online.bytes(item.thumbUrl)).use { org.jetbrains.skia.Bitmap.makeFromImage(it).asComposeImageBitmap() } }
                    .onFailure { log.fine("Thumbnail ${item.thumbUrl}: $it") }.getOrNull()
            }
        }?.also { thumbnailCache[item.thumbUrl] = it }
    }
    val shape = CutCornerShape(topEnd = 14.dp)
    Column(Modifier.clip(shape).background(c.cardBg).padding(bottom = 10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(c.inset), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, item.resolutionText, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: CircularProgressIndicator(Modifier.width(24.dp), color = c.textSubtle, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.resolutionText, color = c.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            when {
                busy -> CircularProgressIndicator(Modifier.width(20.dp), color = c.accent, strokeWidth = 2.dp)
                added -> TextButton(onClick = onOpenLibrary) { Text("In library") }
                else -> {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.semantics { contentDescription = "Add to library" }) { ButtonIcon(Icons.Outlined.Download) }
                    Button(onClick = onSet) { ButtonIcon(Icons.Outlined.Wallpaper); Text("Set") }
                }
            }
        }
    }
}

private fun org.jetbrains.skia.Bitmap.asComposeImageBitmap(): ImageBitmap = org.jetbrains.skia.Image.makeFromBitmap(this).toComposeImageBitmap()

private fun org.jetbrains.skia.Bitmap.Companion.makeFromImage(image: org.jetbrains.skia.Image): org.jetbrains.skia.Bitmap {
    val bitmap = org.jetbrains.skia.Bitmap()
    bitmap.allocN32Pixels(image.width, image.height)
    image.readPixels(bitmap)
    return bitmap
}
