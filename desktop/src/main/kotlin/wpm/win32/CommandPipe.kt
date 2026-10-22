package wpm.win32

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import wpm.logger
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread

private val log = logger("commands")

/** The running copy's answer to a command. */
@Serializable
data class CommandReply(val ok: Boolean, val message: String)

private const val PIPE_REJECT_REMOTE_CLIENTS = 0x8
private const val BUFFER = 64 * 1024

private fun pipePath(name: String) = "\\\\.\\pipe\\$name.Commands"

/**
 * Lets a second launch (`WallpaperManager.exe --next`) hand its command line to the running copy.
 *
 * The running copy serves a local named pipe. A client writes its arguments as a JSON array in
 * one message and reads back a [CommandReply]. Only this PC can connect (remote clients are
 * rejected), and the pipe's default security lets only this user send commands.
 */
class CommandPipe(private val name: String, private val handle: (List<String>) -> CommandReply) : AutoCloseable {
    private val k32 = Kernel32.INSTANCE
    @Volatile private var running = true

    private val server = thread(name = "command-pipe", isDaemon = true) {
        while (running) {
            val pipe = k32.CreateNamedPipe(
                pipePath(name), WinBase.PIPE_ACCESS_DUPLEX,
                WinBase.PIPE_TYPE_MESSAGE or WinBase.PIPE_READMODE_MESSAGE or WinBase.PIPE_WAIT or PIPE_REJECT_REMOTE_CLIENTS,
                WinBase.PIPE_UNLIMITED_INSTANCES, BUFFER, BUFFER, 0, null,
            )
            if (pipe == null || pipe == WinBase.INVALID_HANDLE_VALUE) {
                log.warning("Couldn't create the command pipe (error ${k32.GetLastError()})")
                return@thread
            }
            try {
                val connected = k32.ConnectNamedPipe(pipe, null) || k32.GetLastError() == WinError.ERROR_PIPE_CONNECTED
                if (connected && running) serve(pipe)
            } catch (e: Exception) {
                log.warning("Command failed: $e")
            } finally {
                k32.DisconnectNamedPipe(pipe)
                k32.CloseHandle(pipe)
            }
        }
    }

    private fun serve(pipe: WinNT.HANDLE) {
        val buffer = ByteArray(BUFFER)
        val read = IntByReference()
        if (!k32.ReadFile(pipe, buffer, BUFFER, read, null)) return
        val reply = try {
            val args = Json.decodeFromString(ListSerializer(String.serializer()), String(buffer, 0, read.value, Charsets.UTF_8))
            log.info("Command from the command line: $args")
            handle(args)
        } catch (e: Exception) {
            CommandReply(false, e.message ?: e.javaClass.simpleName)
        }
        val bytes = Json.encodeToString(CommandReply.serializer(), reply).toByteArray(Charsets.UTF_8)
        k32.WriteFile(pipe, bytes, bytes.size, IntByReference(), null)
        k32.FlushFileBuffers(pipe)
    }

    override fun close() {
        if (!running) return
        running = false
        // Unblock ConnectNamedPipe with a throwaway connection.
        runCatching { RandomAccessFile(pipePath(name), "rw").close() }
        server.join(1_000)
    }

    companion object {
        /**
         * Sends [args] to the running copy and returns its answer. Waits up to [timeoutMs] for the
         * pipe to appear (the running copy may still be starting) and for the command to finish.
         */
        fun send(name: String, args: List<String>, timeoutMs: Long = 60_000): CommandReply {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                try {
                    RandomAccessFile(pipePath(name), "rw").use { pipe ->
                        pipe.write(Json.encodeToString(ListSerializer(String.serializer()), args).toByteArray(Charsets.UTF_8))
                        val buffer = ByteArray(BUFFER)
                        val count = pipe.read(buffer)
                        if (count <= 0) throw IOException("The running copy didn't answer")
                        return Json.decodeFromString(CommandReply.serializer(), String(buffer, 0, count, Charsets.UTF_8))
                    }
                } catch (e: FileNotFoundException) { // not created yet, or every instance busy
                    if (System.currentTimeMillis() > deadline) throw IOException("The running copy didn't answer in time", e)
                    Kernel32.INSTANCE.WaitNamedPipe(pipePath(name), 500).also { if (!it) Thread.sleep(200) }
                }
            }
        }
    }
}

@Suppress("FunctionName")
private interface ConsoleApi : StdCallLibrary {
    fun AttachConsole(processId: Int): Boolean

    companion object {
        val INSTANCE: ConsoleApi = Native.load("kernel32", ConsoleApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Where `--help` and command results go: the file or pipe output was redirected to
 * (`WallpaperManager.exe --next > result.txt`), else the console of the command prompt this
 * (windowed) app was started from, or null.
 */
fun parentConsole(): PrintStream? = runCatching {
    val k32 = Kernel32.INSTANCE
    val out = k32.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE)
    if (out != null && out != WinBase.INVALID_HANDLE_VALUE && k32.GetFileType(out) in setOf(WinNT.FILE_TYPE_DISK, WinNT.FILE_TYPE_PIPE)) {
        return PrintStream(FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8)
    }
    if (!ConsoleApi.INSTANCE.AttachConsole(-1)) return null // ATTACH_PARENT_PROCESS
    PrintStream(FileOutputStream("CONOUT$"), true, Charsets.UTF_8)
}.getOrNull()
