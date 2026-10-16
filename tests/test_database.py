from wallpaper_manager.database import MIGRATIONS, Database


def test_migrations_create_schema(tmp_path):
    db = Database(tmp_path / "test.db")
    assert db.schema_version == len(MIGRATIONS)
    tables = {r["name"] for r in db.query("SELECT name FROM sqlite_master WHERE type = 'table'")}
    assert {"settings", "media"} <= tables
    db.close()


def test_reopen_does_not_rerun_migrations(tmp_path):
    Database(tmp_path / "test.db").close()
    db = Database(tmp_path / "test.db")
    assert db.schema_version == len(MIGRATIONS)
    db.close()


def test_settings_roundtrip_and_defaults(tmp_path):
    db = Database(tmp_path / "test.db")
    assert db.get_setting("appearance_mode") == "dark"  # built-in default
    assert db.get_setting("missing", 42) == 42
    db.set_setting("appearance_mode", "light")
    db.set_setting("complex", {"a": [1, 2]})
    db.close()

    db = Database(tmp_path / "test.db")  # settings persist across restarts
    assert db.get_setting("appearance_mode") == "light"
    assert db.get_setting("complex") == {"a": [1, 2]}
    db.close()


def test_media_stats_empty(tmp_path):
    db = Database(tmp_path / "test.db")
    assert db.media_stats() == {"total": 0, "images": 0, "videos": 0, "favorites": 0}
    db.close()
