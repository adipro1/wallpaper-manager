package wpm.services

import wpm.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Playlist
import wpm.data.Settings
import wpm.logger
import java.io.FileNotFoundException
import kotlin.io.path.Path
import kotlin.io.path.exists

private val log = logger("playlists")

const val MIN_INTERVAL_SECONDS = 60

/** The running playlist (same JSON as version 1.x; {} when none is running). */
@Serializable
data class PlaylistState(
    val id: Long? = null,
    val index: Int = -1,
    @SerialName("next_change") val nextChange: Double = 0.0, // epoch seconds
    val bag: List<Long> = emptyList(),
    @SerialName("media_id") val mediaId: Long? = null,
)

/**
 * Changes the desktop wallpaper at a fixed interval. The running playlist is stored in
 * the settings, so it keeps going (and remembers its position) after a restart.
 * Random mode uses a "shuffle bag": every wallpaper is shown once before any repeats.
 */
class PlaylistPlayer(
    private val db: Database,
    private val wallpapers: WallpaperService,
    /** Where the user is, for playlists that follow the sun (null: a 07:00-19:00 day). */
    private val place: () -> Place? = { null },
    private val zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val settings = Settings(db)
    private val lock = Any() // advance() can be called from the timer and from the UI

    val state: PlaylistState?
        get() = settings.obj("active_playlist", PlaylistState.serializer())?.takeIf { it.id != null }

    private fun save(state: PlaylistState?) = settings.set("active_playlist", PlaylistState.serializer(), state ?: PlaylistState())

    /** advance() runs in the background; if the user stopped (or switched) the playlist meanwhile, saving would restart it. */
    private fun saveIfStillRunning(playlistId: Long, state: PlaylistState) {
        if (this.state?.id == playlistId) save(state)
    }

    fun activePlaylist(): Playlist? {
        val s = state ?: return null
        val playlist = db.getPlaylist(s.id!!)
        if (playlist == null) save(null) // deleted while running
        return playlist
    }

    fun secondsUntilChange(): Double? = state?.let { maxOf(0.0, it.nextChange - clock()) }

    fun currentMediaId(): Long? = state?.mediaId

    /** Starts a playlist: shows its first wallpaper right away. */
    fun start(playlistId: Long): MediaItem {
        val playlist = db.getPlaylist(playlistId) ?: throw IllegalArgumentException(tr("Playlist not found"))
        require(db.playlistItems(playlistId).isNotEmpty()) { tr("“{0}” is empty - add some wallpapers first.", playlist.name) }
        synchronized(lock) { save(PlaylistState(id = playlistId)) }
        log.info("Started playlist ${playlist.name}")
        return advance()
    }

    fun stop() {
        if (state != null) log.info("Stopped playlist")
        save(null)
    }

    fun isDue(): Boolean = state?.let { clock() >= it.nextChange } ?: false

    /** How many of a "follow the sun" playlist's pictures are for the day (by default the first half). */
    fun dayCount(playlistId: Long, size: Int): Int =
        settings.string(sunDayCountKey(playlistId), "").toIntOrNull()?.coerceIn(0, size) ?: ((size + 1) / 2)

    fun setDayCount(playlistId: Long, count: Int) = settings.set(sunDayCountKey(playlistId), count.coerceAtLeast(0).toString())

    /** Shows the next wallpaper of the running playlist and restarts the timer. */
    fun advance(): MediaItem = synchronized(lock) {
        var s = state ?: throw IllegalStateException(tr("No playlist is running"))
        val playlist = activePlaylist() ?: throw IllegalStateException(tr("No playlist is running"))
        val items = db.playlistItems(playlist.id)
        if (items.isEmpty()) {
            save(null)
            throw IllegalStateException(tr("“{0}” is empty", playlist.name))
        }
        if (playlist.mode == "sun") return followSun(playlist, items, s)
        val interval = maxOf(playlist.intervalSeconds, MIN_INTERVAL_SECONDS)

        // Try each item at most once, skipping files that have gone missing.
        var lastError: Exception? = null
        repeat(items.size) {
            val (item, next) = pickNext(playlist, items, s)
            s = next
            if (!item.available()) {
                log.warning("Skipping missing playlist item ${item.path}")
                return@repeat
            }
            try {
                wallpapers.set(item)
            } catch (e: Exception) { // try the next one
                log.warning("Could not show ${item.path}: $e")
                lastError = e
                return@repeat
            }
            saveIfStillRunning(playlist.id, s.copy(mediaId = item.id, nextChange = clock() + interval))
            log.info("Playlist ${playlist.name}: showing ${item.filename}")
            return item
        }
        // Nothing could be shown; don't keep retrying every few seconds.
        saveIfStillRunning(playlist.id, s.copy(nextChange = clock() + interval))
        throw lastError ?: FileNotFoundException(tr("None of the files in “{0}” can be found", playlist.name))
    }

    /** Shows the picture for this time of day; the next change comes when the sun moves on. */
    private fun followSun(playlist: Playlist, items: List<MediaItem>, s: PlaylistState): MediaItem {
        val now = java.time.Instant.ofEpochMilli((clock() * 1000).toLong())
        val slot = sunSlot(now, place(), zone(), items.size, dayCount(playlist.id, items.size))
        val item = items[slot.index]
        val next = s.copy(index = slot.index, mediaId = item.id, nextChange = slot.until.toEpochMilli() / 1000.0)
        if (item.id != wallpapers.currentItem()?.id || wallpapers.state.kind == null) {
            try {
                if (!item.available()) throw FileNotFoundException(tr("File not found: {0}", item.path))
                wallpapers.set(item)
            } catch (e: Exception) {
                // Try again at the next change rather than every few seconds.
                saveIfStillRunning(playlist.id, next)
                throw e
            }
            log.info("Playlist ${playlist.name}: showing ${item.filename} until ${slot.until}")
        }
        saveIfStillRunning(playlist.id, next)
        return item
    }

    /** Chooses the next item and returns the updated state (index / shuffle bag). */
    private fun pickNext(playlist: Playlist, items: List<MediaItem>, state: PlaylistState): Pair<MediaItem, PlaylistState> {
        if (playlist.mode == "random") {
            val ids = items.map { it.id }.toSet()
            var bag = state.bag.filter { it in ids }
            if (bag.isEmpty()) { // refill, avoiding an immediate repeat of the current item
                bag = ids.shuffled()
                if (bag.size > 1 && bag[0] == state.mediaId) bag = bag.drop(1) + bag[0]
            }
            val nextId = bag.first()
            return items.first { it.id == nextId } to state.copy(bag = bag.drop(1))
        }
        val index = (state.index + 1) % items.size
        return items[index] to state.copy(index = index)
    }
}
