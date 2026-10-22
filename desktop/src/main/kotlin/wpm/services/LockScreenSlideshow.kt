package wpm.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.logger

private val log = logger("lock-slideshow")

/** The shortest time between two lock screen pictures (each change runs PowerShell). */
const val MIN_LOCK_INTERVAL_MINUTES = 15

/** The running lock screen slideshow (saved, so it continues after a restart). */
@Serializable
data class LockSlideshowState(
    @SerialName("playlist_id") val playlistId: Long? = null,
    @SerialName("interval_minutes") val intervalMinutes: Int = 60,
    val index: Int = -1,
    @SerialName("next_change") val nextChange: Double = 0.0, // epoch seconds
    val bag: List<Long> = emptyList(),
    @SerialName("media_id") val mediaId: Long? = null,
)

/**
 * Lets a playlist change the lock screen picture every so often, in the playlist's order (or
 * shuffled, everything once before repeating). Videos show a frame from early in the clip.
 */
class LockScreenSlideshow(
    private val db: Database,
    private val lockScreen: LockScreenService,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val settings = Settings(db)
    private val lock = Any()

    val state: LockSlideshowState?
        get() = settings.obj("lock_slideshow", LockSlideshowState.serializer())?.takeIf { it.playlistId != null }

    private fun save(state: LockSlideshowState?) =
        settings.set("lock_slideshow", LockSlideshowState.serializer(), state ?: LockSlideshowState())

    /** What the slideshow could show from a playlist: local pictures, and local videos when frames can be read. */
    private fun usable(item: MediaItem) =
        !item.isRemote && item.available() && (item.isImage || (item.isVideo && lockScreen.videos != null))

    /** Starts showing [playlistId] on the lock screen, the first picture right away. */
    fun start(playlistId: Long, intervalMinutes: Int): MediaItem {
        val playlist = db.getPlaylist(playlistId) ?: throw IllegalArgumentException("Playlist not found")
        require(db.playlistItems(playlistId).any(::usable)) { "“${playlist.name}” has no pictures for the lock screen" }
        synchronized(lock) { save(LockSlideshowState(playlistId, maxOf(intervalMinutes, MIN_LOCK_INTERVAL_MINUTES))) }
        log.info("Lock screen slideshow: ${playlist.name}, every $intervalMinutes min")
        return advance()
    }

    fun stop() {
        if (state != null) log.info("Lock screen slideshow stopped")
        save(null)
    }

    fun isDue(): Boolean = state?.let { clock() >= it.nextChange } ?: false

    fun secondsUntilChange(): Double? = state?.let { maxOf(0.0, it.nextChange - clock()) }

    /** Shows the next picture and restarts the timer. */
    fun advance(): MediaItem = synchronized(lock) {
        var s = state ?: throw IllegalStateException("No lock screen slideshow is running")
        val playlist = db.getPlaylist(s.playlistId!!) ?: run {
            save(null)
            throw IllegalStateException("The slideshow's playlist was deleted")
        }
        val items = db.playlistItems(playlist.id).filter(::usable)
        val next = clock() + s.intervalMinutes * 60.0
        if (items.isEmpty()) {
            save(s.copy(nextChange = next))
            throw IllegalStateException("“${playlist.name}” has no pictures for the lock screen")
        }
        var lastError: Exception? = null
        repeat(items.size) {
            val item: MediaItem
            if (playlist.mode == "random") {
                val ids = items.map { it.id }.toSet()
                var bag = s.bag.filter { it in ids }
                if (bag.isEmpty()) {
                    bag = ids.shuffled()
                    if (bag.size > 1 && bag[0] == s.mediaId) bag = bag.drop(1) + bag[0]
                }
                item = items.first { it.id == bag.first() }
                s = s.copy(bag = bag.drop(1))
            } else {
                val index = (s.index + 1) % items.size
                item = items[index]
                s = s.copy(index = index)
            }
            try {
                if (item.isVideo) lockScreen.setVideoFrame(item, frameTime(item)) else lockScreen.setImage(item)
                if (state?.playlistId == playlist.id) save(s.copy(mediaId = item.id, nextChange = next))
                log.info("Lock screen slideshow: ${item.filename}")
                return item
            } catch (e: Exception) {
                log.warning("Lock screen slideshow couldn't show ${item.filename}: $e")
                lastError = e
            }
        }
        if (state?.playlistId == playlist.id) save(s.copy(nextChange = next))
        throw lastError ?: IllegalStateException("Nothing in “${playlist.name}” could be shown")
    }

    /** A frame a little way into a video (the very first one is often black). */
    private fun frameTime(item: MediaItem): Double = (item.duration ?: 0.0).let { if (it > 4) minOf(it * 0.2, 30.0) else 0.0 }
}
