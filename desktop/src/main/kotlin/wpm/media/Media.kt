package wpm.media

import org.jetbrains.skia.Image
import wpm.data.Database
import wpm.data.MediaItem
import wpm.logger
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

private val log = logger("media")

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v")
const val THUMB_WIDTH = 400 // 16:9, shown at roughly half size in the library grid
const val THUMB_HEIGHT = 225

fun mediaTypeFor(path: String): String? = when (Path(path).extension.lowercase()) {
    in IMAGE_EXTENSIONS -> "image"
    in VIDEO_EXTENSIONS -> "video"
    else -> null
}

/** All supported files in a folder and its sub-folders. */
fun findMediaFiles(folder: Path): List<Path> =
    Files.walk(folder).use { stream ->
        stream.asSequence().filter { it.isRegularFile() && mediaTypeFor(it.toString()) != null }.sorted().toList()
    }

data class MediaInfo(val mediaType: String, val width: Int?, val height: Int?, val duration: Double? = null)

/** Reads video metadata and frames; provided by the video engine (libmpv). */
interface VideoReader {
    fun probe(path: Path): MediaInfo
    /** The frame shown at [seconds] into the video. */
    fun frame(path: Path, seconds: Double): Image
}

class ImportResult {
    val added = mutableListOf<Long>()
    var skipped = 0 // already in the library or unsupported
    val failed = mutableListOf<Pair<String, String>>() // (path, reason)
}

/** Short, user-friendly reason why a file could not be read. */
fun describeError(e: Throwable): String = when (e) {
    is NoSuchFileException, is FileNotFoundException -> "file not found"
    is AccessDeniedException -> "access denied"
    is IllegalArgumentException -> if (e.message?.contains("decode", true) == true) "not a valid image file" else e.message ?: "unreadable"
    else -> e.message ?: e.javaClass.simpleName
}

/** Metadata, thumbnails and importing. The media files themselves are never copied or moved. */
class MediaLibrary(private val db: Database, val thumbDir: Path, var videos: VideoReader? = null) {

    fun probe(path: Path): MediaInfo = when (mediaTypeFor(path.toString())) {
        "image" -> {
            val (w, h) = try {
                Imaging.size(path)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Can't decode: not a valid image file", e)
            }
            MediaInfo("image", w, h)
        }
        "video" -> (videos ?: throw IllegalStateException("video playback isn't available")).probe(path)
        else -> throw IllegalArgumentException("Unsupported file type: $path")
    }

    /** Thumbnail file name from the media path and modification time, so an edited file gets a fresh one. */
    fun thumbnailName(path: Path): String {
        val mtime = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0)
        return thumbnailNameFor("${path.toAbsolutePath().toString().lowercase()}|$mtime")
    }

    /** Thumbnail file name for any key (e.g. a URL). */
    fun thumbnailNameFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24) + ".jpg"
    }

    /** Writes a 16:9 JPEG thumbnail and returns its file name. */
    fun createThumbnail(path: Path, mediaType: String, duration: Double?): String {
        val source = if (mediaType == "video") {
            // Skip a little into the video - first frames are often black.
            val at = duration?.let { minOf(it * 0.1, 10.0) } ?: 0.0
            (videos ?: error("video playback isn't available")).frame(path, at)
        } else {
            Imaging.load(path)
        }
        val name = thumbnailName(path)
        source.use { Imaging.cover(it, THUMB_WIDTH, THUMB_HEIGHT).use { thumb -> Imaging.saveJpeg(thumb, thumbDir.resolve(name), 85) } }
        return name
    }

    /** The item's thumbnail, recreated if it was deleted. Null when the media file is missing or unreadable. */
    fun ensureThumbnail(item: MediaItem): Path? {
        item.thumbnail?.let { name -> thumbDir.resolve(name).takeIf { it.exists() }?.let { return it } }
        val path = item.localPath ?: return null // streams get their thumbnail when added
        if (!path.exists()) return null
        if (item.isVideo && videos == null) return null
        return try {
            val name = createThumbnail(path, item.mediaType, item.duration)
            db.setThumbnail(item.id, name)
            thumbDir.resolve(name)
        } catch (e: Exception) {
            log.warning("Could not create thumbnail for ${item.path}: $e")
            null
        }
    }

    fun deleteThumbnail(item: MediaItem) {
        item.thumbnail?.let { runCatching { Files.deleteIfExists(thumbDir.resolve(it)) } }
    }

    /** Adds files to the library (metadata + thumbnail). [progress] gets (index, total, path). */
    fun importFiles(paths: List<Path>, progress: ((Int, Int, Path) -> Unit)? = null): ImportResult {
        val result = ImportResult()
        paths.forEachIndexed { i, raw ->
            val path = raw.toAbsolutePath().normalize()
            progress?.invoke(i + 1, paths.size, path)
            if (mediaTypeFor(path.toString()) == null || db.hasMedia(path.toString())) {
                result.skipped++
                return@forEachIndexed
            }
            try {
                val info = probe(path)
                val thumb = createThumbnail(path, info.mediaType, info.duration)
                val id = db.addMedia(path.toString(), info.mediaType, info.width, info.height, info.duration, path.fileSize(), thumb)
                if (id == null) result.skipped++ else result.added += id
            } catch (e: Exception) {
                log.warning("Failed to import $path: $e")
                result.failed += path.toString() to describeError(e)
            }
        }
        log.info("Import finished: ${result.added.size} added, ${result.skipped} skipped, ${result.failed.size} failed")
        return result
    }
}
