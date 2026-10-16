"""Applying wallpapers and remembering what is currently set.

The UI talks to WallpaperService only; it takes care of converting files Windows
can't use directly, backing up the user's original wallpaper before the first
change, and saving the current choice in the settings table.
"""

import logging
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from . import imaging, winapi
from .config import AppPaths
from .database import Database, MediaItem

log = logging.getLogger(__name__)

# Formats Windows reliably accepts as a wallpaper; anything else is converted to PNG.
NATIVE_WALLPAPER_FORMATS = {".jpg", ".jpeg", ".png", ".bmp"}
IMAGE_STYLES = ["fill", "fit", "stretch", "center", "tile"]


@dataclass
class DesktopState:
    """What the desktop currently shows, as far as this app knows."""
    kind: str | None = None  # 'image', 'video' or None (not set by this app)
    media_id: int | None = None
    path: str | None = None
    style: str = "fill"

    @classmethod
    def from_setting(cls, value) -> "DesktopState":
        return cls(**value) if isinstance(value, dict) else cls()


class WallpaperService:
    def __init__(self, db: Database, paths: AppPaths):
        self.db = db
        self.paths = paths

    # -- state ---------------------------------------------------------------

    @property
    def state(self) -> DesktopState:
        return DesktopState.from_setting(self.db.get_setting("desktop_wallpaper"))

    def _save_state(self, state: DesktopState) -> None:
        self.db.set_setting("desktop_wallpaper", state.__dict__)

    def current_item(self) -> MediaItem | None:
        state = self.state
        return self.db.get_media(state.media_id) if state.media_id else None

    # -- static images -------------------------------------------------------

    def set_image(self, item: MediaItem, style: str | None = None) -> None:
        """Make an image the desktop wallpaper. Safe to call from a worker thread."""
        if item.is_video:
            raise ValueError("Use the live wallpaper for videos")
        if not os.path.exists(item.path):
            raise FileNotFoundError(f"File not found: {item.path}")
        style = style or self.db.get_setting("image_style", "fill")
        self.backup_original()
        self._apply(self._prepare_image(item.path), style)
        self.db.set_setting("image_style", style)
        self._save_state(DesktopState("image", item.id, item.path, style))
        log.info("Desktop wallpaper set to %s (%s)", item.path, style)

    def set_image_style(self, style: str) -> None:
        """Change how the current image is positioned (fill, fit, ...)."""
        state = self.state
        item = self.current_item()
        if state.kind == "image" and item:
            self.set_image(item, style)
        else:
            self.db.set_setting("image_style", style)

    def _prepare_image(self, path: str) -> str:
        """Return a file Windows can use: the original for JPG/PNG/BMP, otherwise a PNG copy
        (first frame for GIFs)."""
        if Path(path).suffix.lower() in NATIVE_WALLPAPER_FORMATS:
            return path
        out = self.paths.cache_dir / "desktop_wallpaper.png"
        imaging.open_rgb(path).save(out, "PNG")
        return str(out)

    @staticmethod
    def _apply(file_path: str, style: str) -> None:
        winapi.set_wallpaper_style(style)
        winapi.set_desktop_wallpaper(file_path)

    # -- original wallpaper ----------------------------------------------------

    def backup_original(self) -> None:
        """Before this app changes the wallpaper for the first time, keep a copy of the
        user's wallpaper so it can be restored later."""
        if self.db.get_setting("original_wallpaper"):
            return
        current = winapi.get_desktop_wallpaper()
        backup_path = None
        if current and os.path.exists(current):
            backup_dir = self.paths.data_dir / "backup"
            backup_dir.mkdir(exist_ok=True)
            try:
                # Windows' own copy ("TranscodedWallpaper") has no extension, so detect the format.
                with Image.open(current) as img:
                    ext = {"JPEG": ".jpg", "PNG": ".png", "BMP": ".bmp"}.get(img.format or "", ".png")
                backup_path = backup_dir / f"original_wallpaper{ext}"
                shutil.copyfile(current, backup_path)
            except OSError:
                log.warning("Could not back up the current wallpaper %s", current, exc_info=True)
                backup_path = None
        self.db.set_setting("original_wallpaper", {
            "path": str(backup_path) if backup_path else None,
            "style": winapi.get_wallpaper_style(),
        })
        log.info("Backed up original wallpaper to %s", backup_path)

    def has_original(self) -> bool:
        return bool(self.db.get_setting("original_wallpaper"))

    def restore_original(self) -> None:
        original = self.db.get_setting("original_wallpaper")
        if not original:
            return
        path = original.get("path")
        if path and os.path.exists(path):
            self._apply(path, original.get("style", "fill"))
        else:
            winapi.set_desktop_wallpaper("")  # the user had no picture (solid colour)
        self._save_state(DesktopState())
        log.info("Restored the original wallpaper")
