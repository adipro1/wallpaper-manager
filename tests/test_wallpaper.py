import pytest
from PIL import Image

from wallpaper_manager import media, winapi
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import Database
from wallpaper_manager.wallpaper import WallpaperService

from .samples import make_image, make_video


class FakeWindows:
    """Stands in for the real Windows calls so tests never change the actual wallpaper."""

    def __init__(self, monkeypatch, current: str | None, style: str = "stretch"):
        self.wallpaper, self.style, self.calls = current, style, []
        self.transcoded = None
        monkeypatch.setattr(winapi, "get_desktop_wallpaper", lambda: self.wallpaper)
        monkeypatch.setattr(winapi, "get_transcoded_wallpaper", lambda: self.transcoded)
        monkeypatch.setattr(winapi, "get_wallpaper_style", lambda: self.style)
        monkeypatch.setattr(winapi, "set_wallpaper_style", self._set_style)
        monkeypatch.setattr(winapi, "set_desktop_wallpaper", self._set_wallpaper)

    def _set_style(self, style):
        self.style = style

    def _set_wallpaper(self, path, persist=True):
        self.wallpaper = path
        self.calls.append(path)


class FakeLivePlayer:
    """Records what the service asks the video engine to do."""

    def __init__(self):
        self.path, self.fill, self.muted, self.paused, self.healthy = None, None, None, False, True

    active = property(lambda self: self.path is not None)

    def play(self, path, fill=True, muted=True):
        self.path, self.fill, self.muted, self.paused = path, fill, muted, False

    def stop(self):
        self.path = None

    def pause(self):
        self.paused = True

    def resume(self):
        self.paused = False

    def set_fill(self, fill):
        self.fill = fill

    def set_muted(self, muted):
        self.muted = muted

    def is_healthy(self):
        return self.healthy

    def fit_to_screen(self):
        pass

    def competing_apps(self):
        return []


@pytest.fixture
def env(tmp_path, monkeypatch):
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    original = make_image(tmp_path / "TranscodedWallpaper.jpg")
    original_no_ext = tmp_path / "TranscodedWallpaper"
    original.rename(original_no_ext)  # Windows' copy has no file extension
    fake = FakeWindows(monkeypatch, str(original_no_ext))
    yield db, paths, WallpaperService(db, paths, FakeLivePlayer()), fake, tmp_path
    db.close()


def _import(db, paths, path):
    return db.get_media(media.import_files(db, [str(path)], paths.thumb_dir).added[0])


def test_set_jpg_uses_original_file_and_saves_state(env):
    db, paths, service, fake, tmp = env
    item = _import(db, paths, make_image(tmp / "photo.jpg"))
    service.set_image(item, "fit")
    assert fake.wallpaper == item.path and fake.style == "fit"
    state = service.state
    assert (state.kind, state.media_id, state.style) == ("image", item.id, "fit")
    assert service.current_item().id == item.id
    assert db.get_setting("image_style") == "fit"


@pytest.mark.parametrize("name", ["anim.gif", "photo.webp"])
def test_gif_and_webp_are_converted_to_png(env, name):
    db, paths, service, fake, tmp = env
    item = _import(db, paths, make_image(tmp / name))
    service.set_image(item)
    assert fake.wallpaper.endswith(".png") and fake.wallpaper != item.path
    with Image.open(fake.wallpaper) as img:
        assert img.format == "PNG" and img.size == (320, 180)


def test_original_is_backed_up_once_and_restored(env):
    db, paths, service, fake, tmp = env
    first = _import(db, paths, make_image(tmp / "a.png"))
    second = _import(db, paths, make_image(tmp / "b.png"))
    service.set_image(first)
    service.set_image(second)

    original = db.get_setting("original_wallpaper")
    assert original["style"] == "stretch"
    assert original["path"].endswith("original_wallpaper.jpg")  # format detected without an extension

    service.restore_original()
    assert fake.wallpaper == original["path"] and fake.style == "stretch"
    assert service.state.kind is None


def test_change_style_reapplies_current_image(env):
    db, paths, service, fake, tmp = env
    item = _import(db, paths, make_image(tmp / "a.png"))
    service.set_image(item, "fill")
    service.set_image_style("center")
    assert fake.style == "center" and fake.calls[-1] == item.path


def test_set_image_rejects_videos_and_missing_files(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "v.mp4"))
    with pytest.raises(ValueError):
        service.set_image(video)
    gone = _import(db, paths, make_image(tmp / "gone.png"))
    (tmp / "gone.png").unlink()
    with pytest.raises(FileNotFoundError):
        service.set_image(gone)
    assert fake.calls == []


def test_video_plays_muted_and_saves_state(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "clip.mp4"))
    service.set_video(video)
    assert service.live.path == video.path and service.live.muted is True and service.live.fill is True
    assert (service.state.kind, service.state.media_id) == ("video", video.id)
    assert db.get_setting("original_wallpaper") is not None  # backed up before the first change


def test_image_after_video_stops_the_video(env):
    db, paths, service, fake, tmp = env
    service.set_video(_import(db, paths, make_video(tmp / "clip.mp4")))
    image = _import(db, paths, make_image(tmp / "a.png"))
    service.set_image(image)
    assert not service.live.active and service.state.kind == "image"


def test_stop_video_returns_to_previous_image(env):
    db, paths, service, fake, tmp = env
    image = _import(db, paths, make_image(tmp / "a.png"))
    service.set_image(image, "center")
    service.set_video(_import(db, paths, make_video(tmp / "clip.mp4")))
    service.stop_video()
    assert not service.live.active
    assert fake.calls[-1] == image.path  # desktop repainted with the static picture
    state = service.state
    assert (state.kind, state.media_id, state.style) == ("image", image.id, "center")


def test_pause_resume_sound_and_fit(env):
    db, paths, service, fake, tmp = env
    service.set_video(_import(db, paths, make_video(tmp / "clip.mp4")))
    service.pause_video()
    assert service.video_paused
    service.resume_video()
    assert not service.video_paused
    service.set_muted(False)
    service.set_video_style("fit")
    assert service.live.muted is False and service.live.fill is False and service.state.style == "fit"


def test_shutdown_keeps_choice_and_startup_restores_it(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "clip.mp4"))
    service.set_video(video, "fit")
    service.shutdown()
    assert not service.live.active and service.state.kind == "video"

    restarted = WallpaperService(db, paths, FakeLivePlayer())
    assert restarted.restore_on_startup() is True
    assert restarted.live.path == video.path and restarted.live.fill is False

    db.set_setting("resume_live_wallpaper", False)
    assert WallpaperService(db, paths, FakeLivePlayer()).restore_on_startup() is False


def test_check_live_recreates_lost_window(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "clip.mp4"))
    service.set_video(video)
    service.live.healthy = False  # e.g. Explorer was restarted
    service.check_live()
    assert service.live.path == video.path


def test_backup_falls_back_to_windows_copy_when_source_is_gone(env):
    db, paths, service, fake, tmp = env
    transcoded = make_image(tmp / "cached.png")
    fake.transcoded = str(transcoded)
    fake.wallpaper = str(tmp / "deleted-since.jpg")  # Windows still reports a path that no longer exists
    service.set_image(_import(db, paths, make_image(tmp / "a.png")))
    original = db.get_setting("original_wallpaper")
    assert original["path"] and original["path"].endswith("original_wallpaper.png")

    service.restore_original()
    assert fake.wallpaper == original["path"]  # a picture, not a solid colour


def test_backup_when_current_wallpaper_is_already_the_backup_file(env):
    db, paths, service, fake, tmp = env
    backup_dir = paths.data_dir / "backup"
    backup_dir.mkdir()
    existing = make_image(backup_dir / "original_wallpaper.png")
    fake.wallpaper = str(existing)
    service.backup_original()
    assert db.get_setting("original_wallpaper")["path"] == str(existing)
    assert existing.exists()


def test_auto_pause_and_resume(env):
    db, paths, service, fake, tmp = env
    service.set_video(_import(db, paths, make_video(tmp / "clip.mp4")))
    service.update_auto_pause("fullscreen")
    assert service.video_paused and service.auto_pause_reason == "fullscreen"
    service.update_auto_pause("fullscreen")  # still fullscreen: stays paused
    assert service.video_paused
    service.update_auto_pause(None)  # game closed
    assert not service.video_paused and service.auto_pause_reason is None


def test_auto_pause_never_overrides_the_user(env):
    db, paths, service, fake, tmp = env
    service.set_video(_import(db, paths, make_video(tmp / "clip.mp4")))
    # user paused by hand: leaving fullscreen must not resume it
    service.pause_video()
    service.update_auto_pause("fullscreen")
    service.update_auto_pause(None)
    assert service.video_paused

    # user resumes while on battery: don't pause again for the same reason...
    service.resume_video()
    service.update_auto_pause("battery")
    service.resume_video()
    service.update_auto_pause("battery")
    assert not service.video_paused
    # ...but a new reason (or the same one later) pauses again
    service.update_auto_pause(None)
    service.update_auto_pause("battery")
    assert service.video_paused


def test_next_and_random_from_library(env):
    db, paths, service, fake, tmp = env
    ids = media.import_files(db, [str(make_image(tmp / f"{n}.png")) for n in "abc"], paths.thumb_dir).added
    newest_first = [m.id for m in db.list_media(sort="newest")]
    shown = [service.next_from_library().id for _ in range(4)]
    assert shown == newest_first + newest_first[:1]
    for _ in range(10):
        before = service.state.media_id
        assert service.random_from_library().id != before
    assert set(ids) == set(newest_first)
