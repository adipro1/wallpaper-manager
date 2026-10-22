package wpm.media

import wpm.tr
import wpm.data.Database
import wpm.data.MediaItem
import wpm.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = logger("missing")

/**
 * Library items whose file or folder has gone (moved, renamed, deleted, or a drive that isn't
 * connected), and finding them again.
 */
class MissingFiles(private val db: Database) {

    /** Items on this PC whose file or folder can't be found. */
    fun find(): List<MediaItem> = db.listMedia(sort = "name").filter { !it.isRemote && !it.available() }

    /** Points [item] at [newPath] (the same picture, video or web wallpaper somewhere else). */
    fun relink(item: MediaItem, newPath: Path) {
        val target = newPath.toAbsolutePath().normalize()
        require(target.exists()) { tr("{0} doesn't exist", target.name) }
        require(item.isWeb == target.isDirectory() || (item.isWeb && target.isRegularFile())) { tr(if (item.isWeb) "Choose a folder" else "Choose a file") }
        db.mediaByPath(target.toString())?.let { other ->
            require(other.id == item.id) { tr("{0} is already in your library (as {1})", target.name, other.filename) }
            return
        }
        val oldName = item.localPath?.name
        db.relink(item.id, target.toString(), if (item.filename == oldName) target.name else null)
        log.info("Relinked ${item.path} -> $target")
    }

    /**
     * Looks through [folder] (and its sub-folders) for missing items by file name; when a name
     * appears more than once, the one with the same size wins. Returns how many were found.
     */
    fun searchFolder(folder: Path, missing: List<MediaItem> = find()): Int {
        if (missing.isEmpty()) return 0
        val byName = mutableMapOf<String, MutableList<Path>>()
        Files.walk(folder).use { stream ->
            stream.forEach { p -> if (p != folder) byName.getOrPut(p.name.lowercase()) { mutableListOf() }.add(p) }
        }
        var found = 0
        for (item in missing) {
            val name = item.localPath?.name?.lowercase() ?: continue
            val candidates = byName[name].orEmpty().filter { if (item.isWeb) it.isDirectory() else it.isRegularFile() }
            val match = candidates.singleOrNull()
                ?: candidates.firstOrNull { c -> item.fileSize != null && runCatching { c.fileSize() }.getOrNull() == item.fileSize }
                ?: continue
            if (runCatching { relink(item, match) }.isSuccess) found++
        }
        log.info("Found $found of ${missing.size} missing items in $folder")
        return found
    }
}
