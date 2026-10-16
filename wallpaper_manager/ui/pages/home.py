"""Home page: what's on the desktop now, quick actions, automation status and the library."""

import logging
import os
from datetime import datetime

import customtkinter as ctk

from ... import imaging, media, winapi
from .. import icons, theme
from ..widgets import BasePage, Card, PageHeader, button, section_label

log = logging.getLogger(__name__)

PREVIEW_SIZE = (480, 270)
RECENT_COUNT = 4
RECENT_SIZE = (160, 90)


def greeting() -> str:
    hour = datetime.now().hour
    if hour < 12:
        return "Good morning"
    if hour < 18:
        return "Good afternoon"
    return "Good evening"


class StatCard(Card):
    def __init__(self, master, label: str):
        super().__init__(master)
        self.value = ctk.CTkLabel(self, text="0", font=theme.font(26, "bold"), text_color=theme.TEXT)
        self.value.pack(anchor="w", padx=18, pady=(14, 0))
        ctk.CTkLabel(self, text=label, font=theme.font(12), text_color=theme.TEXT_MUTED).pack(
            anchor="w", padx=18, pady=(0, 14))


class HomePage(BasePage):
    title = "Home"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        self.header = PageHeader(self, greeting(), "Here's an overview of your wallpapers.")
        self.header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 20))
        button(self.header.actions, "Import wallpapers", self._import, icon="add", style="primary").pack()

        body = ctk.CTkScrollableFrame(self, fg_color="transparent")
        body.grid(row=1, column=0, sticky="nsew", padx=(20, 8), pady=(0, 16))
        body.grid_columnconfigure(0, weight=1)
        self.body = body

        # What's on the desktop right now + quick actions
        current = Card(body)
        current.grid(row=0, column=0, sticky="ew", padx=12, pady=(0, 16))
        current.grid_columnconfigure(1, weight=1)
        self.wallpaper_image = ctk.CTkLabel(current, text="", width=PREVIEW_SIZE[0], height=PREVIEW_SIZE[1])
        self.wallpaper_image.grid(row=0, column=0, rowspan=5, padx=16, pady=16)
        self.heading = section_label(current, "Current desktop wallpaper")
        self.heading.grid(row=0, column=1, sticky="sw", padx=(8, 16), pady=(16, 0))
        self.wallpaper_name = ctk.CTkLabel(current, text="", font=theme.font(13), text_color=theme.TEXT_MUTED,
                                           anchor="w", justify="left")
        self.wallpaper_name.grid(row=1, column=1, sticky="nw", padx=(8, 16), pady=(4, 0))

        actions = ctk.CTkFrame(current, fg_color="transparent")
        actions.grid(row=2, column=1, sticky="nw", padx=(8, 16), pady=(14, 0))
        button(actions, "Next", lambda: self._change("next"), icon="refresh", width=96, height=32).pack(side="left")
        button(actions, "Random", lambda: self._change("random"), icon="refresh", width=110, height=32).pack(
            side="left", padx=8)
        self.pause_btn = button(actions, "Pause", self._toggle_pause, icon="pause", width=110, height=32)

        self.automation = ctk.CTkLabel(current, text="", font=theme.font(12), text_color=theme.TEXT_MUTED,
                                       anchor="w", justify="left")
        self.automation.grid(row=3, column=1, sticky="nw", padx=(8, 16), pady=(14, 0))
        current.grid_rowconfigure(4, weight=1)

        # Library statistics
        section_label(body, "Library").grid(row=1, column=0, sticky="w", padx=14, pady=(4, 8))
        stats = ctk.CTkFrame(body, fg_color="transparent")
        stats.grid(row=2, column=0, sticky="ew", padx=6)
        self.stat_cards = {}
        for col, (key, label) in enumerate(
            [("total", "Wallpapers"), ("images", "Images"), ("videos", "Videos"), ("favorites", "Favorites")]
        ):
            stats.grid_columnconfigure(col, weight=1, uniform="stats")
            card = StatCard(stats, label)
            card.grid(row=0, column=col, sticky="ew", padx=6)
            self.stat_cards[key] = card

        # Recently added
        section_label(body, "Recently added").grid(row=3, column=0, sticky="w", padx=14, pady=(20, 8))
        self.recent = Card(body)
        self.recent.grid(row=4, column=0, sticky="ew", padx=12, pady=(0, 8))
        self.recent_tiles: list[ctk.CTkLabel] = []
        for col in range(RECENT_COUNT):
            self.recent.grid_columnconfigure(col, weight=1, uniform="recent")
            tile = ctk.CTkLabel(self.recent, text="", compound="top", font=theme.font(12),
                                text_color=theme.TEXT_MUTED, cursor="hand2")
            tile.grid(row=0, column=col, padx=8, pady=14)
            self.recent_tiles.append(tile)
        self.recent_empty = ctk.CTkLabel(self.recent, text="Nothing here yet - import some wallpapers to get started.",
                                         font=theme.font(13), text_color=theme.TEXT_MUTED)
        self._preview_key: tuple | None = None

    def on_show(self) -> None:
        self.header.title_label.configure(text=greeting())
        for key, value in self.app.db.media_stats().items():
            self.stat_cards[key].value.configure(text=str(value))
        self.refresh_current()
        self._show_recent()

    # -- current wallpaper -----------------------------------------------------------

    def refresh_current(self) -> None:
        service = self.app.wallpapers
        state = service.state
        item = service.current_item()
        live = state.kind == "video" and item is not None and service.video_active

        if live:
            paused = service.video_paused
            self.heading.configure(text="Live wallpaper  ·  " + ("Paused" if paused else "Playing"))
            self.pause_btn.configure(text="Resume" if paused else "Pause",
                                     image=icons.icon("play" if paused else "pause", 16, theme.TEXT))
            self.pause_btn.pack(side="left")
            self._set_preview(item.path, video=True)
            self.wallpaper_name.configure(
                text=f"{item.filename}\n{item.resolution_text}  ·  {item.duration_text}  ·  looping")
        else:
            self.heading.configure(text="Current desktop wallpaper")
            self.pause_btn.pack_forget()
            path = winapi.get_desktop_wallpaper()
            name = os.path.basename(path) if path else ""
            if item and state.kind == "image":
                name, path = item.filename, item.path
            elif name == "TranscodedWallpaper":  # Windows' own cached copy of the wallpaper
                name = "Set through Windows Settings"
            if self._set_preview(path):
                self.wallpaper_name.configure(text=f"{name}\n{imaging.shorten_middle(os.path.dirname(path), 38)}")
            else:
                self.wallpaper_name.configure(text="Windows is using a solid colour or slideshow.")
        self.automation.configure(text=self._automation_text())

    def _automation_text(self) -> str:
        lines = []
        playlist = self.app.playlists.active_playlist()
        if playlist:
            minutes = round((self.app.playlists.seconds_until_change() or 0) / 60)
            lines.append(f"Playlist “{playlist.name}” is running - next change in about {minutes} min")
        if self.app.scheduler.enabled:
            upcoming = self.app.scheduler.upcoming()
            if upcoming:
                lines.append(f"Schedule: next {upcoming[1].time} → {upcoming[1].target_name}")
        return "\n".join(lines)

    def _set_preview(self, path: str | None, video: bool = False) -> bool:
        if not path or not os.path.exists(path):
            self._preview_key = None
            self.wallpaper_image.configure(image=None, text="No wallpaper image", text_color=theme.TEXT_MUTED)
            return False
        key = (path, os.path.getmtime(path))
        if key == self._preview_key:
            return True  # unchanged since last time; skip decoding a large image again
        try:
            img = media.read_video_frame(path, 1.0) if video else imaging.open_rgb(path, draft_size=(960, 540))
            img = imaging.rounded(imaging.cover(img, PREVIEW_SIZE), 10)
        except Exception:  # noqa: BLE001 - a broken preview must not break the page
            log.warning("Could not preview the current wallpaper %s", path, exc_info=True)
            return False
        self._preview_key = key
        self._preview_image = ctk.CTkImage(img, img, size=PREVIEW_SIZE)
        self.wallpaper_image.configure(image=self._preview_image, text="")
        return True

    # -- actions ------------------------------------------------------------------------

    def _change(self, action: str) -> None:
        self.app.change_wallpaper(action)

    def _toggle_pause(self) -> None:
        if self.app.wallpapers.video_paused:
            self.app.wallpapers.resume_video()
        else:
            self.app.wallpapers.pause_video()
        self.refresh_current()

    def _import(self) -> None:
        self.app.show_page("library").import_files()

    def _show_recent(self) -> None:
        items = self.app.db.list_media(sort="newest", limit=RECENT_COUNT)
        if not items:
            for tile in self.recent_tiles:
                tile.grid_remove()
            self.recent_empty.grid(row=0, column=0, columnspan=RECENT_COUNT, pady=24)
            return
        self.recent_empty.grid_remove()
        for index, tile in enumerate(self.recent_tiles):
            if index >= len(items):
                tile.grid_remove()
                continue
            item = items[index]
            name = item.filename if len(item.filename) <= 20 else item.filename[:18] + "…"
            tile.configure(image=self.app.thumbs.get(item, RECENT_SIZE), text=name)
            tile.bind("<Button-1>", lambda _e, media_id=item.id: self._open_in_library(media_id))
            tile.grid()

    def _open_in_library(self, media_id: int) -> None:
        self.app.show_page("library").show_item(media_id)
