package wpm.media

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.data.Database
import wpm.logger
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private val log = logger("wallpaper-engine")
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Wallpaper Engine's Steam app id; workshop items live in steamapps/workshop/content/431960. */
private const val APP_ID = "431960"

/** A Wallpaper Engine project (a folder with a project.json). */
data class EngineProject(
    val folder: Path,
    val title: String,
    /** "video", "web", "scene", "application" or "preset". */
    val type: String,
    val file: String?,
    val preview: Path?,
) {
    /** Videos and web pages can be shown here; scenes and applications need Wallpaper Engine itself. */
    val supported: Boolean get() = (type == "video" || type == "web") && file != null && folder.resolve(file).isRegularFile()

    val reason: String?
        get() = when {
            supported -> null
            type == "scene" -> "Scene wallpapers need Wallpaper Engine"
            type == "application" -> "Program wallpapers need Wallpaper Engine"
            else -> "Not a video or web wallpaper"
        }
}

object WallpaperEngine {
    /** Reads a project folder, or null if it isn't one. */
    fun read(folder: Path): EngineProject? {
        val file = folder.resolve("project.json").takeIf { it.isRegularFile() } ?: return null
        val o = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        return EngineProject(
            folder = folder,
            title = str("title")?.takeIf { it.isNotBlank() } ?: folder.name,
            type = str("type")?.lowercase() ?: "scene",
            file = str("file"),
            preview = str("preview")?.let(folder::resolve)?.takeIf { it.isRegularFile() },
        )
    }

    /** Steam library folders (from the registry and libraryfolders.vdf). */
    fun steamLibraries(): List<Path> {
        val steam = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath") }
            .getOrNull()?.let { Path(it) } ?: return emptyList()
        val vdf = steam.resolve("steamapps/libraryfolders.vdf")
        val others = if (vdf.isRegularFile()) {
            Regex("\"path\"\\s+\"([^\"]+)\"").findAll(vdf.readText()).map { Path(it.groupValues[1].replace("\\\\", "\\")) }.toList()
        } else {
            emptyList()
        }
        return (listOf(steam) + others).distinctBy { it.toString().lowercase() }.filter { it.isDirectory() }
    }

    /** Every project found: workshop subscriptions and Wallpaper Engine's own projects folders. */
    fun findProjects(libraries: List<Path> = steamLibraries()): List<EngineProject> {
        val folders = libraries.flatMap { lib ->
            listOf(
                lib.resolve("steamapps/workshop/content/$APP_ID"),
                lib.resolve("steamapps/common/wallpaper_engine/projects/myprojects"),
                lib.resolve("steamapps/common/wallpaper_engine/projects/defaultprojects"),
            )
        }.filter { it.isDirectory() }
        return folders.flatMap { dir -> runCatching { dir.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList()) }
            .mapNotNull(::read)
            .sortedWith(compareBy({ !it.supported }, { it.title.lowercase() }))
            .also { log.info("Found ${it.size} Wallpaper Engine projects in ${folders.size} folders") }
    }

    /** Adds a video or web project to the library. Returns the item id, or null if it was already there. */
    fun import(project: EngineProject, db: Database, library: MediaLibrary, web: WebWallpapers): Long? {
        require(project.supported) { project.reason ?: "Can't be shown here" }
        return when (project.type) {
            "video" -> {
                val file = project.folder.resolve(project.file!!)
                if (db.mediaByPath(file.toString()) != null) return null
                val result = library.importFiles(listOf(file))
                result.failed.firstOrNull()?.let { throw IllegalArgumentException("${project.title}: ${it.second}") }
                result.added.firstOrNull()?.also { db.rename(it, project.title) }
            }
            else -> web.import(project.folder)
        }
    }
}
