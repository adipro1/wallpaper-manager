"""Library page: import, browse, search, favourite and preview wallpapers."""

import logging
import os
import tkinter as tk
from datetime import datetime
from tkinter import filedialog, messagebox

import customtkinter as ctk
from customtkinter.windows.widgets.scaling import ScalingTracker

from ... import imaging, media, winapi
from ...database import MediaItem
from .. import icons, theme
from ..frame_picker import FramePicker
from ..preview import MediaPreview, PreviewWindow
from ..widgets import BasePage, Card, PageHeader, button, link_button, run_in_thread, segmented

log = logging.getLogger(__name__)

PAGE_SIZE = 40
CARD_IMAGE = (192, 108)  # 16:9
CARD_GAP = 12
SEARCH_PLACEHOLDER = "Search by file name"
PREVIEW_MAX_HEIGHT, PREVIEW_MIN_HEIGHT = 176, 90
TYPE_FILTERS = {"All": None, "Images": "image", "Videos": "video"}
SORTS = {"Newest first": "newest", "Oldest first": "oldest", "Name A–Z": "name", "Name Z–A": "name_desc"}


class LibraryCard(Card):
    """One tile in the grid. Cards are reused when the page changes, so content is set via set_item()."""

    def __init__(self, master, on_select, on_open, on_favorite):
        super().__init__(master, corner_radius=12, width=CARD_IMAGE[0] + 16)
        self.item: MediaItem | None = None
        self._on_select, self._on_open, self._on_favorite = on_select, on_open, on_favorite

        self.thumb = ctk.CTkLabel(self, text="", width=CARD_IMAGE[0], height=CARD_IMAGE[1])
        self.thumb.grid(row=0, column=0, columnspan=2, padx=8, pady=(8, 6))
        self.name = ctk.CTkLabel(self, text="", font=theme.font(13, "bold"), text_color=theme.TEXT, anchor="w")
        self.name.grid(row=1, column=0, sticky="w", padx=(12, 0))
        self.meta = ctk.CTkLabel(self, text="", font=theme.font(11), text_color=theme.TEXT_MUTED, anchor="w")
        self.meta.grid(row=2, column=0, sticky="w", padx=(12, 0), pady=(0, 10))
        self.star = ctk.CTkButton(self, text="", width=30, height=30, corner_radius=15, fg_color="transparent",
                                  hover_color=theme.NAV_HOVER, command=self._toggle_favorite)
        self.star.grid(row=1, column=1, rowspan=2, padx=(0, 8), pady=(0, 6))
        self.grid_columnconfigure(0, weight=1)

        for widget in (self, self.thumb, self.name, self.meta):
            widget.bind("<Button-1>", lambda e: self.item and self._on_select(self.item, e))
            widget.bind("<Double-Button-1>", lambda _e: self.item and self._on_open(self.item))
            widget.configure(cursor="hand2")

    def set_item(self, item: MediaItem, image: ctk.CTkImage, selected: bool) -> None:
        self.item = item
        self.thumb.configure(image=image)
        name = item.filename if len(item.filename) <= 24 else item.filename[:22] + "…"
        self.name.configure(text=name)
        kind = "Video" if item.is_video else "Image"
        self.meta.configure(text=f"{kind}  ·  {item.resolution_text}")
        self.set_favorite(item.favorite)
        self.set_selected(selected)

    def set_favorite(self, favorite: bool) -> None:
        self.star.configure(image=icons.icon("star_filled", 16, theme.STAR) if favorite
                            else icons.icon("star", 16, theme.TEXT_MUTED))

    def set_selected(self, selected: bool) -> None:
        self.configure(border_width=2 if selected else 1,
                       border_color=theme.ACCENT if selected else theme.CARD_BORDER)

    def _toggle_favorite(self) -> None:
        if self.item:
            self._on_favorite(self.item)


# --- details panel -------------------------------------------------------------

class DetailsPanel(Card):
    """Right-hand panel with a live preview, metadata and actions for the selected item."""

    FIELDS = ("Type", "Resolution", "Duration", "File size", "Added", "Location")

    def __init__(self, master, page: "LibraryPage"):
        super().__init__(master, width=340)
        self.page = page
        self.item: MediaItem | None = None
        self.grid_propagate(False)
        self.grid_columnconfigure(0, weight=1)

        self._preview_height = PREVIEW_MAX_HEIGHT
        self.preview = MediaPreview(self, height=PREVIEW_MAX_HEIGHT)
        self.preview.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 10))

        self.title = ctk.CTkLabel(self, text="", font=theme.font(16, "bold"), text_color=theme.TEXT,
                                  anchor="w", justify="left", wraplength=300)
        self.title.grid(row=1, column=0, sticky="ew", padx=16)

        info = self.info = ctk.CTkFrame(self, fg_color="transparent")
        info.grid(row=2, column=0, sticky="ew", padx=16, pady=(8, 8))
        info.grid_columnconfigure(1, weight=1)
        self.values: dict[str, ctk.CTkLabel] = {}
        self.labels: dict[str, ctk.CTkLabel] = {}
        for row, name in enumerate(self.FIELDS):
            self.labels[name] = ctk.CTkLabel(info, text=name, font=theme.font(12), text_color=theme.TEXT_MUTED,
                                             anchor="w", height=20)
            self.labels[name].grid(row=row, column=0, sticky="w", padx=(0, 12))
            self.values[name] = ctk.CTkLabel(info, text="", font=theme.font(12), text_color=theme.TEXT,
                                             anchor="w", height=20)
            self.values[name].grid(row=row, column=1, sticky="w")

        actions = ctk.CTkFrame(self, fg_color="transparent")
        actions.grid(row=3, column=0, sticky="ew", padx=12, pady=(0, 8))
        actions.grid_columnconfigure((0, 1), weight=1, uniform="actions")
        button(actions, "Desktop", self._set_desktop, icon="desktop", style="primary").grid(
            row=0, column=0, sticky="ew", padx=4, pady=4)
        self.lock_btn = button(actions, "Lock screen", self._set_lock_screen, icon="lock", style="primary")
        self.lock_btn.grid(row=0, column=1, sticky="ew", padx=4, pady=4)
        self.fav_button = button(actions, "Favorite", self._toggle_favorite, icon="star")
        self.fav_button.grid(row=1, column=0, sticky="ew", padx=4, pady=4)
        button(actions, "Preview", self._open_preview, icon="open").grid(row=1, column=1, sticky="ew", padx=4, pady=4)
        button(actions, "Show in folder", self._show_in_explorer, icon="folder_open").grid(
            row=2, column=0, sticky="ew", padx=4, pady=4)
        button(actions, "Remove", self._remove, icon="delete").grid(row=2, column=1, sticky="ew", padx=4, pady=4)
        self.playlist_link = link_button(actions, "Add to playlist…", self._add_to_playlist, theme.ACCENT)
        self.playlist_link.grid(row=3, column=0, pady=(2, 0))
        link_button(actions, "Delete file…", self._delete_file, theme.DANGER).grid(row=3, column=1, pady=(2, 0))
        self.actions = actions
        self._build_bulk_panel()

        self.empty = ctk.CTkLabel(self, text="Select a wallpaper\nto see its details", font=theme.font(13),
                                  text_color=theme.TEXT_MUTED, image=icons.icon("image", 40, theme.TEXT_SUBTLE),
                                  compound="top")
        self.bind("<Configure>", lambda _e: self.after_idle(self._fit_preview))
        self.show(None)

    def _build_bulk_panel(self) -> None:
        """Shown instead of the details when several wallpapers are selected."""
        bulk = self.bulk = ctk.CTkFrame(self, fg_color="transparent")
        bulk.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(bulk, text="", image=icons.icon("library", 40, theme.ACCENT)).grid(row=0, column=0, pady=(60, 6))
        self.bulk_title = ctk.CTkLabel(bulk, text="", font=theme.font(17, "bold"), text_color=theme.TEXT)
        self.bulk_title.grid(row=1, column=0)
        ctk.CTkLabel(bulk, text="Ctrl+click to add or remove, Shift+click for a range",
                     font=theme.font(12), text_color=theme.TEXT_MUTED).grid(row=2, column=0, pady=(0, 16))
        for row, (text, icon_name, command) in enumerate([
                ("Add to favorites", "star_filled", lambda: self.page.bulk_favorite(True)),
                ("Remove from favorites", "star", lambda: self.page.bulk_favorite(False)),
                ("Add to playlist…", "playlist", self._add_to_playlist),
                ("Remove from library", "delete", self.page.bulk_remove)], start=3):
            widget = button(bulk, text, command, icon=icon_name)
            widget.grid(row=row, column=0, sticky="ew", padx=24, pady=4)
            if text == "Add to playlist…":
                self.bulk_playlist_btn = widget
        link_button(bulk, "Clear selection", self.page.clear_selection, theme.TEXT_MUTED).grid(
            row=7, column=0, pady=(8, 0))

    def show_bulk(self, count: int) -> None:
        self.item = None
        self.preview.stop()
        for widget in [self.preview, self.title, self.actions, self.empty, *self.labels.values(),
                       *self.values.values()]:
            widget.grid_remove()
        self.bulk_title.configure(text=f"{count} wallpapers selected")
        self.bulk.grid(row=0, column=0, rowspan=4, sticky="nsew")

    def show(self, item: MediaItem | None) -> None:
        self.item = item
        self.bulk.grid_remove()
        widgets = [self.preview, self.title, self.actions, *self.labels.values(), *self.values.values()]
        if item is None:
            self.preview.stop()
            for widget in widgets:
                widget.grid_remove()
            self.empty.grid(row=0, column=0, rowspan=4, pady=180)
            return
        self.empty.grid_remove()
        for widget in widgets:
            widget.grid()

        self.preview.show(item)
        self.title.configure(text=item.filename)
        missing = not os.path.exists(item.path)
        values = {
            "Type": ("Video" if item.is_video else "Image") + (f" ({os.path.splitext(item.filename)[1][1:].upper()})"),
            "Resolution": item.resolution_text,
            "Duration": item.duration_text,
            "File size": "File missing" if missing else item.size_text,
            "Added": self._format_date(item.date_added),
            "Location": imaging.shorten_middle(os.path.dirname(item.path), 34),
        }
        for name, value in values.items():
            self.values[name].configure(text=value, text_color=theme.DANGER if missing and name == "File size"
                                        else theme.TEXT)
        if not item.is_video:
            self.labels["Duration"].grid_remove()
            self.values["Duration"].grid_remove()
        self.lock_btn.configure(text="Lock screen frame…" if item.is_video else "Lock screen")
        self.update_favorite()
        self.after_idle(self._fit_preview)

    def _fit_preview(self) -> None:
        """Shrink the preview on short windows so the buttons below it stay visible."""
        if self.item is None or not self.winfo_exists():
            return
        scale = ScalingTracker.get_widget_scaling(self)
        others_px = sum(w.winfo_reqheight() for w in (self.title, self.info, self.actions))
        spare = (self.winfo_height() - others_px) / scale - 50  # 50 = vertical padding
        height = int(max(PREVIEW_MIN_HEIGHT, min(PREVIEW_MAX_HEIGHT, spare)))
        if height != self._preview_height:
            self._preview_height = height
            self.preview.configure(height=height)

    def update_favorite(self) -> None:
        if self.item:
            fav = self.item.favorite
            self.fav_button.configure(
                text="Favorited" if fav else "Favorite",
                image=icons.icon("star_filled", 16, theme.STAR) if fav else icons.icon("star", 16, theme.TEXT))

    @staticmethod
    def _format_date(value: str) -> str:
        try:
            return datetime.strptime(value, "%Y-%m-%d %H:%M:%S").strftime("%d %b %Y, %H:%M")
        except ValueError:
            return value

    def _set_desktop(self) -> None:
        if self.item:
            self.page.set_as_desktop(self.item)

    def _set_lock_screen(self) -> None:
        if self.item:
            self.page.set_as_lock_screen(self.item)

    def _toggle_favorite(self) -> None:
        if self.item:
            self.page.toggle_favorite(self.item)

    def _open_preview(self) -> None:
        if self.item:
            self.page.open_preview(self.item)

    def _show_in_explorer(self) -> None:
        if self.item and os.path.exists(self.item.path):
            winapi.show_in_explorer(self.item.path)
        elif self.item:
            messagebox.showwarning("File not found", f"This file no longer exists:\n{self.item.path}")

    def _remove(self) -> None:
        if self.item:
            self.page.remove_item(self.item, delete_file=False)

    def _add_to_playlist(self) -> None:
        anchor = self.bulk_playlist_btn if self.bulk.winfo_ismapped() else self.playlist_link
        self.page.show_playlist_menu(anchor)

    def _delete_file(self) -> None:
        if self.item:
            self.page.remove_item(self.item, delete_file=True)


# --- the page --------------------------------------------------------------------

class LibraryPage(BasePage):
    title = "Library"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.db = app.db
        self.thumb_dir = app.paths.thumb_dir
        self.page_index = 0
        self.selected_ids: set[int] = set()
        self._anchor_id: int | None = None  # start of a Shift+click range
        self.items: list[MediaItem] = []
        self.cards: list[LibraryCard] = []
        self.columns = 0
        self._search_job: str | None = None
        self.importing = False

        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(3, weight=1)

        # Header + import buttons
        self.header = PageHeader(self, "Library", "")
        self.header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 16))
        self.import_folder_btn = button(self.header.actions, "Import folder", self.import_folder, icon="folder")
        self.import_folder_btn.pack(side="left", padx=(0, 8))
        self.import_files_btn = button(self.header.actions, "Import files", self.import_files, icon="add",
                                       style="primary")
        self.import_files_btn.pack(side="left")

        self._build_toolbar()

        # Import progress (only visible while importing)
        self.progress_row = ctk.CTkFrame(self, fg_color="transparent")
        self.progress_label = ctk.CTkLabel(self.progress_row, text="", font=theme.font(12),
                                           text_color=theme.TEXT_MUTED, anchor="w")
        self.progress_label.pack(fill="x")
        self.progress_bar = ctk.CTkProgressBar(self.progress_row, height=6, progress_color=theme.ACCENT)
        self.progress_bar.pack(fill="x", pady=(2, 0))

        # Grid of cards + details panel
        body = ctk.CTkFrame(self, fg_color="transparent")
        body.grid(row=3, column=0, sticky="nsew", padx=(20, 24), pady=(0, 8))
        body.grid_columnconfigure(0, weight=1)
        body.grid_rowconfigure(0, weight=1)
        # The scrollable frame's own size follows its content, so the available
        # width is measured on this holder frame instead.
        self.grid_holder = ctk.CTkFrame(body, fg_color="transparent")
        self.grid_holder.grid(row=0, column=0, sticky="nsew")
        self.grid_holder.bind("<Configure>", lambda _e: self._reflow())
        self.grid_area = ctk.CTkScrollableFrame(self.grid_holder, fg_color="transparent")
        self.grid_area.pack(fill="both", expand=True)
        self.details = DetailsPanel(body, self)
        self.details.grid(row=0, column=1, sticky="ns", padx=(8, 0))

        self.empty_state = ctk.CTkFrame(self.grid_area, fg_color="transparent")
        self.empty_icon = ctk.CTkLabel(self.empty_state, text="", image=icons.icon("library", 48, theme.TEXT_SUBTLE))
        self.empty_icon.pack(pady=(80, 10))
        self.empty_title = ctk.CTkLabel(self.empty_state, text="", font=theme.font(17, "bold"), text_color=theme.TEXT)
        self.empty_title.pack()
        self.empty_text = ctk.CTkLabel(self.empty_state, text="", font=theme.font(13), text_color=theme.TEXT_MUTED)
        self.empty_text.pack(pady=(2, 14))
        self.empty_button = button(self.empty_state, "Import files", self.import_files, icon="add", style="primary")

        # Pagination
        pager = ctk.CTkFrame(self, fg_color="transparent")
        pager.grid(row=4, column=0, sticky="ew", padx=32, pady=(0, 16))
        self.page_label = ctk.CTkLabel(pager, text="", font=theme.font(12), text_color=theme.TEXT_MUTED)
        self.page_label.pack(side="left")
        self.next_btn = button(pager, "Next", lambda: self._change_page(1), width=80, height=30)
        self.next_btn.pack(side="right")
        self.prev_btn = button(pager, "Previous", lambda: self._change_page(-1), width=80, height=30)
        self.prev_btn.pack(side="right", padx=(0, 8))

    def _build_toolbar(self) -> None:
        bar = ctk.CTkFrame(self, fg_color="transparent")
        bar.grid(row=1, column=0, sticky="ew", padx=32, pady=(0, 12))

        search = ctk.CTkEntry(bar, placeholder_text=SEARCH_PLACEHOLDER,
                              width=240, height=34, corner_radius=8, font=theme.font(13),
                              fg_color=theme.INPUT_BG, border_color=theme.CARD_BORDER, border_width=1)
        search.pack(side="left")
        search.bind("<KeyRelease>", lambda _e: self._schedule_search())
        search.bind("<Escape>", lambda _e: self.clear_search())
        self.search_entry = search

        self.type_filter = segmented(bar, list(TYPE_FILTERS), lambda _v: self.refresh(reset_page=True))
        self.type_filter.set("All")
        self.type_filter.pack(side="left", padx=12)

        self.favorites_only = ctk.CTkSwitch(bar, text="Favorites only", font=theme.font(13),
                                            progress_color=theme.ACCENT,
                                            command=lambda: self.refresh(reset_page=True))
        self.favorites_only.pack(side="left", padx=(4, 0))

        self.sort = ctk.CTkOptionMenu(bar, values=list(SORTS), command=lambda _v: self.refresh(reset_page=True),
                                      width=140, height=34, font=theme.font(13), fg_color=theme.SECONDARY_BTN,
                                      button_color=theme.SECONDARY_BTN, button_hover_color=theme.SECONDARY_BTN_HOVER,
                                      text_color=theme.TEXT, dropdown_font=theme.font(13))
        self.sort.set("Newest first")
        self.sort.pack(side="right")

    # -- page lifecycle -----------------------------------------------------------

    def on_show(self) -> None:
        self.refresh()

    def on_hide(self) -> None:
        self.details.preview.stop()  # don't keep decoding video in the background

    def _filters(self) -> dict:
        return dict(search=self.search_entry.get(), media_type=TYPE_FILTERS[self.type_filter.get()],
                    favorites_only=bool(self.favorites_only.get()))

    def clear_search(self, refresh: bool = True) -> None:
        self.search_entry.delete(0, "end")
        self.focus()
        # Re-setting the placeholder makes CTkEntry show it again after a programmatic clear.
        self.search_entry.configure(placeholder_text=SEARCH_PLACEHOLDER)
        if refresh:
            self.refresh(reset_page=True)

    def _schedule_search(self) -> None:
        if self._search_job:
            self.after_cancel(self._search_job)
        self._search_job = self.after(250, lambda: self.refresh(reset_page=True))

    def refresh(self, reset_page: bool = False) -> None:
        """Reload the current page of results from the database."""
        if reset_page:
            self.page_index = 0
        filters = self._filters()
        total = self.db.count_media(**filters)
        pages = max(1, (total + PAGE_SIZE - 1) // PAGE_SIZE)
        self.page_index = min(self.page_index, pages - 1)
        self.items = self.db.list_media(**filters, sort=SORTS[self.sort.get()], limit=PAGE_SIZE,
                                        offset=self.page_index * PAGE_SIZE)

        library_total = self.db.count_media()
        self.header.subtitle.configure(
            text=f"{library_total} wallpaper{'s' if library_total != 1 else ''} in your library")
        if total:
            first = self.page_index * PAGE_SIZE + 1
            self.page_label.configure(text=f"Showing {first}–{first + len(self.items) - 1} of {total}")
        else:
            self.page_label.configure(text="")
        self.prev_btn.configure(state="normal" if self.page_index > 0 else "disabled")
        self.next_btn.configure(state="normal" if self.page_index < pages - 1 else "disabled")

        self._render_cards()
        self._update_empty_state(library_total, total)

        # Keep the selection to items still on this page (others may have been filtered out or removed).
        self.selected_ids &= {m.id for m in self.items}
        self._update_details()

    @property
    def selected_items(self) -> list[MediaItem]:
        return [m for m in self.items if m.id in self.selected_ids]

    def _update_details(self) -> None:
        selected = self.selected_items
        if len(selected) > 1:
            self.details.show_bulk(len(selected))
            return
        item = selected[0] if selected else None
        shown = self.details.item
        if item is None or shown is None or item.id != shown.id:
            self.details.show(item)
        else:  # same item: just refresh its data without restarting the preview
            self.details.item = item
            self.details.update_favorite()

    def _change_page(self, delta: int) -> None:
        self.page_index += delta
        self.refresh()
        self.grid_area._parent_canvas.yview_moveto(0)

    # -- grid -------------------------------------------------------------------------

    def _render_cards(self) -> None:
        while len(self.cards) < len(self.items):
            self.cards.append(LibraryCard(self.grid_area, self.select_item, self.open_preview, self.toggle_favorite))
        for card, item in zip(self.cards, self.items):
            card.set_item(item, self.app.thumbs.get(item, CARD_IMAGE), item.id in self.selected_ids)
        for card in self.cards[len(self.items):]:
            card.grid_remove()
            card.item = None
        self.columns = 0  # force re-layout
        self._reflow()

    def _reflow(self) -> None:
        """Place the cards in as many columns as fit the current width."""
        visible = [c for c in self.cards if c.item is not None]
        scale = ScalingTracker.get_widget_scaling(self)
        card_px = (CARD_IMAGE[0] + 16 + CARD_GAP) * scale
        width = self.grid_holder.winfo_width() - 20 * scale  # minus the scrollbar
        columns = max(1, int(width // card_px)) if width > 1 else 3
        if columns == self.columns:
            return
        # Equal-weight columns spread the cards evenly across the available width.
        for col in range(max(columns, self.columns)):
            self.grid_area.grid_columnconfigure(col, weight=1 if col < columns else 0,
                                                uniform="cards" if col < columns else "")
        self.columns = columns
        for index, card in enumerate(visible):
            card.grid(row=index // columns, column=index % columns, padx=CARD_GAP // 2, pady=CARD_GAP // 2,
                      sticky="n")

    def _update_empty_state(self, library_total: int, shown: int) -> None:
        if shown:
            self.empty_state.grid_remove()
            return
        if library_total == 0:
            self.empty_title.configure(text="Your library is empty")
            self.empty_text.configure(text="Import images (JPG, PNG, WEBP, GIF) or videos (MP4, WEBM) to get started.")
            self.empty_button.pack()
        else:
            self.empty_title.configure(text="No matches")
            self.empty_text.configure(text="No wallpapers match your search and filters.")
            self.empty_button.pack_forget()
        self.empty_state.grid(row=0, column=0, columnspan=max(self.columns, 1), sticky="ew")

    # -- actions ------------------------------------------------------------------------

    def select_item(self, item: MediaItem, event=None) -> None:
        """Click selects one item; Ctrl+click adds/removes it; Shift+click selects a range."""
        state = event.state if event is not None else 0
        ctrl, shift = bool(state & 0x0004), bool(state & 0x0001)
        ids = [m.id for m in self.items]
        if shift and self._anchor_id in ids:
            start, end = sorted((ids.index(self._anchor_id), ids.index(item.id)))
            chosen = set(ids[start:end + 1])
            self.selected_ids = self.selected_ids | chosen if ctrl else chosen
        elif ctrl:
            self.selected_ids ^= {item.id}
            self._anchor_id = item.id
        else:
            self.selected_ids = {item.id}
            self._anchor_id = item.id
        self._sync_selection()

    def _sync_selection(self) -> None:
        for card in self.cards:
            if card.item is not None:
                card.set_selected(card.item.id in self.selected_ids)
        self._update_details()

    def select_all(self) -> None:
        self.selected_ids = {m.id for m in self.items}
        self._sync_selection()

    def clear_selection(self) -> None:
        self.selected_ids = set()
        self._sync_selection()

    def bulk_favorite(self, favorite: bool) -> None:
        items = self.selected_items
        for item in items:
            self.db.set_favorite(item.id, favorite)
        self.refresh()
        self.app.toast(f"{'Added' if favorite else 'Removed'} {len(items)} wallpapers "
                       f"{'to' if favorite else 'from'} favorites", "success")

    def bulk_remove(self) -> None:
        items = self.selected_items
        if not items or not messagebox.askyesno(
                "Remove from library", f"Remove {len(items)} wallpapers from the library?\n\n"
                "The files themselves are not deleted.", parent=self):
            return
        self.details.preview.stop()
        for item in items:
            self.db.remove_media(item.id)
            media.delete_thumbnail(item, self.thumb_dir)
        log.info("Removed %d items from the library", len(items))
        self.selected_ids = set()
        self.refresh()
        self.app.toast(f"Removed {len(items)} wallpapers from the library (the files are kept)")

    def show_playlist_menu(self, anchor) -> None:
        """Small menu of playlists below `anchor`; adds the selected wallpapers to the one chosen."""
        items = self.selected_items
        if not items:
            return
        menu = tk.Menu(self, tearoff=0)
        for playlist in self.db.list_playlists():
            menu.add_command(label=f"{playlist.name}  ({playlist.item_count})",
                             command=lambda p=playlist: self._add_to_playlist(p.id, p.name, items))
        if self.db.list_playlists():
            menu.add_separator()
        menu.add_command(label="New playlist…", command=lambda: self._add_to_new_playlist(items))
        menu.tk_popup(anchor.winfo_rootx(), anchor.winfo_rooty() + anchor.winfo_height())

    def _add_to_new_playlist(self, items: list[MediaItem]) -> None:
        name = (ctk.CTkInputDialog(title="New playlist", text="Name of the new playlist:").get_input() or "").strip()
        if name:
            self._add_to_playlist(self.db.create_playlist(name), name, items)

    def _add_to_playlist(self, playlist_id: int, name: str, items: list[MediaItem]) -> None:
        before = self.db.get_playlist(playlist_id).item_count
        self.db.add_to_playlist(playlist_id, [m.id for m in items])
        added = self.db.get_playlist(playlist_id).item_count - before
        skipped = f" ({len(items) - added} already in it)" if added < len(items) else ""
        self.app.toast(f"Added {added} to “{name}”{skipped}", "success")

    def show_item(self, media_id: int) -> None:
        """Select an item from elsewhere in the app (e.g. the Home page)."""
        self.clear_search(refresh=False)
        self.type_filter.set("All")
        self.favorites_only.deselect()
        self.sort.set("Newest first")
        self.selected_ids = {media_id}
        self._anchor_id = media_id
        self.refresh(reset_page=True)
        ids = [m.id for m in self.db.list_media(sort="newest")]
        if media_id in ids:
            self.page_index = ids.index(media_id) // PAGE_SIZE
            self.refresh()

    def open_preview(self, item: MediaItem) -> None:
        self.details.preview.stop()  # only decode one video at a time
        window = PreviewWindow(self, item)

        def on_close(event):
            # <Destroy> also fires for each child widget; react only to the window itself.
            if event.widget is window and self.details.item and self.winfo_ismapped():
                self.details.preview.show(self.details.item)

        window.bind("<Destroy>", on_close, add="+")

    def set_as_desktop(self, item: MediaItem) -> None:
        """Apply the item and switch to the Desktop page so the user sees the result."""
        self.app.show_page("desktop").apply(item)

    def set_as_lock_screen(self, item: MediaItem) -> None:
        """Images switch to the Lock Screen page and apply; videos open the frame picker."""
        if item.is_video:
            FramePicker(self, self.app, item)
        else:
            self.app.show_page("lockscreen").apply(item)

    def toggle_favorite(self, item: MediaItem) -> None:
        item.favorite = not item.favorite
        self.db.set_favorite(item.id, item.favorite)
        for card in self.cards:
            if card.item is not None and card.item.id == item.id:
                card.item.favorite = item.favorite
                card.set_favorite(item.favorite)
        if self.details.item and self.details.item.id == item.id:
            self.details.item.favorite = item.favorite
            self.details.update_favorite()
        if self.favorites_only.get():
            self.refresh()

    def remove_item(self, item: MediaItem, delete_file: bool) -> None:
        if delete_file:
            if not messagebox.askyesno(
                    "Delete file",
                    f"Move “{item.filename}” to the Recycle Bin?\n\n"
                    "It will be removed from your library and deleted from disk. "
                    "You can restore it from the Recycle Bin.",
                    icon="warning", parent=self):
                return
            self.details.preview.stop()  # release the file before deleting it
            if os.path.exists(item.path):
                try:
                    winapi.send_to_recycle_bin(item.path)
                except OSError as exc:
                    log.error("Delete failed: %s", exc)
                    messagebox.showerror("Delete failed", str(exc), parent=self)
                    self.details.show(item)
                    return
            log.info("Deleted file %s", item.path)
            self.app.toast(f"Moved {item.filename} to the Recycle Bin")
        else:
            self.app.toast(f"Removed {item.filename} from the library (the file is kept)")
        self.db.remove_media(item.id)
        media.delete_thumbnail(item, self.thumb_dir)
        log.info("Removed %s from library", item.path)
        self.selected_ids.discard(item.id)
        self.refresh()

    # -- importing ----------------------------------------------------------------------

    def import_files(self) -> None:
        paths = filedialog.askopenfilenames(parent=self, title="Import wallpapers", filetypes=media.FILE_TYPES)
        if paths:
            self.start_import(lambda: list(paths))

    def import_folder(self) -> None:
        folder = filedialog.askdirectory(parent=self, title="Import a folder of wallpapers", mustexist=True)
        if folder:
            self.start_import(lambda: media.find_media_files(folder))

    def start_import(self, get_paths) -> None:
        """Import in the background so the window stays responsive. `get_paths` runs on the worker too
        because scanning a large folder can take a while."""
        if self.importing:
            return
        self.importing = True
        state = {"done": 0, "total": 0, "current": "Scanning…"}

        def progress(done, total, path):
            state.update(done=done, total=total, current=os.path.basename(path))

        def work():
            return media.import_files(self.db, get_paths(), self.thumb_dir, progress)

        def tick():
            total = state["total"]
            self.progress_bar.set(state["done"] / total if total else 0)
            counter = f"Importing {state['done']} of {total}  ·  " if total else ""
            self.progress_label.configure(text=f"{counter}{state['current']}")

        self.import_files_btn.configure(state="disabled")
        self.import_folder_btn.configure(state="disabled")
        self.progress_bar.set(0)
        self.progress_row.grid(row=2, column=0, sticky="ew", padx=32, pady=(0, 12))
        run_in_thread(self, work, on_done=self._import_finished, on_error=self._import_failed, on_tick=tick)

    def _end_import(self) -> None:
        self.importing = False
        self.progress_row.grid_remove()
        self.import_files_btn.configure(state="normal")
        self.import_folder_btn.configure(state="normal")

    def _import_finished(self, result: media.ImportResult) -> None:
        self._end_import()
        self.sort.set("Newest first")
        self.refresh(reset_page=True)
        lines = [f"Added {len(result.added)} wallpaper{'s' if len(result.added) != 1 else ''}."]
        if result.skipped:
            lines.append(f"Skipped {result.skipped} (already in the library or not supported).")
        if result.failed:
            lines.append(f"\nCould not import {len(result.failed)} file(s):")
            lines += [f"•  {os.path.basename(p)} – {reason}" for p, reason in result.failed[:8]]
            if len(result.failed) > 8:
                lines.append(f"…and {len(result.failed) - 8} more (see the log file).")
            messagebox.showwarning("Import finished", "\n".join(lines), parent=self)
        else:
            self.app.toast(" ".join(lines), "success" if result.added else "info")

    def _import_failed(self, exc: BaseException) -> None:
        self._end_import()
        messagebox.showerror("Import failed", str(exc), parent=self)
