package wpm.data

import java.util.Locale
import kotlin.math.roundToLong

data class MediaItem(
    val id: Long,
    val path: String,
    val filename: String,
    val mediaType: String, // "image" or "video"
    val width: Int?,
    val height: Int?,
    val duration: Double?, // seconds, videos only
    val fileSize: Long?,
    val favorite: Boolean,
    val dateAdded: String,
    val thumbnail: String?, // file name inside the thumbnail folder
) {
    val isVideo: Boolean get() = mediaType == "video"

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
    val time: String, // "HH:MM"
    val mediaId: Long?,
    val playlistId: Long?,
    val enabled: Boolean,
    val targetName: String = "", // file name or playlist name, filled in by the query
) {
    val minutes: Int
        get() {
            val (h, m) = time.split(":").map { it.toInt() }
            return h * 60 + m
        }
}

data class MediaStats(val total: Int, val images: Int, val videos: Int, val favorites: Int)
