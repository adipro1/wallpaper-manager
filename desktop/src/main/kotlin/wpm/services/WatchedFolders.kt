package wpm.services

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import wpm.data.Database
import wpm.data.Settings
import wpm.logger
import wpm.media.MediaLibrary
import wpm.media.findMediaFiles
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val log = logger("watched")
private val foldersSerializer = ListSerializer(String.serializer())

/** What one check of a watched folder changed. */
data class FolderSync(val added: Int = 0, val removed: Int = 0, val moved: Int = 0) {
    val changed: Boolean get() = added + removed + moved > 0
    operator fun plus(other: FolderSync) = FolderSync(added + other.added, removed + other.removed, moved + other.moved)
}

/**
 * Folders whose pictures and videos stay in the library by themselves: new files are added,
 * deleted ones removed, and files moved inside a watched folder keep their place in playlists
 * and their tags. Changes are noticed through Windows' change notifications, with a full check
 * at start and every few minutes (notifications can be missed, e.g. on network drives).
 */
class WatchedFolders(
    private val db: Database,
    private val library: MediaLibrary,
    /** Called (on a background thread) after a check changed the library. */
    private val onChange: (FolderSync) -> Unit = {},
) : AutoCloseable {
    private val settings = Settings(db)
    private val lock = Any()
    private var watcher: WatchService? = null
    private val keys = ConcurrentHashMap<WatchKey, Path>() // watch key -> the watched root it belongs to
    @Volatile private var running = false

    val folders: List<Path> get() = (settings.obj("watched_folders", foldersSerializer) ?: emptyList()).map(Path::of)

    fun add(folder: Path): FolderSync {
        val path = folder.toAbsolutePath().normalize()
        require(path.isDirectory()) { "${path.name} isn't a folder" }
        val current = folders
        require(current.none { path.startsWith(it) }) { "${path.name} is already watched" }
        // A parent of watched folders replaces them.
        save(current.filterNot { it.startsWith(path) } + listOf(path)) // (a Path is Iterable: "+ path" would add its parts)
        restartWatching()
        return sync(path)
    }

    fun remove(folder: Path) {
        save(folders.filterNot { it == folder })
        restartWatching()
    }

    private fun save(list: List<Path>) = settings.set("watched_folders", foldersSerializer, list.map { it.toString() })

    /** Brings the library in line with [folder]. Nothing is removed while the folder itself can't be reached. */
    fun sync(folder: Path): FolderSync = synchronized(lock) {
        if (!folder.isDirectory()) return FolderSync()
        val prefix = folder.toString().trimEnd('\\', '/') + java.io.File.separator
        val inLibrary = db.listMedia().filter { it.path?.startsWith(prefix, ignoreCase = true) == true && (it.isImage || it.isVideo) }
        val onDisk = runCatching { findMediaFiles(folder) }.getOrElse {
            log.warning("Couldn't read $folder: $it")
            return FolderSync()
        }
        val known = inLibrary.mapTo(HashSet()) { it.path!!.lowercase() }
        val fresh = onDisk.filter { it.toString().lowercase() !in known }.toMutableList()
        val gone = inLibrary.filter { !Path.of(it.path!!).exists() }

        // A file that disappeared and one that appeared with the same name and size were moved.
        var moved = 0
        val removed = mutableListOf<Long>()
        for (item in gone) {
            val match = fresh.firstOrNull { f -> f.name.equals(item.localPath!!.name, ignoreCase = true) && runCatching { f.fileSize() }.getOrNull() == item.fileSize }
            if (match != null) {
                db.relink(item.id, match.toString())
                fresh.remove(match)
                moved++
            } else {
                library.deleteThumbnail(item)
                db.removeMedia(item.id)
                removed += item.id
            }
        }
        val added = if (fresh.isEmpty()) 0 else library.importFiles(fresh).added.size
        return FolderSync(added, removed.size, moved).also {
            if (it.changed) log.info("$folder: ${it.added} added, ${it.removed} removed, ${it.moved} moved")
        }
    }

    /** Checks every watched folder. */
    fun syncAll(): FolderSync = folders.fold(FolderSync()) { total, folder -> total + sync(folder) }.also { if (it.changed) onChange(it) }

    /** Starts noticing changes (and checks everything once). Checks again every [periodMinutes]. */
    fun start(periodMinutes: Long = 10) {
        running = true
        restartWatching()
        thread(name = "watched-folders", isDaemon = true) {
            runCatching { syncAll() }.onFailure { log.warning("Checking watched folders failed: $it") }
            var lastFull = System.currentTimeMillis()
            while (running) {
                val service = watcher
                val key = runCatching { service?.poll(5, TimeUnit.SECONDS) }.getOrNull()
                if (service == null) Thread.sleep(5_000)
                val dirty = mutableSetOf<Path>()
                if (key != null) {
                    keys[key]?.let(dirty::add)
                    key.pollEvents()
                    key.reset()
                    // Wait for a burst of changes (e.g. copying many files) to settle.
                    Thread.sleep(2_000)
                    generateSequence { runCatching { service?.poll() }.getOrNull() }.forEach { more ->
                        keys[more]?.let(dirty::add)
                        more.pollEvents()
                        more.reset()
                    }
                }
                if (System.currentTimeMillis() - lastFull > periodMinutes * 60_000) {
                    dirty += folders
                    lastFull = System.currentTimeMillis()
                }
                if (dirty.isNotEmpty()) {
                    runCatching {
                        val result = dirty.filter { it in folders }.fold(FolderSync()) { total, folder -> total + sync(folder) }
                        if (result.changed) onChange(result)
                        if (key != null) restartWatching() // new sub-folders need watching too
                    }.onFailure { log.warning("Checking watched folders failed: $it") }
                }
            }
        }
    }

    /** (Re)registers every folder and sub-folder with a fresh watch service. */
    private fun restartWatching() {
        if (!running) return
        synchronized(lock) {
            runCatching { watcher?.close() }
            keys.clear()
            val service = FileSystems.getDefault().newWatchService()
            for (root in folders.filter { it.isDirectory() }) {
                runCatching {
                    Files.walk(root).use { dirs ->
                        dirs.filter { it.isDirectory() }.forEach { dir ->
                            keys[dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = root
                        }
                    }
                }.onFailure { log.warning("Couldn't watch $root: $it") }
            }
            watcher = service
        }
    }

    override fun close() {
        running = false
        runCatching { watcher?.close() }.onFailure { if (it !is ClosedWatchServiceException) log.fine("$it") }
    }
}
