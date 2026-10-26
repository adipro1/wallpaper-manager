package wpm.services

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** The ambient sounds the app can play. */
enum class AmbientSound(val label: String) {
    Rain("Rain"), Storm("Thunderstorm"), Wind("Wind"), Breeze("Breeze"), Ocean("Ocean waves"),
    Stream("Stream"), Fire("Fireplace"), Crickets("Crickets at night"), BrownNoise("Brown noise");
}

/**
 * Makes the ambient sounds from noise and little events (drops, crackles, chirps), the way sound
 * designers do: nothing to download and no recordings to license. Each sound is a loop that
 * joins up without a click (its end is cross-faded into its start).
 */
object SoundSynth {
    const val RATE = 32_000

    /** [seconds] of [sound] as interleaved stereo samples (-1..1), seamless when looped. */
    fun render(sound: AmbientSound, seconds: Int = 40, seed: Long = sound.ordinal.toLong()): FloatArray {
        val fade = RATE * 3
        val n = seconds * RATE
        val left = FloatArray(n + fade)
        val right = FloatArray(n + fade)
        Voice(left, right, Random(seed)).apply { draw(sound) }
        normalize(left, right)
        // The extra seconds at the end fade into the start (equal power: the noise doesn't dip).
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            var l = left[i]
            var r = right[i]
            if (i < fade) {
                val t = i.toDouble() / fade
                val a = sqrt(t).toFloat()
                val b = sqrt(1 - t).toFloat()
                l = l * a + left[n + i] * b
                r = r * a + right[n + i] * b
            }
            out[i * 2] = l
            out[i * 2 + 1] = r
        }
        return out
    }

    /** A 16-bit stereo WAV file of [samples]. */
    fun wav(samples: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(RATE); putInt(RATE * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    /** Brings the loudness to about -20 dB (so all sounds are equally loud) and rounds off peaks. */
    private fun normalize(left: FloatArray, right: FloatArray) {
        var sum = 0.0
        for (i in left.indices) sum += left[i] * left[i] + right[i] * right[i]
        val rms = sqrt(sum / (left.size * 2)).coerceAtLeast(1e-6)
        val gain = 0.1 / rms
        for (i in left.indices) {
            left[i] = tanh(left[i] * gain * 1.2).toFloat() / 1.2f
            right[i] = tanh(right[i] * gain * 1.2).toFloat() / 1.2f
        }
    }

    // -- building blocks ----------------------------------------------------------------------------

    /** A state-variable filter: low-, band- or high-pass at a cutoff that may change every sample. */
    private class Filter(var cutoff: Double, val q: Double = 0.7) {
        private var low = 0.0
        private var band = 0.0
        var high = 0.0
            private set
        fun run(x: Double): Double {
            val f = 2 * sin(PI * min(cutoff, RATE / 6.0) / RATE)
            low += f * band
            high = x - low - band / q
            band += f * high
            return low
        }
        val bandOut: Double get() = band
    }

    private class Pink(private val random: Random) {
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        fun next(): Double {
            val w = random.nextDouble() * 2 - 1
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            return (b0 + b1 + b2 + w * 0.1848) * 0.2
        }
    }

    private class Brown(private val random: Random) {
        private var v = 0.0
        fun next(): Double {
            v = (v + (random.nextDouble() * 2 - 1) * 0.02) / 1.02
            return v * 3.5
        }
    }

    /** A value that wanders smoothly between 0 and 1, changing about every [seconds]. */
    private class Drift(private val random: Random, private val seconds: Double) {
        private var from = random.nextDouble()
        private var to = random.nextDouble()
        private var t = 0.0
        fun next(): Double {
            t += 1.0 / (seconds * RATE)
            if (t >= 1) { t -= 1; from = to; to = random.nextDouble() }
            val s = t * t * (3 - 2 * t)
            return from + (to - from) * s
        }
    }

    private class Voice(val left: FloatArray, val right: FloatArray, val random: Random) {
        val n = left.size

        fun draw(sound: AmbientSound) = when (sound) {
            AmbientSound.Rain -> { rainBed(0.6); drops(55.0, 0.5) }
            AmbientSound.Storm -> { rainBed(1.0); drops(110.0, 0.7); thunder() }
            AmbientSound.Wind -> wind(180.0, 900.0, 1.0)
            AmbientSound.Breeze -> { wind(500.0, 2200.0, 0.5); leaves() }
            AmbientSound.Ocean -> waves()
            AmbientSound.Stream -> stream()
            AmbientSound.Fire -> fire()
            AmbientSound.Crickets -> crickets()
            AmbientSound.BrownNoise -> brownNoise()
        }

        fun add(i: Int, l: Double, r: Double) {
            if (i in 0 until n) { left[i] += l.toFloat(); right[i] += r.toFloat() }
        }

        /** Events at random times, about [perSecond] a second. */
        fun events(perSecond: Double, block: (start: Int) -> Unit) {
            var t = 0.0
            while (true) {
                t += -ln(1 - random.nextDouble()) / perSecond
                val start = (t * RATE).toInt()
                if (start >= n) break
                block(start)
            }
        }

        fun rainBed(level: Double) {
            val pl = Pink(random); val pr = Pink(random)
            val hl = Filter(500.0); val hr = Filter(500.0)
            val ll = Filter(7000.0); val lr = Filter(7000.0)
            val swell = Drift(random, 6.0)
            for (i in 0 until n) {
                val g = level * (0.8 + 0.3 * swell.next())
                hl.run(pl.next()); hr.run(pr.next())
                add(i, ll.run(hl.high) * g, lr.run(hr.high) * g)
            }
        }

        /** Raindrops: tiny clicks of noise at 2-7 kHz, all over the stereo field. */
        fun drops(perSecond: Double, level: Double) = events(perSecond) { start ->
            val f = Filter(2000.0 + random.nextDouble() * 5000, 3.0)
            val len = (RATE * (0.004 + random.nextDouble() * 0.012)).toInt()
            val amp = level * -ln(1 - random.nextDouble() * 0.98) * 0.35
            val pan = random.nextDouble()
            for (k in 0 until len) {
                f.run(random.nextDouble() * 2 - 1)
                val s = f.bandOut * amp * exp(-k / (len / 4.0))
                add(start + k, s * (1 - pan), s * pan)
            }
        }

        /** A rumble or two: low noise that cracks first, then rolls for seconds. */
        fun thunder() {
            val count = 2
            for (c in 0 until count) {
                val start = (n * (0.15 + 0.5 * c + random.nextDouble() * 0.2)).toInt()
                val length = (RATE * (5 + random.nextDouble() * 3)).toInt()
                val b = Brown(random)
                val f = Filter(900.0)
                val rolls = Drift(random, 0.6)
                val pan = 0.3 + random.nextDouble() * 0.4
                val loud = 1.6 + random.nextDouble()
                for (k in 0 until length) {
                    val t = k.toDouble() / RATE
                    f.cutoff = 120 + 1400 * exp(-t * 3)
                    val env = (1 - exp(-t * 12)) * exp(-t * 0.55) * (0.5 + rolls.next())
                    val s = f.run(b.next()) * env * loud
                    add(start + k, s * (1.2 - pan), s * (0.2 + pan))
                }
            }
        }

        fun wind(lowHz: Double, highHz: Double, level: Double) {
            val nl = Pink(random); val nr = Pink(random)
            val fl = Filter(400.0, 1.6); val fr = Filter(400.0, 1.6)
            val pitch = Drift(random, 4.0); val pitch2 = Drift(random, 5.0)
            val gust = Drift(random, 3.0)
            for (i in 0 until n) {
                val g = gust.next()
                val p = pitch.next()
                fl.cutoff = lowHz + (highHz - lowHz) * (0.6 * p + 0.4 * g)
                fr.cutoff = lowHz + (highHz - lowHz) * (0.6 * pitch2.next() + 0.4 * g)
                fl.run(nl.next()); fr.run(nr.next())
                val a = level * (0.25 + 0.9 * g * g) * 2.0
                add(i, fl.bandOut * a, fr.bandOut * a)
            }
        }

        /** Leaves rustling in gusts: soft high noise. */
        fun leaves() {
            val pl = Pink(random); val pr = Pink(random)
            val hl = Filter(3000.0); val hr = Filter(3000.0)
            val gust = Drift(random, 2.5)
            for (i in 0 until n) {
                val g = gust.next()
                hl.run(pl.next()); hr.run(pr.next())
                val a = 0.12 * g * g * g
                add(i, hl.high * a, hr.high * a)
            }
        }

        /** Waves every 7-11 seconds: a swell, a crash and the wash running back. */
        fun waves() {
            val bl = Brown(random); val br = Brown(random)
            val pl = Pink(random); val pr = Pink(random)
            val fl = Filter(300.0); val fr = Filter(300.0)
            val envelope = DoubleArray(n)
            var t = random.nextDouble() * 3
            while (t * RATE < n + RATE * 10) {
                val period = 7 + random.nextDouble() * 4
                val size = 0.6 + random.nextDouble() * 0.5
                for (k in 0 until (period * 1.6 * RATE).toInt()) {
                    val i = (t * RATE).toInt() + k
                    if (i >= n) break
                    val s = k.toDouble() / RATE
                    val e = if (s < 2.2) (s / 2.2).let { it * it } else exp(-(s - 2.2) / 2.2)
                    envelope[i] = max(envelope[i], e * size)
                }
                t += period
            }
            for (i in 0 until n) {
                val e = envelope[i]
                fl.cutoff = 250 + 2600 * e * e; fr.cutoff = 260 + 2500 * e * e
                val l = fl.run(bl.next() * 0.6 + pl.next() * 0.8)
                val r = fr.run(br.next() * 0.6 + pr.next() * 0.8)
                add(i, l * (0.15 + e), r * (0.15 + e * 0.9))
            }
        }

        /** Water over stones: a babble of little bubbles over soft noise. */
        fun stream() {
            val pl = Pink(random); val pr = Pink(random)
            val fl = Filter(1400.0, 0.9); val fr = Filter(1600.0, 0.9)
            for (i in 0 until n) {
                fl.run(pl.next()); fr.run(pr.next())
                add(i, fl.bandOut * 0.5, fr.bandOut * 0.5)
            }
            events(90.0) { start ->
                val f0 = 500 + random.nextDouble() * 1500
                val len = (RATE * (0.015 + random.nextDouble() * 0.03)).toInt()
                val amp = 0.05 + random.nextDouble() * 0.1
                val pan = random.nextDouble()
                var phase = 0.0
                for (k in 0 until len) {
                    val s = k.toDouble() / len
                    phase += 2 * PI * f0 * (1 + 0.8 * s) / RATE
                    val v = sin(phase) * amp * sin(PI * s) * exp(-s * 2)
                    add(start + k, v * (1 - pan), v * pan)
                }
            }
        }

        /** A fire: a low roar that flickers, hiss, and crackles and pops. */
        fun fire() {
            val bl = Brown(random); val br = Brown(random)
            val fl = Filter(220.0); val fr = Filter(240.0)
            val flicker = Drift(random, 0.4)
            val pl = Pink(random)
            val hiss = Filter(5000.0)
            for (i in 0 until n) {
                val f = 0.6 + 0.6 * flicker.next()
                hiss.run(pl.next())
                val h = hiss.high * 0.04
                add(i, fl.run(bl.next()) * f + h, fr.run(br.next()) * f + h)
            }
            events(7.0) { start ->
                val len = (RATE * (0.001 + random.nextDouble() * 0.004)).toInt()
                val amp = 0.25 + random.nextDouble() * 0.6
                val pan = 0.2 + random.nextDouble() * 0.6
                // Crackles come in little bursts.
                val clicks = 1 + random.nextInt(4)
                for (c in 0 until clicks) {
                    val at = start + c * (RATE * (0.01 + random.nextDouble() * 0.03)).toInt()
                    for (k in 0 until len) {
                        val v = (random.nextDouble() * 2 - 1) * amp * exp(-k / (len / 3.0))
                        add(at + k, v * (1 - pan), v * pan)
                    }
                }
            }
            events(0.4) { start ->
                val f = Filter(600.0, 2.0)
                val len = (RATE * 0.06).toInt()
                val amp = 0.8 + random.nextDouble() * 0.5
                for (k in 0 until len) {
                    f.run(if (k < 30) random.nextDouble() * 2 - 1 else 0.0)
                    val v = f.bandOut * amp * 4
                    add(start + k, v, v * 0.8)
                }
            }
        }

        /** Several crickets near and far, each with its own song, and a soft night hum. */
        fun crickets() {
            val pl = Pink(random); val pr = Pink(random)
            val fl = Filter(300.0); val fr = Filter(300.0)
            for (i in 0 until n) add(i, fl.run(pl.next()) * 0.05, fr.run(pr.next()) * 0.05)
            repeat(5) { c ->
                val pitch = 4200 + random.nextDouble() * 700
                val pulses = 2 + random.nextInt(3)
                val period = 0.5 + random.nextDouble() * 0.6
                val near = if (c < 2) 1.0 else 0.35
                val pan = random.nextDouble()
                var t = random.nextDouble() * period
                var phase = 0.0
                while (t * RATE < n) {
                    for (pulse in 0 until pulses) {
                        val start = ((t + pulse * 0.03) * RATE).toInt()
                        val len = (RATE * 0.018).toInt()
                        for (k in 0 until len) {
                            phase += 2 * PI * pitch / RATE
                            val s = k.toDouble() / len
                            val v = sin(phase) * sin(PI * s) * 0.18 * near
                            add(start + k, v * (1 - pan), v * pan)
                        }
                    }
                    t += period * (0.95 + random.nextDouble() * 0.1)
                }
            }
            // A distant tree cricket's steady trill.
            var phase = 0.0
            for (i in 0 until n) {
                phase += 2 * PI * 2900 / RATE
                val trill = 0.5 + 0.5 * sin(2 * PI * 48 * i / RATE)
                val v = sin(phase) * trill * trill * 0.03
                add(i, v * 0.4, v)
            }
        }

        fun brownNoise() {
            val bl = Brown(random); val br = Brown(random)
            val fl = Filter(1200.0); val fr = Filter(1200.0)
            for (i in 0 until n) add(i, fl.run(bl.next()), fr.run(br.next()))
        }
    }

    /** The loudness of [samples] in dBFS (for tests). */
    fun level(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s * s
        return 20 * kotlin.math.log10(sqrt(sum / samples.size).coerceAtLeast(1e-9))
    }

    /**
     * For tests: the jump across the loop point (last sample to first) and the jump that only one
     * in a thousand neighboring samples exceed. A click would make the first far larger.
     */
    fun loopJump(samples: FloatArray): Pair<Double, Double> {
        val count = samples.size / 2
        var loop = 0.0
        val steps = ArrayList<Double>(count * 2)
        for (c in 0..1) {
            for (i in 0 until count - 1) steps += abs(samples[(i + 1) * 2 + c] - samples[i * 2 + c]).toDouble()
            loop = max(loop, abs(samples[c] - samples[(count - 1) * 2 + c]).toDouble())
        }
        steps.sort()
        return loop to steps[(steps.size * 0.999).toInt()]
    }
}
