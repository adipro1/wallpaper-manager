"""SQLite storage for settings and library metadata.

Only metadata is stored here - the media files themselves stay where they are.
The schema is versioned with PRAGMA user_version; to change it, append a new
entry to MIGRATIONS (never edit an old one).
"""

import json
import logging
import sqlite3
import threading
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
]

DEFAULT_SETTINGS: dict[str, Any] = {
    "appearance_mode": "dark",
}


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

    def media_stats(self) -> dict[str, int]:
        row = self.query(
            "SELECT COUNT(*) AS total,"
            " COALESCE(SUM(media_type = 'image'), 0) AS images,"
            " COALESCE(SUM(media_type = 'video'), 0) AS videos,"
            " COALESCE(SUM(favorite), 0) AS favorites FROM media"
        )[0]
        return dict(row)
