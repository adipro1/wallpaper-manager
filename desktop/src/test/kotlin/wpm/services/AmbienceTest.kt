package wpm.services

import wpm.AppPaths
import wpm.data.Database
import wpm.data.Settings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbienceTest {
    private val dir: Path = Files.createTempDirectory("wpm-ambience")
    private val db = Database(AppPaths(dir.resolve("data")).ensure().dbPath)
    private val settings = Settings(db)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** Remembers what it was told instead of playing. */
    private class FakePlayer : SoundPlayer {
        var file: Path? = null
        var volume = 0.0
        val played = mutableListOf<String>()
        override fun play(file: Path) { this.file = file; played += file.fileName.toString() }
        override fun volume(percent: Double) { volume = percent }
        override fun stop() { file = null }
    }

    @Test
    fun theSoundFollowsTheWeather() {
        fun pick(weather: Weather, sun: Double = 30.0) = Ambience.choose(Ambience.WEATHER, AmbientState(weather, sun))
        assertEquals(AmbientSound.Storm, pick(weatherFromCode(95)))
        assertEquals(AmbientSound.Rain, pick(weatherFromCode(61)))
        assertEquals(AmbientSound.Rain, pick(weatherFromCode(51)), "drizzle")
        assertEquals(AmbientSound.Wind, pick(weatherFromCode(73)), "snow")
        assertEquals(AmbientSound.Wind, pick(weatherFromCode(45)), "fog")
        assertEquals(AmbientSound.Breeze, pick(weatherFromCode(0)), "a clear day")
        assertEquals(AmbientSound.Crickets, pick(weatherFromCode(0).copy(temperature = 18.0), sun = -20.0), "a warm clear night")
        assertEquals(AmbientSound.Fire, pick(weatherFromCode(1).copy(temperature = -3.0), sun = -20.0), "a cold clear night")
        assertEquals(AmbientSound.Ocean, Ambience.choose("Ocean", AmbientState(weatherFromCode(95))), "a chosen sound stays")
        assertNull(Ambience.choose("Nonsense", AmbientState()))
    }

    @Test
    fun fadesInQuietensUnderMusicAndStopsWhenNobodyListens() {
        val player = FakePlayer()
        val ambience = Ambience(settings, dir.resolve("sounds"), player)
        ambience.setChoice("Rain")
        ambience.update(AmbientState())
        repeat(10) { ambience.step(1.0) }
        assertNull(player.file, "off by default")

        ambience.setEnabled(true)
        ambience.setVolume(60)
        ambience.update(AmbientState())
        ambience.step(1.0)
        assertTrue(player.file.toString().endsWith("rain-1.wav"), "${player.file}")
        assertTrue(Files.size(player.file!!) > 1_000_000, "a loop of real sound")
        assertTrue(player.volume < 60, "it fades in: ${player.volume}")
        repeat(5) { ambience.step(1.0) }
        assertEquals(60.0, player.volume)

        ambience.update(AmbientState(musicPlaying = true))
        repeat(5) { ambience.step(1.0) }
        assertEquals(15.0, player.volume, "a quarter as loud under music")

        // Another sound: the rain fades out first, then the new one starts.
        ambience.setChoice("Ocean")
        ambience.update(AmbientState())
        ambience.step(0.1)
        assertTrue(player.file.toString().endsWith("rain-1.wav"), "still fading out")
        repeat(10) { ambience.step(1.0) }
        assertTrue(player.file.toString().endsWith("ocean-1.wav"), "${player.file}")
        assertEquals(AmbientSound.Ocean, ambience.playing)

        ambience.update(AmbientState(quiet = true))
        repeat(10) { ambience.step(1.0) }
        assertNull(player.file, "silence (and no player) while the PC is locked")
        assertNull(ambience.playing)
        ambience.update(AmbientState())
        repeat(10) { ambience.step(1.0) }
        assertEquals(60.0, player.volume, "back afterwards")
        assertEquals(listOf("rain-1.wav", "ocean-1.wav", "ocean-1.wav"), player.played)
    }

    @Test
    fun everySoundLoopsWithoutAClick() {
        for (sound in AmbientSound.entries) {
            val samples = SoundSynth.render(sound, seconds = 6)
            assertEquals(6 * SoundSynth.RATE * 2, samples.size)
            val level = SoundSynth.level(samples)
            assertTrue(level in -26.0..-16.0, "$sound is about as loud as the others: $level dB")
            val (loop, usual) = SoundSynth.loopJump(samples)
            assertTrue(loop <= maxOf(usual, 0.02), "$sound has no click where it loops: $loop vs $usual")
            assertTrue(samples.all { it in -1f..1f })
        }
        val wav = SoundSynth.wav(FloatArray(4))
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + 8, wav.size)
    }

    /** mpv plays a made loop (at volume 0, so nothing is heard). Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun mpvPlaysTheLoop() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1" && wpm.mpv.Mpv.available, "desktop test not requested")
        val file = Ambience(settings, dir.resolve("sounds"), FakePlayer()).file(AmbientSound.Stream)
        val mpv = wpm.mpv.MpvPlayer(mapOf("vid" to "no", "vo" to "null", "loop-file" to "inf", "volume" to "0", "idle" to "yes", "config" to "no"))
        try {
            mpv.command("loadfile", file.toString())
            Thread.sleep(1_500)
            assertEquals(file.toString(), mpv["path"])
            assertTrue((mpv["time-pos"]?.toDoubleOrNull() ?: 0.0) > 0.3, "it plays: ${mpv["time-pos"]}")
            assertEquals("2", mpv["audio-params/channel-count"])
        } finally {
            mpv.close()
        }
    }
}
