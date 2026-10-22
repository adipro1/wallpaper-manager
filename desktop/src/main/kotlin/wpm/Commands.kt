package wpm

/**
 * Something the app can be asked to do from outside its window: the command line
 * (`WallpaperManager.exe --next`, sent to the running copy), global keyboard shortcuts and the tray.
 */
sealed class AppCommand {
    data object Show : AppCommand()
    data object Next : AppCommand()
    data object Random : AppCommand()
    data object TogglePause : AppCommand()
    data object Pause : AppCommand()
    data object Resume : AppCommand()
    data object Stop : AppCommand()
    data object StopPlaylist : AppCommand()
    /** A library item (by file name) or a file/folder on this PC (added to the library if needed). */
    data class Set(val target: String) : AppCommand()
    data class Playlist(val name: String) : AppCommand()
}

/** What the command line asked for. */
data class CommandLine(
    val command: AppCommand? = null,
    val minimized: Boolean = false,
    val selfTest: Boolean = false,
    val help: Boolean = false,
    /** --after <pid>: wait for that process (the copy that is restarting or updating) to exit first. */
    val afterPid: Long? = null,
    /** Set when the arguments don't make sense; shown with the usage text. */
    val error: String? = null,
)

val COMMAND_LINE_HELP = """
    Usage: WallpaperManager.exe [option]

      --next              Next wallpaper (of the running playlist, or the library)
      --random            Random wallpaper from the library
      --pause             Pause the live wallpaper
      --resume            Resume the live wallpaper
      --toggle-pause      Pause or resume the live wallpaper
      --stop              Stop the live wallpaper (back to the picture underneath)
      --set <file|name>   Show a file (added to the library if needed) or a library item
      --playlist <name>   Start a playlist
      --stop-playlist     Stop the running playlist
      --show              Open the window
      --minimized         Start hidden in the tray
      --self-test         Check every engine and write the results to the log
      --help              Show this text

    Commands go to the running copy (it is started first if needed).
    Exit code: 0 done, 1 failed, 2 wrong arguments.
""".trimIndent()

private val simpleCommands = mapOf(
    "--show" to AppCommand.Show,
    "--next" to AppCommand.Next,
    "--random" to AppCommand.Random,
    "--pause" to AppCommand.Pause,
    "--resume" to AppCommand.Resume,
    "--toggle-pause" to AppCommand.TogglePause,
    "--stop" to AppCommand.Stop,
    "--stop-playlist" to AppCommand.StopPlaylist,
)

/** Reads the command line. Options are case-insensitive; at most one command is allowed. */
fun parseCommandLine(args: List<String>): CommandLine {
    var result = CommandLine()
    var command: AppCommand? = null
    var i = 0
    fun setCommand(next: AppCommand): Boolean {
        if (command != null) {
            result = result.copy(error = "Only one command at a time")
            return false
        }
        command = next
        return true
    }
    while (i < args.size) {
        val arg = args[i]
        when (val option = arg.lowercase()) {
            "--minimized" -> result = result.copy(minimized = true)
            "--self-test" -> result = result.copy(selfTest = true)
            "--help", "-h", "/?", "-?" -> result = result.copy(help = true)
            "--after" -> {
                val pid = args.getOrNull(i + 1)?.toLongOrNull() ?: return result.copy(error = "--after needs a process id")
                result = result.copy(afterPid = pid)
                i++
            }
            "--set", "--playlist" -> {
                val value = args.getOrNull(i + 1)?.trim()
                if (value.isNullOrEmpty() || value.startsWith("--")) {
                    return result.copy(error = "$option needs a value, e.g. $option \"${if (option == "--set") "C:\\Pictures\\sunset.jpg" else "Evenings"}\"")
                }
                if (!setCommand(if (option == "--set") AppCommand.Set(value) else AppCommand.Playlist(value))) return result
                i++
            }
            else -> {
                val simple = simpleCommands[option] ?: return result.copy(error = "Unknown option: $arg")
                if (!setCommand(simple)) return result
            }
        }
        i++
    }
    return result.copy(command = command)
}

/** An action that can have a global keyboard shortcut. */
data class ShortcutAction(val id: String, val label: String, val command: AppCommand, val defaultKeys: String)

/** Global shortcuts are off until switched on in Settings; these are the suggested keys. */
val SHORTCUT_ACTIONS = listOf(
    ShortcutAction("next", "Next wallpaper", AppCommand.Next, "Ctrl+Alt+N"),
    ShortcutAction("random", "Random wallpaper", AppCommand.Random, "Ctrl+Alt+R"),
    ShortcutAction("toggle_pause", "Pause / resume the live wallpaper", AppCommand.TogglePause, "Ctrl+Alt+P"),
    ShortcutAction("stop", "Stop the live wallpaper", AppCommand.Stop, ""),
    ShortcutAction("show", "Open Wallpaper Manager", AppCommand.Show, "Ctrl+Alt+W"),
)
