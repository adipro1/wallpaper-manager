import os

import pytest

from wallpaper_manager import media
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import MIGRATIONS, Database
from wallpaper_manager.playlists import MIN_INTERVAL_SECONDS, PlaylistPlayer
from wallpaper_manager.wallpaper import WallpaperService

from .samples import make_image, make_video
from .test_wallpaper import FakeLivePlayer, FakeWindows


class Clock:
    def __init__(self):
        self.now = 1_000_000.0

    def __call__(self):
        return self.now


@pytest.fixture
def env(tmp_path, monkeypatch):
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    fake = FakeWindows(monkeypatch, None)
    service = WallpaperService(db, paths, FakeLivePlayer())
    clock = Clock()
    files = [make_image(tmp_path / f"img{i}.png") for i in range(3)] + [make_video(tmp_path / "clip.mp4")]
    ids = media.import_files(db, [str(f) for f in files], paths.thumb_dir).added
    yield db, PlaylistPlayer(db, service, clock), service, fake, clock, ids
    db.close()


def test_playlist_crud_and_ordering(env):
    db, player, *_rest, ids = env
    pid = db.create_playlist("Mix", "random", 600)
    db.add_to_playlist(pid, ids)
    db.add_to_playlist(pid, ids[:2])  # duplicates ignored
    playlist = db.get_playlist(pid)
    assert (playlist.name, playlist.mode, playlist.item_count) == ("Mix", "random", 4)
    assert playlist.interval_text == "every 10 min"

    db.move_in_playlist(pid, ids[0], +1)
    assert [m.id for m in db.playlist_items(pid)] == [ids[1], ids[0], ids[2], ids[3]]
    db.move_in_playlist(pid, ids[3], -5)  # clamps to the top
    assert [m.id for m in db.playlist_items(pid)][0] == ids[3]

    db.remove_from_playlist(pid, ids[1])
    db.update_playlist(pid, name="Renamed", interval_seconds=7200)
    assert db.get_playlist(pid).interval_text == "every 2 h"
    assert db.get_playlist(pid).item_count == 3

    db.remove_media(ids[2])  # removing from the library removes it from playlists
    assert ids[2] not in [m.id for m in db.playlist_items(pid)]
    db.delete_playlist(pid)
    assert db.list_playlists() == []


def test_schema_upgrade_from_version_1(tmp_path):
    import sqlite3
    conn = sqlite3.connect(tmp_path / "old.db")
    conn.executescript(MIGRATIONS[0] + "PRAGMA user_version = 1;")
    conn.close()
    db = Database(tmp_path / "old.db")
    assert db.schema_version == len(MIGRATIONS)
    assert db.create_playlist("works") == 1
    db.close()


def test_sequential_playback_with_interval(env):
    db, player, service, fake, clock, ids = env
    pid = db.create_playlist("Seq", "sequential", 300)
    db.add_to_playlist(pid, ids)  # 3 images + 1 video

    first = player.start(pid)
    assert first.id == ids[0] and service.state.media_id == ids[0]
    assert not player.is_due()
    assert player.seconds_until_change() == 300

    shown = []
    for _ in range(4):
        clock.now += 300
        assert player.is_due()
        shown.append(player.advance().id)
    assert shown == [ids[1], ids[2], ids[3], ids[0]]  # wraps around
    assert service.live.path is None  # video was replaced by the image again


def test_video_items_become_live_wallpapers(env):
    db, player, service, fake, clock, ids = env
    pid = db.create_playlist("Vid")
    db.add_to_playlist(pid, [ids[3]])
    player.start(pid)
    assert service.live.path.endswith("clip.mp4") and service.state.kind == "video"


def test_random_mode_shows_everything_before_repeating(env):
    db, player, *_rest, ids = env
    pid = db.create_playlist("Rnd", "random", 60)
    db.add_to_playlist(pid, ids)
    seen = [player.start(pid).id] + [player.advance().id for _ in range(3)]
    assert sorted(seen) == sorted(ids)
    second_round_first = player.advance().id
    assert second_round_first != seen[-1]  # no immediate repeat across rounds


def test_state_survives_restart(env):
    db, player, service, fake, clock, ids = env
    pid = db.create_playlist("Seq", "sequential", 300)
    db.add_to_playlist(pid, ids[:3])
    player.start(pid)
    player.advance()
    restarted = PlaylistPlayer(db, service, clock)
    assert restarted.active_playlist().id == pid
    clock.now += 301
    assert restarted.is_due() and restarted.advance().id == ids[2]


def test_missing_files_are_skipped_and_empty_playlist_rejected(env):
    db, player, service, fake, clock, ids = env
    empty = db.create_playlist("Empty")
    with pytest.raises(ValueError):
        player.start(empty)

    pid = db.create_playlist("Seq", "sequential", 300)
    db.add_to_playlist(pid, ids[:3])
    os.remove(db.get_media(ids[1]).path)
    player.start(pid)
    clock.now += 300
    assert player.advance().id == ids[2]  # ids[1] skipped


def test_minimum_interval_and_stop(env):
    db, player, service, fake, clock, ids = env
    pid = db.create_playlist("Fast", "sequential", 5)
    db.add_to_playlist(pid, ids[:2])
    player.start(pid)
    assert player.seconds_until_change() == MIN_INTERVAL_SECONDS
    player.stop()
    assert player.state is None and not player.is_due()


def test_deleting_running_playlist_stops_it(env):
    db, player, *_rest, ids = env
    pid = db.create_playlist("Gone")
    db.add_to_playlist(pid, ids[:1])
    player.start(pid)
    db.delete_playlist(pid)
    assert player.active_playlist() is None and player.state is None
