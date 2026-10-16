"""Windows lock screen image.

Uses the official WinRT API (Windows.System.UserProfile.LockScreen) - no admin
rights, registry policies or other hacks. Windows only supports still images on
the lock screen, so for videos a single frame is extracted and used instead.

Windows copies the image into its own storage when it is set, but we keep our
copies (converted images, extracted frames) in the data folder anyway.
"""

import asyncio
import io
import logging
import os
import winreg
from pathlib import Path

from PIL import Image

from . import imaging, media
from .config import AppPaths
from .database import Database, MediaItem

log = logging.getLogger(__name__)

NATIVE_FORMATS = {".jpg", ".jpeg", ".png"}
_SPOTLIGHT_KEY = r"Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager"


# --- thin wrappers around the WinRT calls (replaced by fakes in tests) ---------

def _set_lock_screen_file(path: str) -> None:
    from winrt.windows.storage import StorageFile
    from winrt.windows.system.userprofile import LockScreen

    async def run():
        file = await StorageFile.get_file_from_path_async(path)
        await LockScreen.set_image_file_async(file)

    asyncio.run(run())


def _read_lock_screen() -> bytes:
    """Bytes of the image currently shown on the lock screen (also for Windows Spotlight)."""
    from winrt.windows.storage.streams import DataReader
    from winrt.windows.system.userprofile import LockScreen

    async def run():
        stream = LockScreen.get_image_stream()
        reader = DataReader(stream.get_input_stream_at(0))
        await reader.load_async(stream.size)
        data = bytearray(stream.size)
        reader.read_bytes(data)
        return bytes(data)

    return asyncio.run(run())


def spotlight_enabled() -> bool:
    """True if the lock screen shows Windows Spotlight pictures."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _SPOTLIGHT_KEY) as key:
            return bool(winreg.QueryValueEx(key, "RotatingLockScreenEnabled")[0])
    except OSError:
        return False


def _set_spotlight(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _SPOTLIGHT_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, "RotatingLockScreenEnabled", 0, winreg.REG_DWORD, int(enabled))


def format_timestamp(seconds: float) -> str:
    minutes, secs = divmod(max(seconds, 0.0), 60)
    return f"{int(minutes)}:{secs:05.2f}"


class LockScreenService:
    """All methods that call Windows block briefly; call them from a worker thread."""

    def __init__(self, db: Database, paths: AppPaths):
        self.db = db
        self.paths = paths
        self.folder = paths.data_dir / "lockscreen"

    @property
    def state(self) -> dict:
        return self.db.get_setting("lock_screen") or {}

    def current_item(self) -> MediaItem | None:
        media_id = self.state.get("media_id")
        return self.db.get_media(media_id) if media_id else None

    def current_image(self) -> Image.Image:
        return Image.open(io.BytesIO(_read_lock_screen()))

    # -- setting -------------------------------------------------------------

    def set_image(self, item: MediaItem) -> None:
        if item.is_video:
            raise ValueError("Pick a frame with set_video_frame for videos")
        if not os.path.exists(item.path):
            raise FileNotFoundError(f"File not found: {item.path}")
        self.backup_original()
        path = item.path
        if Path(path).suffix.lower() not in NATIVE_FORMATS:  # WEBP / GIF -> PNG
            self.folder.mkdir(parents=True, exist_ok=True)
            path = str(self.folder / "lockscreen.png")
            imaging.open_rgb(item.path).save(path, "PNG")
        self._set(path, {"media_id": item.id, "path": item.path, "timestamp": None})

    def set_video_frame(self, item: MediaItem, seconds: float) -> str:
        """Extract the frame at `seconds` and use it as the lock screen. Returns the frame file."""
        if not item.is_video:
            raise ValueError("Not a video")
        if not os.path.exists(item.path):
            raise FileNotFoundError(f"File not found: {item.path}")
        self.backup_original()
        frame = media.read_video_frame(item.path, seconds)
        self.folder.mkdir(parents=True, exist_ok=True)
        for old in self.folder.glob("frame-*.jpg"):  # keep only the current frame
            old.unlink(missing_ok=True)
        path = self.folder / f"frame-{item.id}-{int(seconds * 1000)}.jpg"
        frame.save(path, "JPEG", quality=95)
        self._set(str(path), {"media_id": item.id, "path": item.path, "timestamp": seconds})
        return str(path)

    def _set(self, file_path: str, state: dict) -> None:
        _set_lock_screen_file(os.path.normpath(os.path.abspath(file_path)))
        self.db.set_setting("lock_screen", state)
        log.info("Lock screen set to %s", file_path)

    # -- original -----------------------------------------------------------------

    def backup_original(self) -> None:
        """Before the first change, save the current lock screen picture and whether
        Windows Spotlight was on."""
        if self.db.get_setting("original_lock_screen"):
            return
        backup = None
        try:
            data = _read_lock_screen()
            with Image.open(io.BytesIO(data)) as img:
                ext = ".png" if img.format == "PNG" else ".jpg"
            backup = self.paths.data_dir / "backup" / f"original_lockscreen{ext}"
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_bytes(data)
        except Exception:  # noqa: BLE001 - a missing backup must not block setting a new image
            log.warning("Could not back up the current lock screen", exc_info=True)
            backup = None
        self.db.set_setting("original_lock_screen", {
            "path": str(backup) if backup else None,
            "spotlight": spotlight_enabled(),
        })
        log.info("Backed up original lock screen to %s", backup)

    def has_original(self) -> bool:
        return bool(self.db.get_setting("original_lock_screen"))

    def restore_original(self) -> None:
        original = self.db.get_setting("original_lock_screen")
        if not original:
            return
        path = original.get("path")
        if path and os.path.exists(path):
            _set_lock_screen_file(path)  # shows the old picture even if Spotlight doesn't come back
        if original.get("spotlight"):
            # Best effort: Windows Settings may still need Spotlight to be picked again.
            _set_spotlight(True)
        self.db.set_setting("lock_screen", {})
        log.info("Restored the original lock screen")
