package wpm.services

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import wpm.APP_NAME
import wpm.APP_VERSION
import wpm.AppPaths
import wpm.data.Database
import wpm.logger
import wpm.mpv.Mpv
import wpm.mpv.NativeBin
import wpm.win32.Autostart
import wpm.win32.Monitors
import wpm.win32.SystemState
import wpm.win32.WindowsDesktop
import wpm.win32.describeDesktop
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val log = logger("diagnostics")

fun windowsVersion(): String = try {
    val key = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"
    fun value(name: String) = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, name) }.getOrDefault("")
    val build = value("CurrentBuild").toIntOrNull() ?: 0
    val ubr = runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key, "UBR") }.getOrDefault(0)
    // The registry still says "Windows 10" on Windows 11; build 22000+ is Windows 11.
    val product = value("ProductName").let { if (build >= 22000) it.replace("Windows 10", "Windows 11") else it }
    "$product ${value("DisplayVersion")} (build $build.$ubr)"
} catch (e: Exception) {
    System.getProperty("os.name") + " " + System.getProperty("os.version")
}

private fun section(title: String, lines: List<String>) = "== $title ==\n" + lines.joinToString("\n") + "\n"

/** A diagnostics report must never fail. */
private fun safe(block: () -> Any?): String = try {
    block().toString()
} catch (e: Exception) {
    "unavailable ($e)"
}

fun buildReport(db: Database, paths: AppPaths, wallpapers: WallpaperService): String = listOf(
    section("App", listOf(
        "$APP_NAME $APP_VERSION",
        "Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
        "Installed app: ${System.getProperty("jpackage.app-path") ?: "no (development build)"}",
        "Data folder: ${paths.dataDir}",
        "Report created: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}",
    )),
    section("Windows", listOf(
        windowsVersion(),
        "Monitors: ${safe { Monitors.all() }}",
        "On battery: ${safe { SystemState.onBattery() }}",
        "Fullscreen app on: ${safe { SystemState.fullscreenMonitor() }}",
        "Current desktop wallpaper: ${safe { WindowsDesktop.currentWallpaper() }}",
        "Start with Windows: ${safe { Autostart.isEnabled() }}",
    )),
    section("Live wallpaper", listOf(
        "Native folder: ${NativeBin.dir.toAbsolutePath()}  libmpv found: ${Mpv.available}",
        "Playing: ${wallpapers.videoActive}  paused: ${safe { wallpapers.videoPaused }}  auto-pause reason: ${wallpapers.autoPauseReason}",
        "Other wallpaper apps in the layer: ${safe { wallpapers.competingApps() }}",
    )),
    section("Desktop windows (Explorer)", runCatching { describeDesktop() }.getOrElse { listOf("unavailable ($it)") }.ifEmpty { listOf("(none found)") }),
    section("Library", listOf(
        safe { db.mediaStats() },
        "Playlists: ${safe { db.listPlaylists().size }}   Schedule rules: ${safe { db.listRules().size }}",
    )),
    section("Settings", db.query("SELECT key, value FROM settings ORDER BY key") { "${it.getString(1)} = ${it.getString(2)}" }),
).joinToString("\n")

/** Writes the report and all log files into one zip. No media files are included. */
fun saveDiagnostics(dest: Path, db: Database, paths: AppPaths, wallpapers: WallpaperService): Path {
    val report = buildReport(db, paths, wallpapers)
    ZipOutputStream(Files.newOutputStream(dest)).use { zip ->
        zip.putNextEntry(ZipEntry("report.txt"))
        zip.write(report.toByteArray())
        zip.closeEntry()
        runCatching { paths.logDir.listDirectoryEntries("*.log*") }.getOrDefault(emptyList()).filterNot { it.name.endsWith(".lck") }.sorted().forEach { file ->
            // The current log is still open for writing; copying its bytes is fine.
            runCatching {
                val bytes = Files.readAllBytes(file)
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
    log.info("Saved diagnostics to $dest")
    return dest
}
