from datetime import datetime

import pytest

from wallpaper_manager import media
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import Database
from wallpaper_manager.playlists import PlaylistPlayer
from wallpaper_manager.scheduler import Scheduler, parse_time
from wallpaper_manager.wallpaper import WallpaperService

from .samples import make_image, make_video
from .test_wallpaper import FakeLivePlayer, FakeWindows


class Clock:
    def __init__(self, when: str):
        self.now = datetime.fromisoformat(when)

    def __call__(self):
        return self.now

    def set(self, when: str):
        self.now = datetime.fromisoformat(when)


@pytest.fixture
def env(tmp_path, monkeypatch):
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    FakeWindows(monkeypatch, None)
    wallpapers = WallpaperService(db, paths, FakeLivePlayer())
    clock = Clock("2026-10-16 12:00")
    playlists = PlaylistPlayer(db, wallpapers, clock=lambda: clock.now.timestamp())
    files = [make_image(tmp_path / "morning.jpg"), make_video(tmp_path / "city.mp4"),
             make_image(tmp_path / "space.png")]
    ids = dict(zip(["morning", "city", "space"],
                   media.import_files(db, [str(f) for f in files], paths.thumb_dir).added))
    yield db, Scheduler(db, wallpapers, playlists, clock), wallpapers, playlists, clock, ids
    db.close()


@pytest.mark.parametrize("text,expected", [("7:05", "07:05"), ("0705", "07:05"), (" 23:59 ", "23:59"),
                                           ("18:00", "18:00")])
def test_parse_time(text, expected):
    assert parse_time(text) == expected


@pytest.mark.parametrize("text", ["24:00", "12:60", "noon", "", "7", "25:00ge", "ab:cd", "-1:30"])
def test_parse_time_rejects(text):
    with pytest.raises(ValueError):
        parse_time(text)


def _rules(db, ids):
    db.add_rule("07:00", media_id=ids["morning"])
    db.add_rule("18:00", media_id=ids["city"])
    db.add_rule("22:00", media_id=ids["space"])


def test_active_rule_wraps_round_midnight(env):
    db, scheduler, *_rest, clock, ids = env
    _rules(db, ids)
    for when, expected in [("2026-10-16 12:00", "morning.jpg"), ("2026-10-16 18:00", "city.mp4"),
                           ("2026-10-16 23:30", "space.png"), ("2026-10-17 03:00", "space.png"),
                           ("2026-10-17 07:00", "morning.jpg")]:
        clock.set(when)
        assert scheduler.active()[1].target_name == expected, when
    clock.set("2026-10-17 03:00")
    when, rule = scheduler.upcoming()
    assert rule.target_name == "morning.jpg" and when == datetime(2026, 10, 17, 7, 0)


def test_rules_fire_once_at_their_time(env):
    db, scheduler, wallpapers, playlists, clock, ids = env
    _rules(db, ids)
    scheduler.mark_current_applied()  # rules were just created: nothing changes yet
    assert not scheduler.is_due()

    clock.set("2026-10-16 17:59")
    assert scheduler.apply_due() is None
    clock.set("2026-10-16 18:00")
    assert scheduler.apply_due().target_name == "city.mp4"
    assert wallpapers.state.kind == "video" and wallpapers.live.path.endswith("city.mp4")
    assert scheduler.apply_due() is None  # only once

    clock.set("2026-10-16 22:01")
    assert scheduler.apply_due().target_name == "space.png"
    assert wallpapers.state.kind == "image" and not wallpapers.live.active


def test_catches_up_after_restart(env):
    db, scheduler, wallpapers, playlists, clock, ids = env
    _rules(db, ids)
    scheduler.mark_current_applied()  # at 12:00 → morning
    clock.set("2026-10-16 19:30")  # app was closed at 18:00
    restarted = Scheduler(db, wallpapers, playlists, clock)
    assert restarted.apply_due().target_name == "city.mp4"


def test_disabled_schedule_and_disabled_rules(env):
    db, scheduler, wallpapers, playlists, clock, ids = env
    _rules(db, ids)
    scheduler.mark_current_applied()
    scheduler.set_enabled(False)
    clock.set("2026-10-16 18:30")
    assert not scheduler.is_due()

    scheduler.set_enabled(True)  # turning it on doesn't apply the 18:00 rule immediately
    assert not scheduler.is_due()
    city_rule = [r for r in db.list_rules() if r.time == "18:00"][0]
    db.set_rule_enabled(city_rule.id, False)
    clock.set("2026-10-16 18:31")
    assert scheduler.active()[1].target_name == "morning.jpg"


def test_rule_can_start_a_playlist(env):
    db, scheduler, wallpapers, playlists, clock, ids = env
    pid = db.create_playlist("Night", "sequential", 600)
    db.add_to_playlist(pid, [ids["space"], ids["morning"]])
    db.add_rule("21:00", playlist_id=pid)
    scheduler.mark_current_applied()
    clock.set("2026-10-16 21:00")
    rule = scheduler.apply_due()
    assert rule.target_name == "Night" and playlists.active_playlist().id == pid
    assert wallpapers.state.media_id == ids["space"]


def test_rules_follow_library_and_playlist_deletes(env):
    db, scheduler, *_rest, ids = env
    pid = db.create_playlist("P")
    db.add_rule("08:00", media_id=ids["morning"])
    db.add_rule("09:00", playlist_id=pid)
    db.remove_media(ids["morning"])
    db.delete_playlist(pid)
    assert db.list_rules() == []
