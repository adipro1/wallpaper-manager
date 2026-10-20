package wpm.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.data.MediaItem
import wpm.media.Imaging
import wpm.media.MediaLibrary
import java.nio.file.Path
import java.util.Collections

/** Decoded thumbnails kept in memory (least recently used dropped first). */
class ThumbnailCache(private val library: MediaLibrary, private val capacity: Int = 400) {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > capacity
    })

    fun cached(item: MediaItem): ImageBitmap? = cache[key(item)]

    /** Loads (and if needed creates) the thumbnail. Blocking; null if the file is missing or unreadable. */
    fun load(item: MediaItem): ImageBitmap? {
        cache[key(item)]?.let { return it }
        val file = library.ensureThumbnail(item) ?: return null
        return loadFile(file)?.also { cache[key(item)] = it }
    }

    private fun key(item: MediaItem) = "${item.id}:${item.thumbnail}"

    companion object {
        fun loadFile(file: Path): ImageBitmap? = runCatching { Imaging.load(file).use { it.toComposeImageBitmap() } }.getOrNull()
    }
}

/** The item's thumbnail, loaded in the background; null until ready (or if unavailable). */
@Composable
fun rememberThumbnail(cache: ThumbnailCache, item: MediaItem): ImageBitmap? {
    var bitmap by remember(item.id, item.thumbnail) { mutableStateOf(cache.cached(item)) }
    LaunchedEffect(item.id, item.thumbnail) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { cache.load(item) }
    }
    return bitmap
}

/** A full-size picture for previews, scaled down to at most [maxSize] pixels. */
@Composable
fun rememberPreview(path: Path?, maxSize: Int = 1600, key: Any? = null): ImageBitmap? {
    var bitmap by remember(path, key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, key) {
        bitmap = path?.let {
            withContext(Dispatchers.IO) {
                runCatching { Imaging.load(it).use { img -> Imaging.contain(img, maxSize, maxSize).use { s -> s.toComposeImageBitmap() } } }.getOrNull()
            }
        }
    }
    return bitmap
}
