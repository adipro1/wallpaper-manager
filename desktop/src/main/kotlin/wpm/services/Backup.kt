package wpm.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wpm.APP_VERSION
import wpm.AppPaths
import wpm.data.Database
import wpm.data.MIGRATIONS
import wpm.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = logger("backup")
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** What a backup file says about itself (manifest.json). */
@Serializable
data class BackupInfo(
    val app: String = "WallpaperManager",
    val version: String = "",
    val schema: Int = 0,
    val created: String = "",
    /** The data folder it was made from, so its paths can be moved to this PC's folder. */
    val dataDir: String = "",
    val items: Int = 0,
)

/**
 * Backups of everything the app keeps: the library, playlists, schedule and settings (the
 * database), thumbnails, shaders, unpacked web wallpapers and lock screen pictures. Not the
 * wallpaper files themselves (they stay where they are), the web engine or the logs.
 *
 * Restoring happens in two steps: [stage] checks and unpacks the backup next to the data,
 * and [applyPending] swaps it in when the app starts, before the database is opened.
 */
object Backup {
    const val EXTENSION = "wpmbackup"
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "library.db"
    private val FOLDERS = listOf("thumbnails", "shaders", "web", "lockscreen")
    private const val PENDING = "restore-pending"
    private const val READY = ".ready"

    /** Writes a backup of the app's data to [target]. Returns what it contains. */
    fun export(target: Path, db: Database, paths: AppPaths): BackupInfo {
        val temp = Files.createTempFile("wpm-backup", ".db")
        Files.delete(temp) // VACUUM INTO needs a file that doesn't exist yet
        try {
            db.snapshot(temp)
            val info = BackupInfo(
                version = APP_VERSION, schema = db.schemaVersion, created = LocalDateTime.now().toString(),
                dataDir = paths.dataDir.toAbsolutePath().toString(), items = db.countMedia(),
            )
            Files.createDirectories(target.toAbsolutePath().parent)
            val partial = target.resolveSibling(target.name + ".part")
            ZipOutputStream(Files.newOutputStream(partial)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.encodeToString(BackupInfo.serializer(), info).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DATABASE))
                Files.copy(temp, zip)
                zip.closeEntry()
                for (folder in FOLDERS) {
                    val dir = paths.dataDir.resolve(folder)
                    if (!dir.isDirectory()) continue
                    Files.walk(dir).use { files ->
                        files.filter { it.isRegularFile() }.forEach { file ->
                            zip.putNextEntry(ZipEntry("$folder/" + dir.relativize(file).toString().replace('\\', '/')))
                            Files.copy(file, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            log.info("Backup with ${info.items} items written to $target")
            return info
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Reads a backup's manifest and checks that this version can restore it. */
    fun inspect(file: Path): BackupInfo {
        var info: BackupInfo? = null
        var hasDatabase = false
        ZipInputStream(Files.newInputStream(file)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                when (entry.name) {
                    MANIFEST -> info = runCatching { json.decodeFromString(BackupInfo.serializer(), zip.readBytes().decodeToString()) }.getOrNull()
                    DATABASE -> hasDatabase = true
                }
            }
        }
        val found = info?.takeIf { hasDatabase && it.app == "WallpaperManager" }
            ?: throw IOException("This isn't a Wallpaper Manager backup")
        if (found.schema > MIGRATIONS.size) {
            throw IOException("This backup comes from a newer version (${found.version}). Update the app first.")
        }
        return found
    }

    /** Checks and unpacks a backup so it replaces the current data on the next start. */
    fun stage(file: Path, paths: AppPaths): BackupInfo {
        val info = inspect(file)
        val pending = paths.dataDir.resolve(PENDING)
        if (pending.exists()) pending.toFile().deleteRecursively()
        Files.createDirectories(pending)
        ZipInputStream(Files.newInputStream(file)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val out = pending.resolve(entry.name).normalize()
                if (!out.startsWith(pending)) throw IOException("The backup contains an unsafe path: ${entry.name}")
                if (entry.isDirectory) Files.createDirectories(out) else {
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        // Only a completely unpacked backup is applied.
        Files.writeString(pending.resolve(READY), info.created)
        log.info("Backup from ${info.created} staged; it is applied when the app starts again")
        return info
    }

    fun hasPending(paths: AppPaths): Boolean = paths.dataDir.resolve(PENDING).resolve(READY).exists()

    /**
     * Swaps in a staged backup (before the database is opened). The data it replaces is kept
     * in before-restore-<time>. Returns true if a backup was applied.
     */
    fun applyPending(paths: AppPaths): Boolean {
        val pending = paths.dataDir.resolve(PENDING)
        if (!pending.resolve(READY).exists()) {
            if (pending.exists()) pending.toFile().deleteRecursively() // half-unpacked: ignore
            return false
        }
        val info = runCatching { json.decodeFromString(BackupInfo.serializer(), Files.readString(pending.resolve(MANIFEST))) }.getOrNull()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val previous = paths.dataDir.resolve("before-restore-$stamp")
        Files.createDirectories(previous)
        val replaced = listOf(DATABASE, "$DATABASE-wal", "$DATABASE-shm") + FOLDERS
        for (name in replaced) {
            val current = paths.dataDir.resolve(name)
            if (current.exists()) Files.move(current, previous.resolve(name))
        }
        for (name in listOf(DATABASE) + FOLDERS) {
            val staged = pending.resolve(name)
            if (staged.exists()) Files.move(staged, paths.dataDir.resolve(name))
        }
        Files.createDirectories(paths.thumbDir)
        pending.toFile().deleteRecursively()
        info?.dataDir?.takeIf { it.isNotEmpty() }?.let { moveDataPaths(paths.dbPath, it, paths.dataDir.toAbsolutePath().toString()) }
        log.info("Restored the backup from ${info?.created}; the previous data is in $previous")
        return true
    }

    /** Shaders and unpacked web wallpapers live in the data folder: point them at this PC's folder. */
    internal fun moveDataPaths(dbPath: Path, from: String, to: String) {
        if (from.equals(to, ignoreCase = true)) return
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement(
                "UPDATE media SET path = ? || substr(path, ?) WHERE lower(substr(path, 1, ?)) = lower(?)",
            ).use { st ->
                st.setString(1, to)
                st.setInt(2, from.length + 1)
                st.setInt(3, from.length)
                st.setString(4, from)
                log.info("Moved ${st.executeUpdate()} paths from $from to $to")
            }
        }
    }
}
