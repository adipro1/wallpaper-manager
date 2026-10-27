package wpm.media

import wpm.APP_VERSION
import wpm.data.Database
import wpm.data.Settings
import wpm.logger
import wpm.tr
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = logger("scenes")

/** A live wallpaper that ships with the app (a web page in resources/scenes/<id>). */
data class Scene(val id: String, val title: String, val files: List<String>)

/**
 * Built-in live wallpapers that show the real world: pages that the app feeds with the place,
 * the sky and the weather (see [wpm.services.World.sceneScript]). Their files are copied into the
 * app's data folder, so they play like any web wallpaper, and each is added to the library once
 * (deleting it there keeps it deleted).
 */
class Scenes(private val db: Database, private val settings: Settings, private val web: WebWallpapers, val folder: Path) {

    fun folderOf(scene: Scene): Path = folder.resolve(scene.id)

    /** Whether [pageUrl] (a web engine's address) is one of the scenes. */
    fun isScene(pageUrl: String?): Boolean = sceneOf(pageUrl) != null

    /** The scene a web engine's address shows, if any. */
    fun sceneOf(pageUrl: String?): Scene? = pageUrl?.let { url -> SCENES.firstOrNull { url.startsWith(folderOf(it).toUri().toString()) } }

    /** Copies the files of every scene (again after an update) and adds new scenes to the library. Returns the new ids. */
    fun install(): List<Long> = SCENES.mapNotNull { scene ->
        runCatching {
            val target = folderOf(scene)
            val marker = target.resolve(".version")
            if (!marker.exists() || marker.readText() != APP_VERSION) {
                Files.createDirectories(target)
                for (file in scene.files) {
                    val stream = javaClass.getResourceAsStream("/scenes/${scene.id}/$file") ?: continue
                    stream.use { Files.copy(it, target.resolve(file), StandardCopyOption.REPLACE_EXISTING) }
                }
                marker.writeText(APP_VERSION)
            }
            val offered = settings.string("scenes_offered", "").split(',').filter { it.isNotEmpty() }
            // 3.3.0 added the scenes before the drawing library was loaded, so without a thumbnail.
            db.mediaByPath(target.toString())?.takeIf { it.thumbnail == null }?.let { item ->
                web.thumbnail(target.resolve("preview.jpg"), item.filename, "WEB")?.let { db.setThumbnail(item.id, it) }
            }
            if (scene.id in offered) return@runCatching null
            settings.set("scenes_offered", (offered + scene.id).joinToString(","))
            if (db.hasMedia(target.toString())) null else web.import(target)?.also { db.rename(it, tr(scene.title)) }
        }.onFailure { log.warning("Couldn't install the ${scene.id} scene: $it") }.getOrNull()
    }

    companion object {
        val SCENES = listOf(
            Scene("night-sky", "Night sky", listOf("index.html", "sky.js", "stars.js", "LivelyProperties.json", "wpm.json", "preview.jpg", "NOTICE.txt")),
            Scene("earth", "Live Earth", listOf("index.html", "earth.js", "LivelyProperties.json", "wpm.json", "preview.jpg", "NOTICE.txt")),
        )
        val EARTH: Scene get() = SCENES.first { it.id == "earth" }
    }
}
