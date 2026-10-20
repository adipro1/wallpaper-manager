package wpm

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

const val APP_NAME = "Wallpaper Manager"
const val APP_ID = "WallpaperManager"
const val APP_VERSION = BUILD_VERSION

/**
 * File-system locations used by the app.
 *
 * All generated data lives in %LOCALAPPDATA%\WallpaperManager - the same folder
 * version 1.x used, so the library, playlists and schedule carry over.
 * Set the WPM_DATA_DIR environment variable to override it.
 */
data class AppPaths(val dataDir: Path) {
    val dbPath: Path get() = dataDir.resolve("library.db")
    val thumbDir: Path get() = dataDir.resolve("thumbnails")
    val logDir: Path get() = dataDir.resolve("logs")
    val cacheDir: Path get() = dataDir.resolve("cache")

    fun ensure(): AppPaths = apply {
        listOf(dataDir, thumbDir, logDir, cacheDir).forEach { it.createDirectories() }
    }

    companion object {
        fun default(): AppPaths {
            val override = System.getenv("WPM_DATA_DIR")
            val base = if (!override.isNullOrBlank()) Path(override) else {
                val local = System.getenv("LOCALAPPDATA") ?: Path(System.getProperty("user.home"), "AppData", "Local").toString()
                Path(local, APP_ID)
            }
            return AppPaths(base).ensure()
        }
    }
}
