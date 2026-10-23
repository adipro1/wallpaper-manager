package wpm.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

/** A song (or video) playing in any app that shows in Windows' media flyout. */
@Serializable
data class Track(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** The app playing it (its AppUserModelID, e.g. "Spotify.exe"). */
    val app: String = "",
    val playing: Boolean = false,
    /** Album art saved by the watcher, if the app provides one. */
    val art: String? = null,
    /** The album art's most striking color (0xRRGGBB), filled in by [NowPlaying]. */
    @Transient val color: Int? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** One line from the watcher; "{}" (or anything unreadable) means nothing is playing. */
        fun parse(line: String): Track? = runCatching { json.decodeFromString(serializer(), line) }.getOrNull()
            ?.takeIf { it.title.isNotBlank() || it.artist.isNotBlank() }
    }
}

/** Reports track changes. An interface so tests don't depend on what the PC is playing. */
interface MediaWatcher {
    fun start(onChange: (Track?) -> Unit)
    fun stop()
}

/** Runs resources/powershell/nowplaying.ps1, which prints a JSON line whenever the track changes. */
class PowerShellMediaWatcher(private val scriptDir: Path, private val artDir: Path) : MediaWatcher {
    private val log = wpm.logger("nowplaying")
    private var process: Process? = null

    private val script: Path by lazy {
        Files.createDirectories(scriptDir)
        scriptDir.resolve("nowplaying.ps1").also { target ->
            javaClass.getResourceAsStream("/powershell/nowplaying.ps1")!!.use { Files.write(target, it.readBytes()) }
        }
    }

    @Synchronized
    override fun start(onChange: (Track?) -> Unit) {
        if (process?.isAlive == true) return
        cleanArt()
        val started = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePathString(), artDir.absolutePathString(),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line -> onChange(Track.parse(line)) }
            }
            if (process === started) log.warning("The media watcher stopped (exit ${runCatching { started.exitValue() }.getOrNull()})")
        }, "now-playing").apply { isDaemon = true }.start()
        log.info("Watching what is playing")
    }

    @Synchronized
    override fun stop() {
        val running = process ?: return
        process = null
        // Closing its input ends the script's loop; it gets a moment before being killed.
        runCatching { running.outputStream.close() }
        if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        cleanArt()
        log.info("Stopped watching what is playing")
    }

    private fun cleanArt() {
        if (!Files.isDirectory(artDir)) return
        runCatching { Files.list(artDir).use { files -> files.forEach { runCatching { Files.delete(it) } } } }
    }
}

/**
 * What is playing, for wallpapers: web pages get Lively's livelyCurrentTrack(json), shader pages
 * get iMusic (the album art's color and whether music plays). The watcher only runs while
 * something wants it ([setWanted]).
 */
class NowPlaying(private val watcher: MediaWatcher, private val onChange: (Track?) -> Unit = {}) {
    private val log = wpm.logger("nowplaying")
    @Volatile var track: Track? = null
        private set
    @Volatile var running = false
        private set

    @Synchronized
    fun setWanted(wanted: Boolean) {
        if (wanted == running) return
        running = wanted
        if (wanted) {
            runCatching { watcher.start(::received) }.onFailure { log.warning("Couldn't watch what is playing: $it"); running = false }
        } else {
            watcher.stop()
            received(null)
        }
    }

    private fun received(next: Track?) {
        val previous = track
        val colored = next?.let { t ->
            // The art only changes with the song; keep the color instead of reading the picture again.
            val color = if (previous != null && previous.art == t.art) previous.color else artColor(t.art)
            t.copy(color = color)
        }
        if (colored == previous) return
        track = colored
        onChange(colored)
    }

    /** JavaScript that hands the track to a page (does nothing on pages that don't listen). */
    fun script(): String = script(track)

    companion object {
        fun artColor(art: String?): Int? = runCatching {
            val file = art?.let(Path::of)?.takeIf { it.isRegularFile() } ?: return null
            wpm.media.Imaging.load(file).use { wpm.services.dominantColor(it) }
        }.getOrNull()

        /**
         * Lively's livelyCurrentTrack gets a JSON string ("null" when nothing plays) with Title, Artist,
         * AlbumTitle and Thumbnail (base64); shader pages get wpm.setMusic([r, g, b], playing).
         */
        fun script(track: Track?): String {
            val lively = if (track == null) "null" else buildJsonObject {
                put("Title", track.title)
                put("Artist", track.artist)
                put("AlbumTitle", track.album)
                put("AlbumArtist", track.artist)
                put("Subtitle", "")
                put("PlaybackType", "Music")
                put("IsPlaying", track.playing)
                put("Thumbnail", track.art?.let(Path::of)?.takeIf { it.isRegularFile() }?.let { Base64.getEncoder().encodeToString(Files.readAllBytes(it)) })
                track.color?.let { put("Color", "#%06x".format(it)) }
            }.toString()
            val color = track?.color?.let { rgb -> listOf(rgb shr 16, rgb shr 8, rgb).joinToString(",") { n((it and 0xFF) / 255.0) } } ?: "0,0,0"
            val playing = track?.playing == true
            return "window.livelyCurrentTrack&&window.livelyCurrentTrack(${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(lively))});" +
                "window.wpm&&window.wpm.setMusic&&window.wpm.setMusic([$color],$playing);"
        }

        private fun n(value: Double) = String.format(Locale.ROOT, "%.3f", value)
    }
}
