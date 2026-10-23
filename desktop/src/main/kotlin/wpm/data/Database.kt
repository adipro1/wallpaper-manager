package wpm.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import wpm.logger
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import kotlin.io.path.name

/**
 * SQLite storage for settings and library metadata - the same database file and
 * schema as version 1.x. Only metadata is stored; the media files stay where they are.
 *
 * The schema is versioned with PRAGMA user_version; to change it, append a new
 * entry to [MIGRATIONS] (never edit an old one).
 */
val MIGRATIONS = listOf(
    // 1: settings + media library
    """
    CREATE TABLE settings (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE media (
        id          INTEGER PRIMARY KEY,
        path        TEXT NOT NULL UNIQUE COLLATE NOCASE,
        filename    TEXT NOT NULL,
        media_type  TEXT NOT NULL CHECK (media_type IN ('image', 'video')),
        width       INTEGER,
        height      INTEGER,
        duration    REAL,
        file_size   INTEGER,
        favorite    INTEGER NOT NULL DEFAULT 0,
        date_added  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
        thumbnail   TEXT
    );
    CREATE INDEX idx_media_type ON media(media_type);
    CREATE INDEX idx_media_favorite ON media(favorite);
    """,
    // 2: playlists
    """
    CREATE TABLE playlists (
        id                INTEGER PRIMARY KEY,
        name              TEXT NOT NULL,
        mode              TEXT NOT NULL DEFAULT 'sequential' CHECK (mode IN ('sequential', 'random')),
        interval_seconds  INTEGER NOT NULL DEFAULT 1800,
        created           TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
    );
    CREATE TABLE playlist_items (
        playlist_id  INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
        media_id     INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
        position     INTEGER NOT NULL,
        PRIMARY KEY (playlist_id, media_id)
    );
    """,
    // 3: time-based schedule; each rule shows either one wallpaper or a playlist
    """
    CREATE TABLE schedule_rules (
        id           INTEGER PRIMARY KEY,
        time         TEXT NOT NULL,  -- 'HH:MM', every day
        media_id     INTEGER REFERENCES media(id) ON DELETE CASCADE,
        playlist_id  INTEGER REFERENCES playlists(id) ON DELETE CASCADE,
        enabled      INTEGER NOT NULL DEFAULT 1,
        CHECK ((media_id IS NULL) != (playlist_id IS NULL))
    );
    """,
    // 4 (2.0): streamed video URLs, web and shader wallpapers. SQLite can't change a CHECK
    // constraint, so the media table is rebuilt; ids are kept, so playlists and rules stay valid.
    """
    CREATE TABLE media_new (
        id           INTEGER PRIMARY KEY,
        path         TEXT UNIQUE COLLATE NOCASE,  -- local file or folder; NULL for streamed URLs and websites
        source_url   TEXT UNIQUE,                 -- where a video or website came from
        filename     TEXT NOT NULL,               -- display name
        media_type   TEXT NOT NULL CHECK (media_type IN ('image', 'video', 'web', 'shader')),
        width        INTEGER,
        height       INTEGER,
        duration     REAL,
        file_size    INTEGER,
        favorite     INTEGER NOT NULL DEFAULT 0,
        date_added   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
        thumbnail    TEXT,
        entry        TEXT,                        -- web wallpapers: the page inside the folder
        interactive  INTEGER NOT NULL DEFAULT 0,  -- web wallpapers: pass mouse input through
        audio        INTEGER NOT NULL DEFAULT 0,  -- web/shader wallpapers: wants system audio levels
        CHECK (path IS NOT NULL OR source_url IS NOT NULL)
    );
    INSERT INTO media_new (id, path, filename, media_type, width, height, duration, file_size, favorite, date_added, thumbnail)
        SELECT id, path, filename, media_type, width, height, duration, file_size, favorite, date_added, thumbnail FROM media;
    DROP TABLE media;
    ALTER TABLE media_new RENAME TO media;
    CREATE INDEX idx_media_type ON media(media_type);
    CREATE INDEX idx_media_favorite ON media(favorite);
    """,
    // 5 (2.1): tags
    """
    CREATE TABLE tags (
        id    INTEGER PRIMARY KEY,
        name  TEXT NOT NULL UNIQUE COLLATE NOCASE
    );
    CREATE TABLE media_tags (
        media_id  INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
        tag_id    INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
        PRIMARY KEY (media_id, tag_id)
    );
    CREATE INDEX idx_media_tags_tag ON media_tags(tag_id);
    """,
    // 6 (3.0): playlists that follow the sun. The CHECK constraint can't change, so the playlists
    // table is rebuilt; ids are kept, so playlist items and schedule rules stay valid.
    """
    CREATE TABLE playlists_new (
        id                INTEGER PRIMARY KEY,
        name              TEXT NOT NULL,
        mode              TEXT NOT NULL DEFAULT 'sequential' CHECK (mode IN ('sequential', 'random', 'sun')),
        interval_seconds  INTEGER NOT NULL DEFAULT 1800,
        created           TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
    );
    INSERT INTO playlists_new (id, name, mode, interval_seconds, created)
        SELECT id, name, mode, interval_seconds, created FROM playlists;
    DROP TABLE playlists;
    ALTER TABLE playlists_new RENAME TO playlists;
    """,
)

/** Migrations that rebuild a table: foreign keys must be off while they run, or the old table's rows cascade away. */
private val TABLE_REBUILDS = setOf(4, 6)

private val DEFAULT_SETTINGS: Map<String, JsonElement> = mapOf(
    "appearance_mode" to JsonPrimitive("dark"),
)

private val SORT_ORDERS = mapOf(
    "newest" to "date_added DESC, id DESC",
    "oldest" to "date_added ASC, id ASC",
    "name" to "filename COLLATE NOCASE ASC",
    "name_desc" to "filename COLLATE NOCASE DESC",
)

private val log = logger("database")

/** Newer than this app understands: refuse to open rather than corrupt it. */
class DatabaseTooNewException(version: Int) :
    IllegalStateException("The library database is version $version, which is newer than this app supports.")

/**
 * Thin wrapper around one SQLite connection. The connection is shared between the
 * UI and background workers (e.g. importing), so every call takes a lock.
 */
class Database(private val path: String) : AutoCloseable {
    constructor(path: Path) : this(path.toString())

    private val lock = Any()
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
        }
        migrate()
    }

    private fun migrate() = synchronized(lock) {
        val version = schemaVersion
        if (version > MIGRATIONS.size) throw DatabaseTooNewException(version)
        if (version in 1 until MIGRATIONS.size) backup(version)
        for (number in version + 1..MIGRATIONS.size) {
            log.info("Applying database migration $number")
            val rebuild = number in TABLE_REBUILDS
            conn.createStatement().use { st ->
                if (rebuild) st.execute("PRAGMA foreign_keys = OFF") // has no effect inside a transaction
                try {
                    inTransaction {
                        splitStatements(MIGRATIONS[number - 1]).forEach { st.execute(it) }
                        if (rebuild) {
                            st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                                if (rs.next()) throw IllegalStateException("Migration $number broke a reference in ${rs.getString(1)}")
                            }
                        }
                        st.execute("PRAGMA user_version = $number")
                    }
                } finally {
                    if (rebuild) st.execute("PRAGMA foreign_keys = ON")
                }
            }
        }
    }

    /** Copy of the database before upgrading it, e.g. library.v3.bak.db (older app versions can't read newer schemas). */
    private fun backup(version: Int) {
        val target = path.removeSuffix(".db") + ".v$version.bak.db"
        if (java.io.File(target).exists()) return
        runCatching { conn.createStatement().use { it.execute("VACUUM INTO '" + target.replace("'", "''") + "'") } }
            .onSuccess { log.info("Backed up the library database to $target") }
            .onFailure { log.warning("Could not back up the database before upgrading: $it") }
    }

    /** A consistent copy of the whole database in [target] (which must not exist yet). */
    fun snapshot(target: Path) = synchronized(lock) {
        conn.createStatement().use { it.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'") }
    }

    val schemaVersion: Int
        get() = synchronized(lock) {
            conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }

    override fun close() = synchronized(lock) { conn.close() }

    // -- low level helpers ---------------------------------------------------------

    private fun <T> inTransaction(block: () -> T): T = synchronized(lock) {
        conn.autoCommit = false
        try {
            block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun PreparedStatement.bind(params: Array<out Any?>) {
        params.forEachIndexed { i, value ->
            when (value) {
                null -> setNull(i + 1, Types.NULL)
                is Boolean -> setInt(i + 1, if (value) 1 else 0)
                is Int -> setInt(i + 1, value)
                is Long -> setLong(i + 1, value)
                is Double -> setDouble(i + 1, value)
                else -> setString(i + 1, value.toString())
            }
        }
    }

    /** Runs an INSERT/UPDATE/DELETE. Returns (rows changed, last inserted id). */
    fun execute(sql: String, vararg params: Any?): Pair<Int, Long> = synchronized(lock) {
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { st ->
            st.bind(params)
            val changed = st.executeUpdate()
            val id = if (changed > 0) st.generatedKeys.use { if (it.next()) it.getLong(1) else 0L } else 0L
            changed to id
        }
    }

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> = synchronized(lock) {
        conn.prepareStatement(sql).use { st ->
            st.bind(params)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    // -- settings ------------------------------------------------------------------

    /** A setting's JSON value, or its default (or null) if it isn't set or can't be read. */
    fun getSetting(key: String): JsonElement? {
        val raw = query("SELECT value FROM settings WHERE key = ?", key) { it.getString(1) }.firstOrNull()
        if (raw != null) {
            try {
                return Json.parseToJsonElement(raw)
            } catch (e: Exception) {
                log.warning("Ignoring corrupt setting '$key'")
            }
        }
        return DEFAULT_SETTINGS[key]
    }

    fun setSetting(key: String, value: JsonElement) {
        execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            key, value.toString(),
        )
    }

    // -- media library -------------------------------------------------------------

    private fun mediaFrom(rs: ResultSet) = MediaItem(
        id = rs.getLong("id"),
        path = rs.getString("path"),
        sourceUrl = rs.getString("source_url"),
        filename = rs.getString("filename"),
        mediaType = rs.getString("media_type"),
        width = rs.getObject("width")?.let { (it as Number).toInt() },
        height = rs.getObject("height")?.let { (it as Number).toInt() },
        duration = rs.getObject("duration")?.let { (it as Number).toDouble() },
        fileSize = rs.getObject("file_size")?.let { (it as Number).toLong() },
        favorite = rs.getInt("favorite") != 0,
        dateAdded = rs.getString("date_added"),
        thumbnail = rs.getString("thumbnail"),
        entry = rs.getString("entry"),
        interactive = rs.getInt("interactive") != 0,
        audio = rs.getInt("audio") != 0,
    )

    /** Inserts a library entry. Returns the new id, or null if the path is already in the library. */
    fun addMedia(path: String, mediaType: String, width: Int?, height: Int?, duration: Double?,
                 fileSize: Long?, thumbnail: String?, sourceUrl: String? = null): Long? {
        val (changed, id) = execute(
            "INSERT OR IGNORE INTO media (path, filename, media_type, width, height, duration, file_size, thumbnail, source_url)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            path, Path.of(path).name, mediaType, width, height, duration, fileSize, thumbnail, sourceUrl,
        )
        return if (changed > 0) id else null
    }

    /**
     * Inserts an entry that isn't a single local file: a streamed URL (no [path]), a website,
     * or a web/shader wallpaper folder. Returns the new id, or null if it is already in the library.
     */
    fun addItem(
        name: String, mediaType: String, path: String? = null, sourceUrl: String? = null, entry: String? = null,
        width: Int? = null, height: Int? = null, duration: Double? = null, fileSize: Long? = null,
        thumbnail: String? = null, interactive: Boolean = false, audio: Boolean = false,
    ): Long? {
        require(path != null || sourceUrl != null) { "An item needs a path or a URL" }
        val (changed, id) = execute(
            "INSERT OR IGNORE INTO media (path, source_url, filename, media_type, width, height, duration, file_size, thumbnail," +
                " entry, interactive, audio) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            path, sourceUrl, name, mediaType, width, height, duration, fileSize, thumbnail, entry, interactive, audio,
        )
        return if (changed > 0) id else null
    }

    fun updateItemOptions(mediaId: Long, interactive: Boolean, audio: Boolean) {
        execute("UPDATE media SET interactive = ?, audio = ? WHERE id = ?", interactive, audio, mediaId)
    }

    /** Moves an item to another file or folder (and renames it to [newName] if given). */
    fun relink(mediaId: Long, path: String, newName: String? = null) {
        execute("UPDATE media SET path = ?, filename = COALESCE(?, filename) WHERE id = ?", path, newName, mediaId)
    }

    fun rename(mediaId: Long, name: String) {
        execute("UPDATE media SET filename = ? WHERE id = ?", name, mediaId)
    }

    fun hasMedia(path: String): Boolean = query("SELECT 1 FROM media WHERE path = ?", path) { true }.isNotEmpty()

    fun hasUrl(url: String): Boolean = query("SELECT 1 FROM media WHERE source_url = ?", url) { true }.isNotEmpty()

    fun getMedia(mediaId: Long): MediaItem? = query("SELECT * FROM media WHERE id = ?", mediaId, map = ::mediaFrom).firstOrNull()

    /** The library item for a local file or folder (paths compare case-insensitively, like Windows). */
    fun mediaByPath(path: String): MediaItem? = query("SELECT * FROM media WHERE path = ?", path, map = ::mediaFrom).firstOrNull()

    private fun mediaFilter(search: String, mediaType: String?, favoritesOnly: Boolean, tag: String? = null): Pair<String, List<Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (search.isNotBlank()) {
            // The name, or one of its tags.
            clauses += "(instr(lower(filename), lower(?)) > 0 OR EXISTS (SELECT 1 FROM media_tags mt JOIN tags t ON t.id = mt.tag_id " +
                "WHERE mt.media_id = media.id AND instr(lower(t.name), lower(?)) > 0))"
            params += search.trim()
            params += search.trim()
        }
        if (tag != null) {
            clauses += "EXISTS (SELECT 1 FROM media_tags mt JOIN tags t ON t.id = mt.tag_id WHERE mt.media_id = media.id AND t.name = ?)"
            params += tag
        }
        if (mediaType == "interactive") {
            clauses += "media_type IN ('web', 'shader')"
        } else if (mediaType != null) {
            clauses += "media_type = ?"
            params += mediaType
        }
        if (favoritesOnly) clauses += "favorite = 1"
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to params
    }

    fun listMedia(search: String = "", mediaType: String? = null, favoritesOnly: Boolean = false,
                  sort: String = "newest", limit: Int = -1, offset: Int = 0, tag: String? = null): List<MediaItem> {
        val (where, params) = mediaFilter(search, mediaType, favoritesOnly, tag)
        val order = SORT_ORDERS[sort] ?: SORT_ORDERS.getValue("newest")
        return query("SELECT * FROM media $where ORDER BY $order LIMIT ? OFFSET ?",
            *params.toTypedArray(), limit, offset, map = ::mediaFrom)
    }

    fun countMedia(search: String = "", mediaType: String? = null, favoritesOnly: Boolean = false): Int {
        val (where, params) = mediaFilter(search, mediaType, favoritesOnly)
        return query("SELECT COUNT(*) FROM media $where", *params.toTypedArray()) { it.getInt(1) }.first()
    }

    fun setFavorite(mediaId: Long, favorite: Boolean) {
        execute("UPDATE media SET favorite = ? WHERE id = ?", favorite, mediaId)
    }

    fun setThumbnail(mediaId: Long, thumbnail: String?) {
        execute("UPDATE media SET thumbnail = ? WHERE id = ?", thumbnail, mediaId)
    }

    /** Removes the library entry only. The media file itself is not touched. */
    fun removeMedia(mediaId: Long) {
        execute("DELETE FROM media WHERE id = ?", mediaId)
    }

    /** How many playlist entries and schedule rules removing these items would also delete. */
    fun removalImpact(mediaIds: Collection<Long>): Pair<Int, Int> {
        if (mediaIds.isEmpty()) return 0 to 0
        val marks = mediaIds.joinToString(",") { "?" }
        val ids = mediaIds.toTypedArray()
        val items = query("SELECT COUNT(*) FROM playlist_items WHERE media_id IN ($marks)", *ids) { it.getInt(1) }.first()
        val rules = query("SELECT COUNT(*) FROM schedule_rules WHERE media_id IN ($marks)", *ids) { it.getInt(1) }.first()
        return items to rules
    }

    fun mediaStats(): MediaStats = query(
        "SELECT COUNT(*), COALESCE(SUM(media_type = 'image'), 0), COALESCE(SUM(media_type = 'video'), 0)," +
            " COALESCE(SUM(favorite), 0), COALESCE(SUM(media_type IN ('web', 'shader')), 0) FROM media",
    ) { MediaStats(it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5)) }.first()

    // -- tags --------------------------------------------------------------------------

    /** Every tag with how many items have it, by name. */
    fun listTags(): List<Pair<String, Int>> = query(
        "SELECT t.name, COUNT(mt.media_id) FROM tags t JOIN media_tags mt ON mt.tag_id = t.id GROUP BY t.id ORDER BY t.name COLLATE NOCASE",
    ) { it.getString(1) to it.getInt(2) }

    fun tagsOf(mediaId: Long): List<String> = query(
        "SELECT t.name FROM tags t JOIN media_tags mt ON mt.tag_id = t.id WHERE mt.media_id = ? ORDER BY t.name COLLATE NOCASE", mediaId,
    ) { it.getString(1) }

    /** Gives items a tag (created if new; an existing tag keeps its spelling). */
    fun addTag(mediaIds: Collection<Long>, name: String) = inTransaction {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "A tag needs a name" }
        require(clean.length <= 40) { "Keep tags under 40 characters" }
        execute("INSERT OR IGNORE INTO tags (name) VALUES (?)", clean)
        val tagId = query("SELECT id FROM tags WHERE name = ?", clean) { it.getLong(1) }.first()
        mediaIds.forEach { execute("INSERT OR IGNORE INTO media_tags (media_id, tag_id) VALUES (?, ?)", it, tagId) }
    }

    /** Takes a tag off an item; tags nobody uses any more are deleted. */
    fun removeTag(mediaId: Long, name: String) = inTransaction {
        execute("DELETE FROM media_tags WHERE media_id = ? AND tag_id = (SELECT id FROM tags WHERE name = ?)", mediaId, name)
        execute("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM media_tags)")
    }

    // -- playlists -----------------------------------------------------------------

    private val playlistSelect = "SELECT p.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id = p.id) AS item_count " +
        "FROM playlists p"

    private fun playlistFrom(rs: ResultSet) = Playlist(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        mode = rs.getString("mode"),
        intervalSeconds = rs.getInt("interval_seconds"),
        created = rs.getString("created"),
        itemCount = rs.getInt("item_count"),
    )

    fun createPlaylist(name: String, mode: String = "sequential", intervalSeconds: Int = 1800): Long =
        execute("INSERT INTO playlists (name, mode, interval_seconds) VALUES (?, ?, ?)", name, mode, intervalSeconds).second

    fun listPlaylists(): List<Playlist> = query("$playlistSelect ORDER BY p.name COLLATE NOCASE", map = ::playlistFrom)

    fun getPlaylist(playlistId: Long): Playlist? =
        query("$playlistSelect WHERE p.id = ?", playlistId, map = ::playlistFrom).firstOrNull()

    fun updatePlaylist(playlistId: Long, name: String? = null, mode: String? = null, intervalSeconds: Int? = null) {
        val fields = listOfNotNull(name?.let { "name" to it }, mode?.let { "mode" to it },
            intervalSeconds?.let { "interval_seconds" to it })
        if (fields.isEmpty()) return
        val assignments = fields.joinToString(", ") { "${it.first} = ?" }
        execute("UPDATE playlists SET $assignments WHERE id = ?", *fields.map { it.second }.toTypedArray(), playlistId)
    }

    fun deletePlaylist(playlistId: Long) {
        execute("DELETE FROM playlists WHERE id = ?", playlistId)
    }

    fun playlistItems(playlistId: Long): List<MediaItem> = query(
        "SELECT m.* FROM playlist_items i JOIN media m ON m.id = i.media_id WHERE i.playlist_id = ? ORDER BY i.position",
        playlistId, map = ::mediaFrom,
    )

    /** Appends items; items already in the playlist are skipped. */
    fun addToPlaylist(playlistId: Long, mediaIds: List<Long>) = inTransaction {
        var position = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = ?",
            playlistId) { it.getInt(1) }.first()
        for (mediaId in mediaIds) {
            val (changed, _) = execute(
                "INSERT OR IGNORE INTO playlist_items (playlist_id, media_id, position) VALUES (?, ?, ?)",
                playlistId, mediaId, position,
            )
            position += changed
        }
    }

    fun removeFromPlaylist(playlistId: Long, mediaId: Long) {
        execute("DELETE FROM playlist_items WHERE playlist_id = ? AND media_id = ?", playlistId, mediaId)
    }

    /** Moves an item up (delta=-1) or down (delta=+1) by swapping it with its neighbour. */
    fun moveInPlaylist(playlistId: Long, mediaId: Long, delta: Int) {
        val ids = playlistItems(playlistId).map { it.id }.toMutableList()
        val old = ids.indexOf(mediaId)
        if (old < 0) return
        val new = (old + delta).coerceIn(0, ids.lastIndex)
        ids.add(new, ids.removeAt(old))
        inTransaction {
            ids.forEachIndexed { pos, id ->
                execute("UPDATE playlist_items SET position = ? WHERE playlist_id = ? AND media_id = ?", pos, playlistId, id)
            }
        }
    }

    // -- schedule ------------------------------------------------------------------

    fun addRule(time: String, mediaId: Long? = null, playlistId: Long? = null): Long =
        execute("INSERT INTO schedule_rules (time, media_id, playlist_id) VALUES (?, ?, ?)", time, mediaId, playlistId).second

    fun updateRule(ruleId: Long, time: String, mediaId: Long?, playlistId: Long?) {
        execute("UPDATE schedule_rules SET time = ?, media_id = ?, playlist_id = ? WHERE id = ?", time, mediaId, playlistId, ruleId)
    }

    fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        execute("UPDATE schedule_rules SET enabled = ? WHERE id = ?", enabled, ruleId)
    }

    fun deleteRule(ruleId: Long) {
        execute("DELETE FROM schedule_rules WHERE id = ?", ruleId)
    }

    fun listRules(): List<ScheduleRule> = query(
        "SELECT r.*, COALESCE(m.filename, p.name, '') AS target_name FROM schedule_rules r " +
            "LEFT JOIN media m ON m.id = r.media_id LEFT JOIN playlists p ON p.id = r.playlist_id ORDER BY r.time, r.id",
    ) {
        ScheduleRule(
            id = it.getLong("id"),
            time = it.getString("time"),
            mediaId = it.getObject("media_id")?.let { v -> (v as Number).toLong() },
            playlistId = it.getObject("playlist_id")?.let { v -> (v as Number).toLong() },
            enabled = it.getInt("enabled") != 0,
            targetName = it.getString("target_name"),
        )
    }
}

/** Splits a migration script into statements (they never contain ';' inside literals). */
internal fun splitStatements(script: String): List<String> =
    script.lines().filterNot { it.trim().startsWith("--") }
        .joinToString("\n") { it.substringBefore(" --") }
        .split(";").map { it.trim() }.filter { it.isNotEmpty() }
