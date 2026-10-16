"""Playlist playback: changes the desktop wallpaper at a fixed interval.

The running playlist is stored in the settings table, so it keeps going (and
remembers its position) after the app is restarted. The app calls `is_due()`
every few seconds and `advance()` on a worker thread when it returns True.

Random mode uses a "shuffle bag": every wallpaper is shown once, in random
order, before any of them repeats.
"""

import logging
import os
import random
import threading
import time
from typing import Callable

from .database import Database, MediaItem, Playlist
from .wallpaper import WallpaperService

log = logging.getLogger(__name__)

MIN_INTERVAL_SECONDS = 60


class PlaylistPlayer:
    def __init__(self, db: Database, wallpapers: WallpaperService, clock: Callable[[], float] = time.time):
        self.db = db
        self.wallpapers = wallpapers
        self.clock = clock
        self._lock = threading.Lock()  # advance() can be called from the timer and from the UI

    # -- state ------------------------------------------------------------------

    @property
    def state(self) -> dict | None:
        """{'id', 'index', 'next_change', 'bag'} of the running playlist, or None."""
        return self.db.get_setting("active_playlist") or None

    def _save(self, state: dict | None) -> None:
        self.db.set_setting("active_playlist", state or {})

    def _save_if_still_running(self, playlist_id: int, state: dict) -> None:
        """advance() runs on a worker thread; if the user stopped (or switched) the playlist
        meanwhile, saving would silently start it again."""
        current = self.state
        if current and current.get("id") == playlist_id:
            self._save(state)

    def active_playlist(self) -> Playlist | None:
        state = self.state
        playlist = self.db.get_playlist(state["id"]) if state else None
        if state and playlist is None:  # deleted while running
            self._save(None)
        return playlist

    def seconds_until_change(self) -> float | None:
        state = self.state
        return max(0.0, state["next_change"] - self.clock()) if state else None

    def current_media_id(self) -> int | None:
        state = self.state
        return state.get("media_id") if state else None

    # -- control ------------------------------------------------------------------

    def start(self, playlist_id: int) -> MediaItem:
        """Start a playlist: shows its first wallpaper right away."""
        playlist = self.db.get_playlist(playlist_id)
        if playlist is None:
            raise ValueError("Playlist not found")
        if not self.db.playlist_items(playlist_id):
            raise ValueError(f"“{playlist.name}” is empty - add some wallpapers first.")
        with self._lock:
            self._save({"id": playlist_id, "index": -1, "next_change": 0, "bag": [], "media_id": None})
        log.info("Started playlist %s", playlist.name)
        return self.advance()

    def stop(self) -> None:
        if self.state:
            log.info("Stopped playlist")
        self._save(None)

    def is_due(self) -> bool:
        state = self.state
        return bool(state) and self.clock() >= state["next_change"]

    def advance(self) -> MediaItem:
        """Show the next wallpaper of the running playlist and restart the timer."""
        with self._lock:
            state = self.state
            playlist = self.active_playlist()
            if not state or not playlist:
                raise ValueError("No playlist is running")
            items = self.db.playlist_items(playlist.id)
            if not items:
                self._save(None)
                raise ValueError(f"“{playlist.name}” is empty")

            # Try each item at most once, skipping files that have gone missing.
            last_error: Exception | None = None
            for _ in range(len(items)):
                item = self._pick_next(playlist, items, state)
                if not os.path.exists(item.path):
                    log.warning("Skipping missing playlist item %s", item.path)
                    continue
                try:
                    self.wallpapers.set(item)
                except Exception as exc:  # noqa: BLE001 - try the next one
                    log.warning("Could not show %s: %s", item.path, exc)
                    last_error = exc
                    continue
                state["media_id"] = item.id
                state["next_change"] = self.clock() + max(playlist.interval_seconds, MIN_INTERVAL_SECONDS)
                self._save_if_still_running(playlist.id, state)
                log.info("Playlist %s: showing %s", playlist.name, item.filename)
                return item

            # Nothing could be shown; don't keep retrying every few seconds.
            state["next_change"] = self.clock() + max(playlist.interval_seconds, MIN_INTERVAL_SECONDS)
            self._save_if_still_running(playlist.id, state)
            raise last_error or FileNotFoundError(f"None of the files in “{playlist.name}” can be found")

    @staticmethod
    def _pick_next(playlist: Playlist, items: list[MediaItem], state: dict) -> MediaItem:
        """Choose the next item and update `state` (index / shuffle bag) in place."""
        if playlist.mode == "random":
            ids = {m.id for m in items}
            bag = [i for i in state.get("bag", []) if i in ids]
            if not bag:  # refill, avoiding an immediate repeat of the current item
                bag = list(ids)
                random.shuffle(bag)
                if len(bag) > 1 and bag[0] == state.get("media_id"):
                    bag.append(bag.pop(0))
            next_id = bag.pop(0)
            state["bag"] = bag
            return next(m for m in items if m.id == next_id)
        state["index"] = (state.get("index", -1) + 1) % len(items)
        return items[state["index"]]
