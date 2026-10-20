package wpm.mpv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import wpm.logger
import java.nio.file.Path
import java.util.logging.Level
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.exists

/** The parts of the libmpv C API we use (https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h). */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_client_api_version(): Long
}

class LibmpvMissingException(dir: Path) :
    IllegalStateException("Live wallpapers need $LIBMPV_DLL, which was not found in $dir.")

const val LIBMPV_DLL = "libmpv-2.dll"

/** Where the native helpers (libmpv, yt-dlp) live: next to the installed app, or the repo's bin/ folder in development. */
object NativeBin {
    val dir: Path by lazy {
        System.getProperty("wpm.bin.dir")?.let(::Path)
            ?: System.getProperty("compose.application.resources.dir")?.let { Path(it, "bin") }
            ?: Path("bin")
    }
}

internal object Mpv {
    private val log = logger("mpv")
    private var loaded: LibMpv? = null

    val available: Boolean get() = NativeBin.dir.resolve(LIBMPV_DLL).exists()

    @Synchronized
    fun lib(): LibMpv {
        loaded?.let { return it }
        val dll = NativeBin.dir.resolve(LIBMPV_DLL)
        if (!dll.exists()) throw LibmpvMissingException(NativeBin.dir.toAbsolutePath())
        return Native.load(dll.toAbsolutePath().toString(), LibMpv::class.java).also {
            loaded = it
            log.info("Loaded libmpv (client API ${it.mpv_client_api_version() shr 16}.${it.mpv_client_api_version() and 0xFFFF})")
        }
    }

    // Event ids from client.h
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_VIDEO_RECONFIG = 17
    const val EVENT_PLAYBACK_RESTART = 21

    // mpv_end_file_reason
    const val END_EOF = 0
    const val END_ERROR = 4
}

class MpvException(message: String) : RuntimeException(message)

/** One event from mpv_wait_event, copied out of mpv's memory. */
data class MpvEvent(val id: Int, val error: Int, val endReason: Int = -1, val endError: Int = 0, val log: Triple<String, String, String>? = null)

/**
 * A libmpv player instance. Commands and properties may be called from any thread
 * (libmpv is thread-safe); events are read by one thread at a time via [waitEvent],
 * or by a background thread started with [startEventThread].
 */
class MpvPlayer(options: Map<String, String>, logLevel: String = "warn") : AutoCloseable {
    private val lib = Mpv.lib()
    private val ctx: Pointer = lib.mpv_create() ?: throw MpvException("mpv_create failed")
    private val log = logger("mpv")
    @Volatile private var closed = false
    private var events: Thread? = null

    init {
        try {
            options.forEach { (name, value) -> check("option $name", lib.mpv_set_option_string(ctx, name, value)) }
            check("initialize", lib.mpv_initialize(ctx))
            lib.mpv_request_log_messages(ctx, logLevel)
        } catch (e: Exception) {
            lib.mpv_terminate_destroy(ctx)
            throw e
        }
    }

    private fun check(what: String, code: Int) {
        if (code < 0) throw MpvException("mpv $what failed: ${lib.mpv_error_string(code)}")
    }

    // Calls are serialised with close(), so nothing touches the handle after it is destroyed.
    @Synchronized
    fun command(vararg args: String) {
        if (closed) return
        check("command ${args.first()}", lib.mpv_command(ctx, arrayOf(*args, null)))
    }

    @Synchronized
    operator fun set(name: String, value: String) {
        if (closed) return
        check("set $name", lib.mpv_set_property_string(ctx, name, value))
    }

    @Synchronized
    operator fun get(name: String): String? {
        if (closed) return null
        val ptr = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { ptr.getString(0, "UTF-8") } finally { lib.mpv_free(ptr) }
    }

    fun waitEvent(timeoutSeconds: Double): MpvEvent {
        val ev = lib.mpv_wait_event(ctx, timeoutSeconds)
        val id = ev.getInt(0)
        val error = ev.getInt(4)
        val data = ev.getPointer(16)
        return when {
            id == Mpv.EVENT_END_FILE && data != null -> MpvEvent(id, error, data.getInt(0), data.getInt(4))
            id == Mpv.EVENT_LOG_MESSAGE && data != null -> MpvEvent(
                id, error,
                log = Triple(data.getPointer(0)?.getString(0) ?: "", data.getPointer(8)?.getString(0) ?: "",
                    data.getPointer(16)?.getString(0, "UTF-8") ?: ""),
            )
            else -> MpvEvent(id, error)
        }
    }

    /** Drains events on a background thread: logs mpv's messages and hands others to [onEvent]. */
    fun startEventThread(name: String, onEvent: (MpvEvent) -> Unit = {}) {
        events = thread(name = name, isDaemon = true) {
            while (!closed) {
                val event = waitEvent(1.0)
                when (event.id) {
                    Mpv.EVENT_NONE -> {}
                    Mpv.EVENT_SHUTDOWN -> return@thread
                    Mpv.EVENT_LOG_MESSAGE -> logMessage(event)
                    else -> runCatching { onEvent(event) }.onFailure { log.warning("mpv event handler failed: $it") }
                }
            }
        }
    }

    internal fun logMessage(event: MpvEvent) {
        val (prefix, level, text) = event.log ?: return
        // mpv's warnings are mostly harmless noise for us; errors are worth keeping.
        val julLevel = when (level) {
            "fatal", "error" -> Level.WARNING
            else -> Level.FINE
        }
        log.log(julLevel, "mpv/$prefix: ${text.trim()}")
    }

    fun errorString(code: Int): String = lib.mpv_error_string(code)

    override fun close() {
        if (closed) return
        closed = true
        events?.let {
            lib.mpv_wakeup(ctx)
            it.join(3_000)
        }
        synchronized(this) { lib.mpv_terminate_destroy(ctx) }
    }
}
