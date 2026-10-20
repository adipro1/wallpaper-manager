package wpm.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {
    private val dir: Path = Files.createTempDirectory("wpm-db")
    private val dbPath = dir.resolve("test.db")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun addImage(db: Database, name: String): Long =
        db.addMedia(dir.resolve(name).toString(), "image", 100, 50, null, 1234, null)!!

    @Test
    fun migrationsCreateSchema() {
        Database(dbPath).use { db ->
            assertEquals(MIGRATIONS.size, db.schemaVersion)
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }.toSet()
            assertTrue(tables.containsAll(setOf("settings", "media", "playlists", "playlist_items", "schedule_rules")))
        }
    }

    @Test
    fun reopenDoesNotRerunMigrations() {
        Database(dbPath).close()
        Database(dbPath).use { assertEquals(MIGRATIONS.size, it.schemaVersion) }
    }

    @Test
    fun upgradesFromVersion1() {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                splitStatements(MIGRATIONS[0]).forEach(st::execute)
                st.execute("PRAGMA user_version = 1")
            }
        }
        Database(dbPath).use { db ->
            assertEquals(MIGRATIONS.size, db.schemaVersion)
            assertEquals(1L, db.createPlaylist("works"))
        }
    }

    @Test
    fun upgradesVersion3LibraryWithoutLosingPlaylistsOrRules() {
        // A 1.x database with media, a playlist and schedule rules (foreign keys cascade on delete).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                MIGRATIONS.take(3).forEach { script -> splitStatements(script).forEach(st::execute) }
                st.execute("PRAGMA user_version = 3")
                st.execute("INSERT INTO media (id, path, filename, media_type, favorite) VALUES (7, 'C:\\a.png', 'a.png', 'image', 1)")
                st.execute("INSERT INTO media (id, path, filename, media_type, duration) VALUES (9, 'C:\\b.mp4', 'b.mp4', 'video', 12.5)")
                st.execute("INSERT INTO playlists (id, name) VALUES (1, 'Mix')")
                st.execute("INSERT INTO playlist_items VALUES (1, 7, 0), (1, 9, 1)")
                st.execute("INSERT INTO schedule_rules (time, media_id) VALUES ('07:00', 9)")
                st.execute("INSERT INTO settings VALUES ('appearance_mode', '\"light\"')")
            }
        }
        Database(dbPath).use { db ->
            assertEquals(4, db.schemaVersion)
            assertEquals(listOf(7L, 9L), db.playlistItems(1).map { it.id })
            assertEquals(9L, db.listRules().single().mediaId)
            val a = db.getMedia(7)!!
            assertTrue(a.favorite)
            assertEquals("C:\\a.png", a.path)
            assertEquals(12.5, db.getMedia(9)!!.duration)
            // New kinds of items work, and cascades still work after the rebuild.
            val stream = db.addItem("Rain", "video", sourceUrl = "https://example.com/rain")!!
            assertTrue(db.getMedia(stream)!!.isRemote)
            assertNull(db.addItem("Rain again", "video", sourceUrl = "https://example.com/rain"))
            db.removeMedia(9)
            assertEquals(listOf(7L), db.playlistItems(1).map { it.id })
            assertTrue(db.listRules().isEmpty())
            val fks = db.query("PRAGMA foreign_keys") { it.getInt(1) }.single()
            assertEquals(1, fks)
        }
        assertTrue(dir.resolve("test.v3.bak.db").toFile().exists())
        Database(dir.resolve("test.v3.bak.db")).close() // the backup is a working database (upgraded when opened)
    }

    @Test
    fun refusesNewerDatabase() {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        assertFailsWith<DatabaseTooNewException> { Database(dbPath) }
    }

    @Test
    fun settingsRoundtripAndDefaults() {
        Database(dbPath).use { db ->
            assertEquals(JsonPrimitive("dark"), db.getSetting("appearance_mode"))
            assertNull(db.getSetting("missing"))
            db.setSetting("appearance_mode", JsonPrimitive("light"))
            db.setSetting("complex", buildJsonObject { put("a", 1) })
        }
        Database(dbPath).use { db ->
            val settings = Settings(db)
            assertEquals("light", settings.string("appearance_mode", "dark"))
            assertEquals("""{"a":1}""", db.getSetting("complex").toString())
            assertEquals(true, settings.boolean("close_to_tray", true))
        }
    }

    @Test
    fun corruptSettingFallsBackToDefault() {
        Database(dbPath).use { db ->
            db.execute("INSERT INTO settings (key, value) VALUES ('appearance_mode', '{not json')")
            assertEquals("dark", Settings(db).string("appearance_mode", "x"))
        }
    }

    @Test
    fun mediaStatsAndQueries() {
        Database(dbPath).use { db ->
            assertEquals(MediaStats(0, 0, 0, 0), db.mediaStats())
            val a = addImage(db, "Beach.png")
            addImage(db, "mountain.jpg")
            db.addMedia(dir.resolve("clip.mp4").toString(), "video", 1920, 1080, 75.4, 10_000_000, null)
            assertNull(db.addMedia(dir.resolve("beach.PNG").toString(), "image", null, null, null, null, null)) // NOCASE duplicate
            db.setFavorite(a, true)

            assertEquals(MediaStats(3, 2, 1, 1), db.mediaStats())
            assertEquals(listOf("Beach.png"), db.listMedia(search = "BEACH").map { it.filename })
            assertEquals(listOf("Beach.png", "clip.mp4", "mountain.jpg"), db.listMedia(sort = "name").map { it.filename })
            assertEquals(1, db.countMedia(mediaType = "video"))
            assertEquals(1, db.countMedia(favoritesOnly = true))
            val clip = db.listMedia(mediaType = "video").single()
            assertEquals("1:15", clip.durationText)
            assertEquals("9.5 MB", clip.sizeText)
            assertEquals("1920 × 1080", clip.resolutionText)
        }
    }

    @Test
    fun playlistCrudAndOrdering() {
        Database(dbPath).use { db ->
            val ids = (0 until 4).map { addImage(db, "img$it.png") }
            val pid = db.createPlaylist("Mix", "random", 600)
            db.addToPlaylist(pid, ids)
            db.addToPlaylist(pid, ids.take(2)) // duplicates ignored
            val playlist = db.getPlaylist(pid)!!
            assertEquals(Triple("Mix", "random", 4), Triple(playlist.name, playlist.mode, playlist.itemCount))
            assertEquals("every 10 min", playlist.intervalText)

            db.moveInPlaylist(pid, ids[0], +1)
            assertEquals(listOf(ids[1], ids[0], ids[2], ids[3]), db.playlistItems(pid).map { it.id })
            db.moveInPlaylist(pid, ids[3], -5) // clamps to the top
            assertEquals(ids[3], db.playlistItems(pid).first().id)

            db.removeFromPlaylist(pid, ids[1])
            db.updatePlaylist(pid, name = "Renamed", intervalSeconds = 7200)
            assertEquals("every 2 h", db.getPlaylist(pid)!!.intervalText)
            assertEquals(3, db.getPlaylist(pid)!!.itemCount)

            val rule = db.addRule("07:30", mediaId = ids[2])
            assertEquals(1 to 1, db.removalImpact(listOf(ids[2])))
            db.removeMedia(ids[2]) // cascades to playlists and rules
            assertTrue(ids[2] !in db.playlistItems(pid).map { it.id })
            assertTrue(db.listRules().none { it.id == rule })
            db.deletePlaylist(pid)
            assertEquals(emptyList(), db.listPlaylists())
        }
    }

    @Test
    fun scheduleRules() {
        Database(dbPath).use { db ->
            val media = addImage(db, "a.png")
            val pid = db.createPlaylist("Evening")
            val r1 = db.addRule("18:00", playlistId = pid)
            db.addRule("07:05", mediaId = media)
            db.setRuleEnabled(r1, false)
            val rules = db.listRules()
            assertEquals(listOf("07:05", "18:00"), rules.map { it.time })
            assertEquals(listOf("a.png", "Evening"), rules.map { it.targetName })
            assertEquals(listOf(true, false), rules.map { it.enabled })
            assertEquals(425, rules.first().minutes)
        }
    }
}
