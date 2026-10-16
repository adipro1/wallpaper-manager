"""SQLite storage for settings and library metadata.

Only metadata is stored here - the media files themselves stay where they are.
The schema is versioned with PRAGMA user_version; to change it, append a new
entry to MIGRATIONS (never edit an old one).
"""

import json
import logging
import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

MIGRATIONS = [
    # 1: settings + media library
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
    # 2: playlists
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
    # 3: time-based schedule; each rule shows either one wallpaper or a playlist
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
]

DEFAULT_SETTINGS: dict[str, Any] = {
    "appearance_mode": "dark",
}

SORT_ORDERS = {
    "newest": "date_added DESC, id DESC",
    "oldest": "date_added ASC, id ASC",
    "name": "filename COLLATE NOCASE ASC",
    "name_desc": "filename COLLATE NOCASE DESC",
}


@dataclass
class Playlist:
    id: int
    name: str
    mode: str  # 'sequential' or 'random'
    interval_seconds: int
    created: str
    item_count: int = 0

    @property
    def interval_text(self) -> str:
        minutes = self.interval_seconds // 60
        if minutes >= 60 and minutes % 60 == 0:
            return f"every {minutes // 60} h"
        return f"every {minutes} min" if minutes else f"every {self.interval_seconds} s"


@dataclass
class ScheduleRule:
    id: int
    time: str  # 'HH:MM'
    media_id: int | None
    playlist_id: int | None
    enabled: bool
    target_name: str = ""  # file name or playlist name, filled in by the query

    @property
    def minutes(self) -> int:
        hours, minutes = map(int, self.time.split(":"))
        return hours * 60 + minutes


@dataclass
class MediaItem:
    id: int
    path: str
    filename: str
    media_type: str  # 'image' or 'video'
    width: int | None
    height: int | None
    duration: float | None  # seconds, videos only
    file_size: int | None
    favorite: bool
    date_added: str
    thumbnail: str | None  # file name inside the thumbnail folder

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> "MediaItem":
        data = dict(row)
        data["favorite"] = bool(data["favorite"])
        return cls(**data)

    @property
    def is_video(self) -> bool:
        return self.media_type == "video"

    @property
    def resolution_text(self) -> str:
        return f"{self.width} × {self.height}" if self.width and self.height else "Unknown"

    @property
    def duration_text(self) -> str:
        if not self.duration:
            return ""
        total = int(round(self.duration))
        hours, rest = divmod(total, 3600)
        minutes, seconds = divmod(rest, 60)
        return f"{hours}:{minutes:02}:{seconds:02}" if hours else f"{minutes}:{seconds:02}"

    @property
    def size_text(self) -> str:
        size = float(self.file_size or 0)
        if size < 1024:
            return f"{size:.0f} B"
        for unit in ("KB", "MB"):
            size /= 1024
            if size < 1024:
                return f"{size:.1f} {unit}"
        return f"{size / 1024:.1f} GB"


class Database:
    """Thin wrapper around one SQLite connection.

    The connection is shared between the UI thread and background workers
    (e.g. importing), so every call takes a lock.
    """

    def __init__(self, path: Path | str):
        self.path = str(path)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._conn.execute("PRAGMA journal_mode = WAL")
        self._migrate()

    def _migrate(self) -> None:
        with self._lock:
            version = self._conn.execute("PRAGMA user_version").fetchone()[0]
            for number, script in enumerate(MIGRATIONS[version:], start=version + 1):
                log.info("Applying database migration %d", number)
                self._conn.executescript(f"BEGIN;\n{script}\nPRAGMA user_version = {number};\nCOMMIT;")

    @property
    def schema_version(self) -> int:
        with self._lock:
            return self._conn.execute("PRAGMA user_version").fetchone()[0]

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- low level helpers -------------------------------------------------

    def execute(self, sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
        with self._lock, self._conn:
            return self._conn.execute(sql, params)

    def query(self, sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(sql, params).fetchall()

    # -- settings ------------------------------------------------------------

    def get_setting(self, key: str, default: Any = None) -> Any:
        rows = self.query("SELECT value FROM settings WHERE key = ?", (key,))
        if rows:
            try:
                return json.loads(rows[0]["value"])
            except json.JSONDecodeError:
                log.warning("Ignoring corrupt setting %r", key)
        if default is None:
            default = DEFAULT_SETTINGS.get(key)
        return default

    def set_setting(self, key: str, value: Any) -> None:
        self.execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, json.dumps(value)),
        )

    # -- media library -------------------------------------------------------

    def add_media(self, path: str, media_type: str, width: int | None, height: int | None,
                  duration: float | None, file_size: int | None, thumbnail: str | None) -> int | None:
        """Insert a library entry. Returns the new id, or None if the path is already in the library."""
        cursor = self.execute(
            "INSERT OR IGNORE INTO media (path, filename, media_type, width, height, duration, file_size, thumbnail)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (path, Path(path).name, media_type, width, height, duration, file_size, thumbnail),
        )
        return cursor.lastrowid if cursor.rowcount else None

    def has_media(self, path: str) -> bool:
        return bool(self.query("SELECT 1 FROM media WHERE path = ?", (path,)))

    def get_media(self, media_id: int) -> MediaItem | None:
        rows = self.query("SELECT * FROM media WHERE id = ?", (media_id,))
        return MediaItem.from_row(rows[0]) if rows else None

    @staticmethod
    def _media_filter(search: str, media_type: str | None, favorites_only: bool) -> tuple[str, list]:
        clauses, params = [], []
        if search.strip():
            clauses.append("instr(lower(filename), lower(?)) > 0")
            params.append(search.strip())
        if media_type:
            clauses.append("media_type = ?")
            params.append(media_type)
        if favorites_only:
            clauses.append("favorite = 1")
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        return where, params

    def list_media(self, search: str = "", media_type: str | None = None, favorites_only: bool = False,
                   sort: str = "newest", limit: int = -1, offset: int = 0) -> list[MediaItem]:
        where, params = self._media_filter(search, media_type, favorites_only)
        order = SORT_ORDERS.get(sort, SORT_ORDERS["newest"])
        rows = self.query(f"SELECT * FROM media {where} ORDER BY {order} LIMIT ? OFFSET ?",
                          (*params, limit, offset))
        return [MediaItem.from_row(r) for r in rows]

    def count_media(self, search: str = "", media_type: str | None = None, favorites_only: bool = False) -> int:
        where, params = self._media_filter(search, media_type, favorites_only)
        return self.query(f"SELECT COUNT(*) FROM media {where}", tuple(params))[0][0]

    def set_favorite(self, media_id: int, favorite: bool) -> None:
        self.execute("UPDATE media SET favorite = ? WHERE id = ?", (int(favorite), media_id))

    def set_thumbnail(self, media_id: int, thumbnail: str | None) -> None:
        self.execute("UPDATE media SET thumbnail = ? WHERE id = ?", (thumbnail, media_id))

    def remove_media(self, media_id: int) -> None:
        """Remove the library entry only. The media file itself is not touched."""
        self.execute("DELETE FROM media WHERE id = ?", (media_id,))

    # -- playlists -----------------------------------------------------------

    def create_playlist(self, name: str, mode: str = "sequential", interval_seconds: int = 1800) -> int:
        return self.execute("INSERT INTO playlists (name, mode, interval_seconds) VALUES (?, ?, ?)",
                            (name, mode, interval_seconds)).lastrowid

    _PLAYLIST_SELECT = ("SELECT p.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id = p.id) AS item_count "
                        "FROM playlists p")

    def list_playlists(self) -> list[Playlist]:
        return [Playlist(**dict(r)) for r in self.query(self._PLAYLIST_SELECT + " ORDER BY p.name COLLATE NOCASE")]

    def get_playlist(self, playlist_id: int) -> Playlist | None:
        rows = self.query(self._PLAYLIST_SELECT + " WHERE p.id = ?", (playlist_id,))
        return Playlist(**dict(rows[0])) if rows else None

    def update_playlist(self, playlist_id: int, **fields) -> None:
        allowed = {k: v for k, v in fields.items() if k in ("name", "mode", "interval_seconds")}
        if allowed:
            assignments = ", ".join(f"{k} = ?" for k in allowed)
            self.execute(f"UPDATE playlists SET {assignments} WHERE id = ?", (*allowed.values(), playlist_id))

    def delete_playlist(self, playlist_id: int) -> None:
        self.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,))

    def playlist_items(self, playlist_id: int) -> list[MediaItem]:
        rows = self.query("SELECT m.* FROM playlist_items i JOIN media m ON m.id = i.media_id "
                          "WHERE i.playlist_id = ? ORDER BY i.position", (playlist_id,))
        return [MediaItem.from_row(r) for r in rows]

    def add_to_playlist(self, playlist_id: int, media_ids: list[int]) -> None:
        """Append items; items already in the playlist are skipped."""
        with self._lock, self._conn:
            position = self._conn.execute("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items "
                                          "WHERE playlist_id = ?", (playlist_id,)).fetchone()[0]
            for media_id in media_ids:
                cursor = self._conn.execute("INSERT OR IGNORE INTO playlist_items (playlist_id, media_id, position) "
                                            "VALUES (?, ?, ?)", (playlist_id, media_id, position))
                position += cursor.rowcount

    def remove_from_playlist(self, playlist_id: int, media_id: int) -> None:
        self.execute("DELETE FROM playlist_items WHERE playlist_id = ? AND media_id = ?", (playlist_id, media_id))

    def move_in_playlist(self, playlist_id: int, media_id: int, delta: int) -> None:
        """Move an item up (delta=-1) or down (delta=+1) by swapping it with its neighbour."""
        ids = [m.id for m in self.playlist_items(playlist_id)]
        if media_id not in ids:
            return
        old = ids.index(media_id)
        new = max(0, min(len(ids) - 1, old + delta))
        ids.insert(new, ids.pop(old))
        with self._lock, self._conn:
            self._conn.executemany("UPDATE playlist_items SET position = ? WHERE playlist_id = ? AND media_id = ?",
                                   [(pos, playlist_id, mid) for pos, mid in enumerate(ids)])

    # -- schedule --------------------------------------------------------------

    def add_rule(self, time: str, media_id: int | None = None, playlist_id: int | None = None) -> int:
        return self.execute("INSERT INTO schedule_rules (time, media_id, playlist_id) VALUES (?, ?, ?)",
                            (time, media_id, playlist_id)).lastrowid

    def update_rule(self, rule_id: int, time: str, media_id: int | None, playlist_id: int | None) -> None:
        self.execute("UPDATE schedule_rules SET time = ?, media_id = ?, playlist_id = ? WHERE id = ?",
                     (time, media_id, playlist_id, rule_id))

    def set_rule_enabled(self, rule_id: int, enabled: bool) -> None:
        self.execute("UPDATE schedule_rules SET enabled = ? WHERE id = ?", (int(enabled), rule_id))

    def delete_rule(self, rule_id: int) -> None:
        self.execute("DELETE FROM schedule_rules WHERE id = ?", (rule_id,))

    def list_rules(self) -> list[ScheduleRule]:
        rows = self.query(
            "SELECT r.*, COALESCE(m.filename, p.name, '') AS target_name FROM schedule_rules r "
            "LEFT JOIN media m ON m.id = r.media_id LEFT JOIN playlists p ON p.id = r.playlist_id "
            "ORDER BY r.time, r.id")
        return [ScheduleRule(**{**dict(r), "enabled": bool(r["enabled"])}) for r in rows]

    def media_stats(self) -> dict[str, int]:
        row = self.query(
            "SELECT COUNT(*) AS total,"
            " COALESCE(SUM(media_type = 'image'), 0) AS images,"
            " COALESCE(SUM(media_type = 'video'), 0) AS videos,"
            " COALESCE(SUM(favorite), 0) AS favorites FROM media"
        )[0]
        return dict(row)
