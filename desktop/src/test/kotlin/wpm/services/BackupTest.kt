package wpm.services

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.media.MediaLibrary
import wpm.media.Shaders
import wpm.media.WebWallpapers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val dir: Path = Files.createTempDirectory("wpm-backup")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun backupAndRestoreOnAnotherPc() {
        // The "old PC": two pictures, a playlist, a rule, a shader and a setting.
        val oldPaths = AppPaths(dir.resolve("old")).ensure()
        val file = dir.resolve("my.wpmbackup")
        Database(oldPaths.dbPath).use { db ->
            val library = MediaLibrary(db, oldPaths.thumbDir)
            val ids = library.importFiles(listOf(Samples.image(dir.resolve("a.png")), Samples.image(dir.resolve("b.png")))).added
            val playlist = db.createPlaylist("Evenings")
            db.addToPlaylist(playlist, ids)
            db.addRule("sunset", playlistId = playlist)
            val shaders = Shaders(db, WebWallpapers(db, library, oldPaths.dataDir.resolve("web")), oldPaths.dataDir.resolve("shaders"), oldPaths.cacheDir)
            shaders.create("Waves", "void mainImage(out vec4 c, in vec2 p) { c = vec4(1.0); }")
            wpm.data.Settings(db).set("appearance_mode", "light")
            val info = Backup.export(file, db, oldPaths)
            assertEquals(3, info.items)
        }
        assertEquals(3, Backup.inspect(file).items)

        // The "new PC" has its own (different) data.
        val newPaths = AppPaths(dir.resolve("new")).ensure()
        Database(newPaths.dbPath).use { it.createPlaylist("Will be replaced") }
        Backup.stage(file, newPaths)
        assertTrue(Backup.hasPending(newPaths))
        assertTrue(Backup.applyPending(newPaths))
        assertFalse(Backup.hasPending(newPaths))
        assertFalse(Backup.applyPending(newPaths)) // only once

        Database(newPaths.dbPath).use { db ->
            assertEquals(listOf("Evenings"), db.listPlaylists().map { it.name })
            assertEquals(2, db.playlistItems(db.listPlaylists().single().id).size)
            assertEquals("sunset", db.listRules().single().time)
            assertEquals("light", wpm.data.Settings(db).string("appearance_mode", "dark"))
            // The shader now lives in the new data folder, and its file came along.
            val shader = db.listMedia(mediaType = "shader").single()
            assertTrue(shader.path!!.startsWith(newPaths.dataDir.toString()), shader.path)
            assertTrue(shader.localPath!!.exists())
            assertTrue("mainImage" in shader.localPath!!.readText())
            // Thumbnails came along too.
            db.listMedia(mediaType = "image").forEach { assertTrue(newPaths.thumbDir.resolve(it.thumbnail!!).exists()) }
        }
        // The replaced data is kept.
        val kept = newPaths.dataDir.listDirectoryEntries("before-restore-*").single()
        Database(kept.resolve("library.db")).use { assertEquals("Will be replaced", it.listPlaylists().single().name) }
    }

    @Test
    fun refusesOtherFilesAndNewerBackups() {
        val notZip = dir.resolve("x.wpmbackup").also { Files.writeString(it, "hello") }
        assertFailsWith<IOException> { Backup.inspect(notZip) }
        fun zip(name: String, manifest: String) = dir.resolve(name).also { target ->
            ZipOutputStream(Files.newOutputStream(target)).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("library.db")); z.closeEntry()
            }
        }
        assertFailsWith<IOException> { Backup.inspect(zip("future.wpmbackup", """{"app":"WallpaperManager","version":"9.0.0","schema":999}""")) }
        assertFailsWith<IOException> { Backup.inspect(zip("other.wpmbackup", """{"app":"SomethingElse","schema":1}""")) }
        // A half-unpacked restore (no .ready marker) is ignored and cleaned up.
        val paths = AppPaths(dir.resolve("data")).ensure()
        Files.createDirectories(paths.dataDir.resolve("restore-pending"))
        assertFalse(Backup.applyPending(paths))
        assertFalse(paths.dataDir.resolve("restore-pending").exists())
        assertTrue(paths.dataDir.listDirectoryEntries().none { it.name.startsWith("before-restore") })
    }
}
