"""Applying wallpapers and remembering what is currently set.

The UI talks to WallpaperService only. It converts files Windows can't use
directly, backs up the user's original wallpaper before the first change,
switches between static images and live video wallpapers, and saves the
current choice in the settings table so it survives a restart.
"""

import logging
import os
import random
import shutil
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from . import imaging, winapi
from .config import AppPaths
from .database import Database, MediaItem
from .live_wallpaper import LivePlayer

log = logging.getLogger(__name__)

# Formats Windows reliably accepts as a wallpaper; anything else is converted to PNG.
NATIVE_WALLPAPER_FORMATS = {".jpg", ".jpeg", ".png", ".bmp"}
IMAGE_STYLES = ["fill", "fit", "stretch", "center", "tile"]
VIDEO_STYLES = ["fill", "fit"]


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
    def __init__(self, db: Database, paths: AppPaths, live: LivePlayer | None = None):
        self.db = db
        self.paths = paths
        self.live = live or LivePlayer()
        # Automatic pausing (fullscreen apps, battery): the reason we paused, if we did,
        # and a reason the user overrode by pressing Resume (we don't fight the user).
        self.auto_pause_reason: str | None = None
        self._overridden_reason: str | None = None

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
        """Make an image the desktop wallpaper (stops a live wallpaper). Safe to call from a worker thread."""
        if item.is_video:
            raise ValueError("Use set_video for videos")
        if not os.path.exists(item.path):
            raise FileNotFoundError(f"File not found: {item.path}")
        style = style or self.db.get_setting("image_style", "fill")
        self.backup_original()
        self._apply(self._prepare_image(item.path), style, item.id)
        self.live.stop()  # after applying the image, so the old picture never flashes up
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

    def _apply(self, file_path: str, style: str, media_id: int | None = None) -> None:
        winapi.set_wallpaper_style(style)
        winapi.set_desktop_wallpaper(file_path)
        # Remember the picture under any live wallpaper, to repaint it when the video stops.
        self.db.set_setting("static_wallpaper", {"file": file_path, "style": style, "media_id": media_id})

    def _repaint_static(self) -> None:
        """Re-apply the static picture so Explorer repaints the area the video covered."""
        static = self.db.get_setting("static_wallpaper") or {}
        original = self.db.get_setting("original_wallpaper") or {}
        for path in (static.get("file"), original.get("path")):
            if path and os.path.exists(path):
                try:
                    winapi.set_desktop_wallpaper(path, persist=False)
                except OSError:
                    log.warning("Could not repaint the desktop", exc_info=True)
                return

    # -- live video ------------------------------------------------------------

    @property
    def muted(self) -> bool:
        return bool(self.db.get_setting("video_muted", True))

    def set_video(self, item: MediaItem, style: str | None = None) -> None:
        """Play a video behind the desktop icons. Safe to call from a worker thread."""
        if not item.is_video:
            raise ValueError("Use set_image for images")
        if not os.path.exists(item.path):
            raise FileNotFoundError(f"File not found: {item.path}")
        style = style or self.db.get_setting("video_style", "fill")
        self.backup_original()
        self.live.play(item.path, fill=style == "fill", muted=self.muted)
        self.auto_pause_reason = None  # a new video starts playing; the next check re-pauses if needed
        self.db.set_setting("video_style", style)
        self._save_state(DesktopState("video", item.id, item.path, style))

    def set(self, item: MediaItem) -> None:
        """Set any library item (image or video) as the desktop wallpaper."""
        if item.is_video:
            self.set_video(item)
        else:
            self.set_image(item)

    def set_video_style(self, style: str) -> None:
        self.db.set_setting("video_style", style)
        self.live.set_fill(style == "fill")
        state = self.state
        if state.kind == "video":
            state.style = style
            self._save_state(state)

    def set_muted(self, muted: bool) -> None:
        self.db.set_setting("video_muted", muted)
        self.live.set_muted(muted)

    @property
    def video_active(self) -> bool:
        return self.live.active

    @property
    def video_paused(self) -> bool:
        return self.live.paused

    def pause_video(self) -> None:
        """Pause requested by the user: stays paused until the user resumes."""
        self.live.pause()
        self.auto_pause_reason = None

    def resume_video(self) -> None:
        """Resume requested by the user, even if an automatic reason still applies."""
        self.live.resume()
        if self.auto_pause_reason:
            self._overridden_reason = self.auto_pause_reason
            self.auto_pause_reason = None

    def update_auto_pause(self, reason: str | None) -> None:
        """Called every few seconds with why the video should be paused right now
        (e.g. 'fullscreen', 'battery') or None. Only resumes what it paused itself."""
        if not self.live.active:
            self.auto_pause_reason = self._overridden_reason = None
            return
        if reason is None:
            self._overridden_reason = None
            if self.auto_pause_reason:
                log.info("Resuming live wallpaper (%s ended)", self.auto_pause_reason)
                self.auto_pause_reason = None
                self.live.resume()
        elif (self.auto_pause_reason is None and not self.live.paused
              and reason != self._overridden_reason):
            log.info("Pausing live wallpaper automatically (%s)", reason)
            self.auto_pause_reason = reason
            self.live.pause()

    def stop_video(self) -> None:
        """Stop the live wallpaper and go back to the static picture underneath."""
        if not self.live.active and self.state.kind != "video":
            return
        self.live.stop()
        self.auto_pause_reason = None
        self._repaint_static()
        static = self.db.get_setting("static_wallpaper") or {}
        item = self.db.get_media(static["media_id"]) if static.get("media_id") else None
        self._save_state(DesktopState("image", item.id, item.path, static.get("style", "fill"))
                         if item else DesktopState())

    def next_from_library(self) -> MediaItem | None:
        """Show the library item after the current one (newest first, wrapping round)."""
        items = self.db.list_media(sort="newest")
        if not items:
            return None
        ids = [m.id for m in items]
        current = self.state.media_id
        item = items[(ids.index(current) + 1) % len(items)] if current in ids else items[0]
        self.set(item)
        return item

    def random_from_library(self) -> MediaItem | None:
        """Show a random library item other than the current one."""
        current = self.state.media_id
        items = [m for m in self.db.list_media() if m.id != current and os.path.exists(m.path)]
        if not items:
            return None
        item = random.choice(items)
        self.set(item)
        return item

    def restore_on_startup(self) -> bool:
        """Start the saved live wallpaper again after the app was restarted. Returns True if started."""
        state = self.state
        if state.kind != "video" or not self.db.get_setting("resume_live_wallpaper", True):
            return False
        item = self.current_item()
        if item is None or not os.path.exists(item.path):
            log.warning("Saved live wallpaper is no longer available: %s", state.path)
            return False
        self.live.play(item.path, fill=state.style == "fill", muted=self.muted)
        return True

    def check_live(self) -> None:
        """Called periodically: recover after an Explorer restart and follow resolution changes."""
        if not self.live.active:
            return
        if not self.live.is_healthy():
            log.warning("Live wallpaper window was lost (Explorer restarted?) - recreating it")
            path, state = self.live.path, self.state
            self.live.stop()
            if path:
                self.live.play(path, fill=state.style == "fill", muted=self.muted)
        else:
            self.live.fit_to_screen()

    def competing_apps(self) -> list[str]:
        return self.live.competing_apps() if self.live.active else []

    def shutdown(self) -> None:
        """App is closing: remove the video window but keep the saved choice for next start."""
        if self.live.active:
            self.live.stop()
            self._repaint_static()

    # -- original wallpaper ----------------------------------------------------

    def backup_original(self) -> None:
        """Before this app changes the wallpaper for the first time, keep a copy of the
        user's wallpaper so it can be restored later."""
        if self.db.get_setting("original_wallpaper"):
            return
        current = winapi.get_desktop_wallpaper()
        if not (current and os.path.exists(current)):
            # The file the wallpaper was set from may have been moved or deleted since;
            # Windows' own cached copy is what is actually on screen.
            current = winapi.get_transcoded_wallpaper()
        backup_path = None
        if current:
            backup_dir = self.paths.data_dir / "backup"
            backup_dir.mkdir(exist_ok=True)
            try:
                # Windows' own copy ("TranscodedWallpaper") has no extension, so detect the format.
                with Image.open(current) as img:
                    ext = {"JPEG": ".jpg", "PNG": ".png", "BMP": ".bmp"}.get(img.format or "", ".png")
                backup_path = backup_dir / f"original_wallpaper{ext}"
                if not (backup_path.exists() and os.path.samefile(current, backup_path)):
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
        self.live.stop()
        self._save_state(DesktopState())
        log.info("Restored the original wallpaper")
