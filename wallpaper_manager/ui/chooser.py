"""A compact, searchable grid of library items to pick a wallpaper from."""

import random

import customtkinter as ctk
from customtkinter.windows.widgets.scaling import ScalingTracker

from ..database import MediaItem
from . import theme
from .widgets import segmented

TILE_IMAGE = (160, 90)
TILE_GAP = 10
MAX_TILES = 60


class Tile(ctk.CTkFrame):
    def __init__(self, master, on_click):
        super().__init__(master, fg_color="transparent", corner_radius=12, border_width=2,
                         border_color=theme.CARD_BG)
        self.item: MediaItem | None = None
        self.image = ctk.CTkLabel(self, text="", width=TILE_IMAGE[0], height=TILE_IMAGE[1])
        self.image.pack(padx=4, pady=(4, 2))
        self.name = ctk.CTkLabel(self, text="", font=theme.font(12), text_color=theme.TEXT_MUTED, height=18)
        self.name.pack(pady=(0, 4))
        for widget in (self, self.image, self.name):
            widget.bind("<Button-1>", lambda _e: self.item and on_click(self.item))
            widget.configure(cursor="hand2")

    def set_item(self, item: MediaItem, image: ctk.CTkImage, current: bool) -> None:
        self.item = item
        self.image.configure(image=image)
        self.name.configure(text=item.filename if len(item.filename) <= 22 else item.filename[:20] + "…")
        self.set_current(current)

    def set_current(self, current: bool) -> None:
        self.configure(border_color=theme.ACCENT if current else theme.CARD_BG)


class MediaChooser(ctk.CTkFrame):
    """Search box, type filter and a grid of tiles. Clicking a tile calls on_pick(item)."""

    def __init__(self, master, app, on_pick, allow_video: bool = True):
        super().__init__(master, fg_color="transparent")
        self.app = app
        self.on_pick = on_pick
        self.allow_video = allow_video
        self.current_id: int | None = None
        self.items: list[MediaItem] = []
        self.tiles: list[Tile] = []
        self.columns = 0
        self._search_job = None

        bar = ctk.CTkFrame(self, fg_color="transparent")
        bar.pack(fill="x", pady=(0, 8))
        self.search = ctk.CTkEntry(bar, placeholder_text="Search", width=200, height=32, corner_radius=8,
                                   font=theme.font(13), fg_color=theme.INPUT_BG, border_color=theme.CARD_BORDER,
                                   border_width=1)
        self.search.pack(side="left")
        self.search.bind("<KeyRelease>", lambda _e: self._schedule_refresh())
        self.type_filter = None
        if allow_video:
            self.type_filter = segmented(bar, ["All", "Images", "Videos"], lambda _v: self.refresh(), height=32)
            self.type_filter.set("All")
            self.type_filter.pack(side="left", padx=10)
        self.favorites = ctk.CTkSwitch(bar, text="Favorites", font=theme.font(13), progress_color=theme.ACCENT,
                                       command=self.refresh)
        self.favorites.pack(side="left", padx=(4, 0))
        self.count_label = ctk.CTkLabel(bar, text="", font=theme.font(12), text_color=theme.TEXT_MUTED)
        self.count_label.pack(side="right")

        # Measure the available width on a holder frame (see LibraryPage for why).
        self.holder = ctk.CTkFrame(self, fg_color="transparent")
        self.holder.pack(fill="both", expand=True)
        self.holder.bind("<Configure>", lambda _e: self._reflow())
        self.grid_area = ctk.CTkScrollableFrame(self.holder, fg_color="transparent")
        self.grid_area.pack(fill="both", expand=True)
        self.empty = ctk.CTkLabel(self.grid_area, text="", font=theme.font(13), text_color=theme.TEXT_MUTED)

    def filters(self) -> dict:
        media_type = "image"
        if self.type_filter is not None:
            media_type = {"All": None, "Images": "image", "Videos": "video"}[self.type_filter.get()]
        return dict(search=self.search.get(), media_type=media_type, favorites_only=bool(self.favorites.get()))

    def random_item(self, exclude_id: int | None = None) -> MediaItem | None:
        candidates = [m for m in self.app.db.list_media(**self.filters()) if m.id != exclude_id]
        return random.choice(candidates) if candidates else None

    def _schedule_refresh(self) -> None:
        if self._search_job:
            self.after_cancel(self._search_job)
        self._search_job = self.after(250, self.refresh)

    def refresh(self) -> None:
        filters = self.filters()
        total = self.app.db.count_media(**filters)
        self.items = self.app.db.list_media(**filters, sort="newest", limit=MAX_TILES)
        self.count_label.configure(
            text=f"Showing {len(self.items)} of {total}" if total > len(self.items) else f"{total} items")

        while len(self.tiles) < len(self.items):
            self.tiles.append(Tile(self.grid_area, self.on_pick))
        for tile, item in zip(self.tiles, self.items):
            tile.set_item(item, self.app.thumbs.get(item, TILE_IMAGE), item.id == self.current_id)
        for tile in self.tiles[len(self.items):]:
            tile.grid_remove()
            tile.item = None

        if self.items:
            self.empty.grid_remove()
        else:
            library_empty = self.app.db.count_media() == 0
            self.empty.configure(text="Your library is empty - import wallpapers on the Library page."
                                 if library_empty else "Nothing matches your search.")
            self.empty.grid(row=0, column=0, columnspan=max(self.columns, 1), pady=30)
        self.columns = 0
        self._reflow()

    def set_current(self, media_id: int | None) -> None:
        self.current_id = media_id
        for tile in self.tiles:
            if tile.item is not None:
                tile.set_current(tile.item.id == media_id)

    def _reflow(self) -> None:
        scale = ScalingTracker.get_widget_scaling(self)
        tile_px = (TILE_IMAGE[0] + 12 + TILE_GAP) * scale
        width = self.holder.winfo_width() - 20 * scale
        columns = max(1, int(width // tile_px)) if width > 1 else 4
        if columns == self.columns:
            return
        for col in range(max(columns, self.columns)):
            self.grid_area.grid_columnconfigure(col, weight=1 if col < columns else 0,
                                                uniform="tiles" if col < columns else "")
        self.columns = columns
        visible = [t for t in self.tiles if t.item is not None]
        for index, tile in enumerate(visible):
            tile.grid(row=index // columns, column=index % columns, padx=TILE_GAP // 2, pady=TILE_GAP // 2)
