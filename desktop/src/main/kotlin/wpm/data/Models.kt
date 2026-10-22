package wpm.data

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.math.roundToLong

data class MediaItem(
    val id: Long,
    val path: String?, // local file or folder; null for streamed URLs and websites
    val filename: String, // display name
    val mediaType: String, // "image", "video", "web" or "shader"
    val width: Int?,
    val height: Int?,
    val duration: Double?, // seconds, videos only
    val fileSize: Long?,
    val favorite: Boolean,
    val dateAdded: String,
    val thumbnail: String?, // file name inside the thumbnail folder
    val sourceUrl: String? = null, // where a video or website came from
    val entry: String? = null, // web wallpapers: the page inside the folder
    val interactive: Boolean = false, // web wallpapers: pass mouse input through
    val audio: Boolean = false, // web/shader wallpapers: wants system audio levels
) {
    val isVideo: Boolean get() = mediaType == "video"
    val isImage: Boolean get() = mediaType == "image"
    val isWeb: Boolean get() = mediaType == "web"
    val isShader: Boolean get() = mediaType == "shader"
    /** Plays as a live wallpaper (video, web page or shader) rather than a Windows picture. */
    val isLive: Boolean get() = !isImage
    /** Streamed from the internet rather than played from a local file or folder. */
    val isRemote: Boolean get() = path == null

    /** What a player opens: the local file, or the URL. */
    val source: String get() = path ?: sourceUrl!!

    val localPath: Path? get() = path?.let(::Path)

    /** False when the local file or folder is gone. Streams can't be checked without loading them. */
    fun available(): Boolean = localPath?.exists() ?: true

    val resolutionText: String
        get() = if (width != null && height != null && width > 0 && height > 0) "$width × $height" else "Unknown"

    val durationText: String
        get() {
            val total = duration?.takeIf { it > 0 }?.roundToLong() ?: return ""
            val hours = total / 3600
            val minutes = total % 3600 / 60
            val seconds = total % 60
            return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }

    val sizeText: String
        get() {
            var size = (fileSize ?: 0).toDouble()
            if (size < 1024) return "%.0f B".format(Locale.ROOT, size)
            for (unit in listOf("KB", "MB")) {
                size /= 1024
                if (size < 1024) return "%.1f %s".format(Locale.ROOT, size, unit)
            }
            return "%.1f GB".format(Locale.ROOT, size / 1024)
        }
}

data class Playlist(
    val id: Long,
    val name: String,
    val mode: String, // "sequential" or "random"
    val intervalSeconds: Int,
    val created: String,
    val itemCount: Int = 0,
) {
    val intervalText: String
        get() {
            val minutes = intervalSeconds / 60
            return when {
                minutes >= 60 && minutes % 60 == 0 -> "every ${minutes / 60} h"
                minutes > 0 -> "every $minutes min"
                else -> "every $intervalSeconds s"
            }
        }
}

data class ScheduleRule(
    val id: Long,
    val time: String, // "HH:MM", or relative to the sun: "sunrise", "sunset-30" (see parseTime)
    val mediaId: Long?,
    val playlistId: Long?,
    val enabled: Boolean,
    val targetName: String = "", // file name or playlist name, filled in by the query
) {
    /** Minutes after midnight of a clock time (not for sunrise/sunset rules). */
    val minutes: Int
        get() {
            val (h, m) = time.split(":").map { it.toInt() }
            return h * 60 + m
        }
}

data class MediaStats(val total: Int, val images: Int, val videos: Int, val favorites: Int, val interactive: Int = 0)
