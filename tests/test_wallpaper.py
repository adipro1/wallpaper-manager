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
        monkeypatch.setattr(winapi, "get_desktop_wallpaper", lambda: self.wallpaper)
        monkeypatch.setattr(winapi, "get_wallpaper_style", lambda: self.style)
        monkeypatch.setattr(winapi, "set_wallpaper_style", self._set_style)
        monkeypatch.setattr(winapi, "set_desktop_wallpaper", self._set_wallpaper)

    def _set_style(self, style):
        self.style = style

    def _set_wallpaper(self, path, persist=True):
        self.wallpaper = path
        self.calls.append(path)


@pytest.fixture
def env(tmp_path, monkeypatch):
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    original = make_image(tmp_path / "TranscodedWallpaper.jpg")
    original_no_ext = tmp_path / "TranscodedWallpaper"
    original.rename(original_no_ext)  # Windows' copy has no file extension
    fake = FakeWindows(monkeypatch, str(original_no_ext))
    yield db, paths, WallpaperService(db, paths), fake, tmp_path
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
