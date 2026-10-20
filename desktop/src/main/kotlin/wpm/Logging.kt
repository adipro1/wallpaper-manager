package wpm

import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger

fun logger(name: String): Logger = Logger.getLogger(name)

private class LineFormatter : Formatter() {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")

    override fun format(record: LogRecord): String {
        val level = when (record.level) {
            Level.SEVERE -> "ERROR"
            Level.WARNING -> "WARNING"
            Level.FINE, Level.FINER, Level.FINEST -> "DEBUG"
            else -> "INFO"
        }
        val text = buildString {
            append(time.format(Date(record.millis))).append(" [").append(level).append("] ")
            append(record.loggerName).append(": ").append(formatMessage(record)).append('\n')
            record.thrown?.let { append(it.stackTraceToString()) }
        }
        return text
    }
}

/** Rotating log file (1 MB x 4) plus the console, and logging of uncaught errors. Returns the log file. */
fun setupLogging(logDir: Path): Path {
    LogManager.getLogManager().reset()
    val root = Logger.getLogger("")
    root.level = Level.INFO
    val pattern = logDir.resolve("wallpaper_manager.%g.log").toString()
    root.addHandler(FileHandler(pattern, 1_000_000, 4, true).apply { formatter = LineFormatter() })
    if (System.console() != null) {
        root.addHandler(ConsoleHandler().apply { formatter = LineFormatter() })
    }
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        logger("uncaught").log(Level.SEVERE, "Uncaught exception in thread ${thread.name}", error)
    }
    return logDir.resolve("wallpaper_manager.0.log")
}
