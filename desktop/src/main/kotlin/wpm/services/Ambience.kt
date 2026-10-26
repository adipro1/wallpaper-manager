package wpm.services

import wpm.data.Settings
import wpm.logger
import wpm.mpv.MpvPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

private val log = logger("ambience")

/** Plays a sound file over and over at a volume (0..100). An interface so tests stay silent. */
interface SoundPlayer {
    fun play(file: Path)
    fun volume(percent: Double)
    fun stop()
    fun close() {}
}

/** Sound through libmpv (already used for videos): no window, the file looped. */
class MpvSoundPlayer : SoundPlayer {
    private val mpv by lazy {
        MpvPlayer(mapOf(
            "vid" to "no", "vo" to "null", "audio-display" to "no", "loop-file" to "inf", "volume" to "0",
            "idle" to "yes", "config" to "no", "terminal" to "no", "osc" to "no", "input-default-bindings" to "no",
        ))
    }
    override fun play(file: Path) = mpv.command("loadfile", file.toString())
    override fun volume(percent: Double) { mpv["volume"] = String.format(java.util.Locale.ROOT, "%.1f", percent) }
    override fun stop() = mpv.command("stop")
    override fun close() = mpv.close()
}

/** What the sound should follow right now. */
data class AmbientState(
    val weather: Weather? = null,
    /** The sun's height in degrees, or null without a place. */
    val sunElevation: Double? = null,
    /** Music plays: the sound gets quiet under it. */
    val musicPlaying: Boolean = false,
    /** Nobody listens (locked, screen off, a full-screen game or film, the screensaver): silence. */
    val quiet: Boolean = false,
    /** Focus mode asks for its own sound (or null for the usual one). */
    val focusSound: AmbientSound? = null,
)

/**
 * A soft sound in the background that matches the wallpaper's world: the weather outside
 * ("follow the weather") or one chosen sound. It fades in and out, gets quiet under music and
 * stops when nobody is there. Off by default.
 */
class Ambience(
    private val settings: Settings,
    private val cacheDir: Path,
    private val player: SoundPlayer = MpvSoundPlayer(),
) {
    val enabled: Boolean get() = settings.boolean("ambient", false)
    /** 0..100. */
    val volume: Int get() = settings.string("ambient_volume", "35").toIntOrNull()?.coerceIn(0, 100) ?: 35
    /** "weather" or an [AmbientSound] name. */
    val choice: String get() = settings.string("ambient_sound", WEATHER)

    /** The sound playing (or fading out) now, for the UI. */
    @Volatile var playing: AmbientSound? = null
        private set
    @Volatile private var target: AmbientSound? = null
    @Volatile private var targetVolume = 0.0
    private var current = 0.0
    private var lastState = AmbientState()

    fun setEnabled(on: Boolean) { settings.set("ambient", on); update(lastState) }
    fun setVolume(value: Int) { settings.set("ambient_volume", value.coerceIn(0, 100).toString()); update(lastState) }
    fun setChoice(value: String) { settings.set("ambient_sound", value); update(lastState) }

    /** Decides what should play (call it every second or so). */
    fun update(state: AmbientState) {
        lastState = state
        val sound = if (!enabled) null else state.focusSound ?: choose(choice, state)
        target = sound
        targetVolume = when {
            sound == null || state.quiet -> 0.0
            state.musicPlaying -> volume * 0.25
            else -> volume.toDouble()
        }
    }

    /**
     * Moves the volume a step towards where it should be ([seconds] since the last step): a full
     * fade takes about three seconds. A different sound first fades the old one out.
     */
    @Synchronized
    fun step(seconds: Double) {
        val want = target
        val step = 100.0 / 3 * seconds
        try {
            if (playing != want) {
                if (playing != null && current > 0.5) {
                    current = (current - step).coerceAtLeast(0.0)
                    player.volume(current)
                    return
                }
                if (playing != null) player.stop()
                playing = null
                if (want == null || targetVolume <= 0) return
                player.volume(0.0)
                player.play(file(want))
                playing = want
                current = 0.0
            }
            if (playing == null) return
            if (current != targetVolume) {
                current = if (current < targetVolume) (current + step).coerceAtMost(targetVolume) else (current - step).coerceAtLeast(targetVolume)
                player.volume(current)
            }
            // Silent for good (switched off, or nobody there): free the player.
            if (current <= 0.0 && targetVolume <= 0.0) {
                player.stop()
                playing = null
            }
        } catch (e: Exception) {
            log.warning("Ambient sound: $e")
            playing = null
            target = null
        }
    }

    /** The sound's loop, made the first time it is needed. */
    fun file(sound: AmbientSound): Path {
        val file = cacheDir.resolve("${sound.name.lowercase()}-$VERSION.wav")
        if (!file.exists()) {
            Files.createDirectories(cacheDir)
            val temp = cacheDir.resolve("${sound.name.lowercase()}.tmp")
            Files.write(temp, SoundSynth.wav(SoundSynth.render(sound)))
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return file
    }

    fun close() = runCatching { player.close() }

    companion object {
        const val WEATHER = "weather"
        /** Bump when the sounds change, so old loops are made again. */
        private const val VERSION = 1

        /**
         * The sound for [choice]: a fixed one, or one that follows the weather: a storm, rain,
         * wind in snow and fog, crickets on warm clear nights, a fire on cold ones, a breeze by day.
         */
        fun choose(choice: String, state: AmbientState): AmbientSound? {
            if (choice != WEATHER) return AmbientSound.entries.firstOrNull { it.name == choice }
            val w = state.weather ?: Weather()
            val night = (state.sunElevation ?: 10.0) < -6
            return when {
                w.storm > 0.3 -> AmbientSound.Storm
                w.rain > 0.05 -> AmbientSound.Rain
                w.snow > 0.05 || w.fog > 0.5 -> AmbientSound.Wind
                night && (w.temperature ?: 15.0) >= 12 -> AmbientSound.Crickets
                night && (w.temperature ?: 15.0) < 5 -> AmbientSound.Fire
                else -> AmbientSound.Breeze
            }
        }
    }
}
