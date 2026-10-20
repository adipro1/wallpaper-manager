package wpm.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    private fun tone(freq: Double, rate: Int = 48_000, amplitude: Double = 0.8) =
        FloatArray(1024) { (amplitude * sin(2 * PI * freq * it / rate)).toFloat() }

    @Test
    fun silenceIsZero() {
        val levels = Spectrum(48_000).analyze(FloatArray(1024))
        assertEquals(AUDIO_BANDS, levels.size)
        assertTrue(levels.all { it == 0f })
    }

    @Test
    fun toneLightsUpItsBand() {
        val spectrum = Spectrum(48_000)
        val bass = spectrum.analyze(tone(60.0))
        val treble = Spectrum(48_000).analyze(tone(8_000.0))
        val bassPeak = bass.indices.maxBy { bass[it] }
        val treblePeak = treble.indices.maxBy { treble[it] }
        assertTrue(bassPeak < AUDIO_BANDS / 4, "60 Hz peaks in the bass (band $bassPeak)")
        assertTrue(treblePeak > AUDIO_BANDS * 3 / 4, "8 kHz peaks in the treble (band $treblePeak)")
        assertTrue(bass[bassPeak] > 0.8f, "a loud tone is near full scale (${bass[bassPeak]})")
    }

    @Test
    fun levelsFallBackSmoothly() {
        val spectrum = Spectrum(48_000)
        val loud = spectrum.analyze(tone(1_000.0)).max()
        val after = spectrum.analyze(FloatArray(1024)).max()
        assertTrue(after in 0.5f * loud..loud, "drops gradually, not to zero at once ($loud -> $after)")
    }

    /** Listens to the real speakers for a moment. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun capturesSystemAudio() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val received = CopyOnWriteArrayList<FloatArray>()
        val capture = AudioCapture { received += it }
        capture.start()
        Thread.sleep(2_500)
        capture.stop()
        println("received ${received.size} updates, loudest ${received.maxOfOrNull { it.max() }}")
        assertTrue(received.size >= 30, "about 30 updates a second")
        assertTrue(received.all { it.size == AUDIO_BANDS })
    }

    /** Lively-style pages receive the levels. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun deliversLevelsToLivelyListener() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val dir = Files.createTempDirectory("wpm-audio")
        val page = dir.resolve("index.html").also {
            it.writeText("<html><script>function livelyAudioListener(a){document.title='bands:'+a.length+':'+a[5];}</script></html>")
        }
        val player = WebLivePlayer(Path.of("build/chromium-test").toAbsolutePath())
        try {
            player.play(LiveSource(LiveKind.Web, page.toUri().toString(), audio = true))
            Thread.sleep(1_500)
            assertTrue(player.wantsAudio)
            player.deliverAudio(FloatArray(AUDIO_BANDS) { if (it == 5) 0.5f else 0f })
            Thread.sleep(500)
            assertEquals("bands:128:0.5", player.pageTitle)
        } finally {
            player.stop()
            dir.toFile().deleteRecursively()
        }
    }
}
