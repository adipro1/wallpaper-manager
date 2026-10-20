package wpm.engine

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import wpm.logger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val log = logger("audio")

const val AUDIO_BANDS = 128

/**
 * Turns raw samples into [AUDIO_BANDS] levels between 0 and 1 (bass first), like Lively's
 * audio listener: 1024-point FFT with a Hann window, log-spaced bands from 30 Hz to 16 kHz,
 * a decibel scale and gentle smoothing so bars fall back smoothly.
 */
class Spectrum(private val sampleRate: Int, private val size: Int = 1024) {
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val smoothed = FloatArray(AUDIO_BANDS)
    // Which FFT bins belong to each band (log spaced; at least one bin each).
    private val edges: IntArray = run {
        val lo = ln(30.0)
        val hi = ln(min(16_000.0, sampleRate / 2.0))
        IntArray(AUDIO_BANDS + 1) { i ->
            val freq = kotlin.math.exp(lo + (hi - lo) * i / AUDIO_BANDS)
            (freq * size / sampleRate).toInt().coerceIn(1, size / 2)
        }
    }

    /** [samples] is the latest [size] mono samples (-1..1). */
    fun analyze(samples: FloatArray): FloatArray {
        for (i in 0 until size) {
            re[i] = samples[i] * window[i]
            im[i] = 0.0
        }
        fft(re, im)
        val out = FloatArray(AUDIO_BANDS)
        for (b in 0 until AUDIO_BANDS) {
            val from = edges[b]
            val to = max(edges[b + 1], from + 1)
            var peak = 0.0
            for (k in from until min(to, size / 2)) peak = max(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            // Magnitude to decibels, mapped so -70 dB..0 dB becomes 0..1 (a full-scale sine is about size/4).
            val db = 20 * log10(peak / (size / 4.0) + 1e-9)
            val level = ((db + 70) / 70).coerceIn(0.0, 1.0).toFloat()
            // Rise fast, fall slowly.
            smoothed[b] = if (level > smoothed[b]) level else smoothed[b] * 0.85f + level * 0.15f
            out[b] = smoothed[b]
        }
        return out
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}

/** A COM object called through its vtable (index 0-2 are IUnknown). */
private class Com(pointer: Pointer) : Unknown(pointer) {
    fun call(index: Int, vararg args: Any?): Int = _invokeNativeInt(index, arrayOf(pointer, *args))
}

private fun check(hr: Int, what: String) {
    if (hr < 0) throw IllegalStateException("$what failed (0x${Integer.toHexString(hr)})")
}

/**
 * Listens to what the PC is playing (WASAPI loopback on the default output device) and reports
 * [AUDIO_BANDS] levels about 30 times a second. Nothing is recorded or sent anywhere: samples
 * only pass through the spectrum analysis in memory.
 */
class AudioCapture(private val onLevels: (FloatArray) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    val active: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-capture", isDaemon = true) {
            while (running) {
                try {
                    captureLoop()
                } catch (e: Exception) {
                    // E.g. the output device changed or was unplugged: try again shortly.
                    log.info("Audio capture stopped: ${e.message}")
                    Thread.sleep(2_000)
                }
            }
        }
        log.info("Listening to system audio for an audio-reactive wallpaper")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        worker?.join(2_000)
        worker = null
        onLevels(FloatArray(AUDIO_BANDS))
        log.info("Stopped listening to system audio")
    }

    private fun captureLoop() {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        val created = mutableListOf<Com>()
        var mixFormat: Pointer? = null
        try {
            val enumeratorRef = PointerByReference()
            check(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, enumeratorRef).toInt(),
                "Opening the audio devices")
            val enumerator = Com(enumeratorRef.value).also(created::add)

            val deviceRef = PointerByReference()
            check(enumerator.call(4, E_RENDER, E_CONSOLE, deviceRef), "Finding the speakers") // GetDefaultAudioEndpoint
            val device = Com(deviceRef.value).also(created::add)

            val clientRef = PointerByReference()
            check(device.call(3, IID_IAudioClient, CLSCTX_ALL, null, clientRef), "Opening the speakers") // Activate
            val client = Com(clientRef.value).also(created::add)

            val formatRef = PointerByReference()
            check(client.call(8, formatRef), "Reading the audio format") // GetMixFormat
            mixFormat = formatRef.value
            val format = AudioFormat.read(mixFormat)
            check(client.call(3, SHAREMODE_SHARED, STREAMFLAGS_LOOPBACK, 2_000_000L, 0L, mixFormat, null), "Starting loopback capture") // Initialize

            val captureRef = PointerByReference()
            check(client.call(14, IID_IAudioCaptureClient, captureRef), "Opening the capture stream") // GetService
            val capture = Com(captureRef.value).also(created::add)
            check(client.call(10), "Starting the capture") // Start

            val spectrum = Spectrum(format.sampleRate)
            val history = FloatArray(1024)
            var filled = 0
            var lastReport = 0L
            val packet = IntByReference()
            val data = PointerByReference()
            val frames = IntByReference()
            val flags = IntByReference()
            while (running) {
                Thread.sleep(10)
                check(capture.call(5, packet), "Reading audio") // GetNextPacketSize
                while (packet.value > 0) {
                    check(capture.call(3, data, frames, flags, null, null), "Reading audio") // GetBuffer
                    val n = frames.value
                    val silent = flags.value and BUFFERFLAGS_SILENT != 0
                    val mono = if (silent) FloatArray(n) else format.toMono(data.value, n)
                    capture.call(4, n) // ReleaseBuffer
                    // Keep the latest 1024 samples.
                    if (n >= history.size) {
                        System.arraycopy(mono, n - history.size, history, 0, history.size)
                    } else {
                        System.arraycopy(history, n, history, 0, history.size - n)
                        System.arraycopy(mono, 0, history, history.size - n, n)
                    }
                    filled = min(history.size, filled + n)
                    check(capture.call(5, packet), "Reading audio")
                }
                val now = System.nanoTime()
                if (now - lastReport >= 33_000_000L) {
                    lastReport = now
                    onLevels(if (filled < history.size) FloatArray(AUDIO_BANDS) else spectrum.analyze(history))
                }
            }
            client.call(11) // Stop
        } finally {
            mixFormat?.let { Ole32.INSTANCE.CoTaskMemFree(it) }
            created.asReversed().forEach { runCatching { it.Release() } }
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private companion object {
        val CLSID_MMDeviceEnumerator = Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
        val IID_IMMDeviceEnumerator = Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        val IID_IAudioClient = Guid.GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
        val IID_IAudioCaptureClient = Guid.GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")
        const val CLSCTX_ALL = 0x17
        const val E_RENDER = 0
        const val E_CONSOLE = 0
        const val SHAREMODE_SHARED = 0
        const val STREAMFLAGS_LOOPBACK = 0x00020000
        const val BUFFERFLAGS_SILENT = 0x2
    }
}

/** The shared-mode mix format (WAVEFORMATEX / WAVEFORMATEXTENSIBLE): usually 32-bit float. */
private class AudioFormat(val channels: Int, val sampleRate: Int, val bits: Int, val isFloat: Boolean) {
    fun toMono(data: Pointer, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val bytesPerSample = bits / 8
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val offset = (f.toLong() * channels + c) * bytesPerSample
                sum += when {
                    isFloat && bits == 32 -> data.getFloat(offset)
                    bits == 16 -> data.getShort(offset) / 32768f
                    bits == 32 -> data.getInt(offset) / 2147483648f
                    else -> 0f
                }
            }
            out[f] = sum / channels
        }
        return out
    }

    companion object {
        private val FLOAT_SUBTYPE = Guid.GUID.fromString("{00000003-0000-0010-8000-00AA00389B71}")

        fun read(p: Pointer): AudioFormat {
            val tag = p.getShort(0).toInt() and 0xFFFF
            val channels = p.getShort(2).toInt()
            val rate = p.getInt(4)
            val bits = p.getShort(14).toInt()
            val isFloat = when (tag) {
                3 -> true // WAVE_FORMAT_IEEE_FLOAT
                0xFFFE -> Guid.GUID(p.share(24)) == FLOAT_SUBTYPE // WAVE_FORMAT_EXTENSIBLE
                else -> false
            }
            return AudioFormat(channels, rate, bits, isFloat)
        }
    }
}
