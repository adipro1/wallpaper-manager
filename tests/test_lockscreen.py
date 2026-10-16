import io

import pytest
from PIL import Image

from wallpaper_manager import lockscreen, media
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import Database

from .samples import make_image, make_video


class FakeLockScreen:
    """Replaces the WinRT calls and the Spotlight registry value."""

    def __init__(self, monkeypatch, current: bytes, spotlight: bool):
        self.current, self.spotlight, self.set_paths = current, spotlight, []
        monkeypatch.setattr(lockscreen, "_set_lock_screen_file", self._set)
        monkeypatch.setattr(lockscreen, "_read_lock_screen", lambda: self.current)
        monkeypatch.setattr(lockscreen, "spotlight_enabled", lambda: self.spotlight)
        monkeypatch.setattr(lockscreen, "_set_spotlight", lambda on: setattr(self, "spotlight", on))

    def _set(self, path):
        self.set_paths.append(path)
        self.current = open(path, "rb").read()
        self.spotlight = False  # Windows switches to "Picture" mode


def _jpeg_bytes(color):
    buf = io.BytesIO()
    Image.new("RGB", (64, 36), color).save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture
def env(tmp_path, monkeypatch):
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    fake = FakeLockScreen(monkeypatch, _jpeg_bytes((10, 20, 200)), spotlight=True)
    yield db, paths, lockscreen.LockScreenService(db, paths), fake, tmp_path
    db.close()


def _import(db, paths, path):
    return db.get_media(media.import_files(db, [str(path)], paths.thumb_dir).added[0])


def test_set_jpg_directly(env):
    db, paths, service, fake, tmp = env
    item = _import(db, paths, make_image(tmp / "a.jpg"))
    service.set_image(item)
    assert fake.set_paths == [item.path]
    assert service.state == {"media_id": item.id, "path": item.path, "timestamp": None}
    assert service.current_item().id == item.id


def test_webp_is_converted(env):
    db, paths, service, fake, tmp = env
    service.set_image(_import(db, paths, make_image(tmp / "a.webp")))
    assert fake.set_paths[-1].endswith("lockscreen.png")
    assert Image.open(io.BytesIO(fake.current)).format == "PNG"


def test_video_frame_extracted_at_timestamp(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "v.mp4"))  # gets brighter over time
    early = service.set_video_frame(video, 0.0)
    early_red = Image.open(early).getpixel((5, 5))[0]
    late = service.set_video_frame(video, 1.5)
    assert Image.open(late).getpixel((5, 5))[0] > early_red + 100
    assert service.state["timestamp"] == 1.5
    assert list((paths.data_dir / "lockscreen").glob("frame-*.jpg")) == [__import__("pathlib").Path(late)]


def test_backup_and_restore_including_spotlight(env):
    db, paths, service, fake, tmp = env
    original = fake.current
    service.set_image(_import(db, paths, make_image(tmp / "a.png")))
    assert fake.spotlight is False
    backup = db.get_setting("original_lock_screen")
    assert backup["spotlight"] is True and backup["path"].endswith("original_lockscreen.jpg")

    service.restore_original()
    assert fake.current == original and fake.spotlight is True
    assert service.state == {}


def test_rejects_wrong_types(env):
    db, paths, service, fake, tmp = env
    video = _import(db, paths, make_video(tmp / "v.mp4"))
    image = _import(db, paths, make_image(tmp / "a.png"))
    with pytest.raises(ValueError):
        service.set_image(video)
    with pytest.raises(ValueError):
        service.set_video_frame(image, 1.0)
    assert fake.set_paths == []


def test_format_timestamp():
    assert lockscreen.format_timestamp(0) == "0:00.00"
    assert lockscreen.format_timestamp(75.5) == "1:15.50"
