"""Playlists page: create playlists of images and videos and play them on the desktop."""

import logging
from tkinter import messagebox

import customtkinter as ctk

from ...database import MediaItem, Playlist
from ...playlists import MIN_INTERVAL_SECONDS
from .. import icons, theme
from ..chooser import MediaChooser
from ..widgets import BasePage, Card, PageHeader, button, icon_button, run_in_thread, section_label, segmented

log = logging.getLogger(__name__)

ITEM_THUMB = (96, 54)
MODES = {"Sequential": "sequential", "Random": "random"}
UNITS = {"minutes": 60, "hours": 3600}


def format_countdown(seconds: float) -> str:
    seconds = int(seconds)
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}:{minutes:02}:{secs:02}" if hours else f"{minutes}:{secs:02}"


class PlaylistRow(ctk.CTkFrame):
    """One entry in the list of playlists on the left."""

    def __init__(self, master, playlist: Playlist, selected: bool, playing: bool, on_click):
        super().__init__(master, corner_radius=8, fg_color=theme.NAV_SELECTED if selected else "transparent",
                         cursor="hand2")
        self.grid_columnconfigure(0, weight=1)
        name = ctk.CTkLabel(self, text=playlist.name, font=theme.font(14, "bold" if selected else "normal"),
                            text_color=theme.TEXT, anchor="w")
        name.grid(row=0, column=0, sticky="w", padx=12, pady=(8, 0))
        count = f"{playlist.item_count} item{'s' if playlist.item_count != 1 else ''}"
        meta = ctk.CTkLabel(self, text=f"{count}  ·  {playlist.mode.title()}  ·  {playlist.interval_text}",
                            font=theme.font(11), text_color=theme.TEXT_MUTED, anchor="w")
        meta.grid(row=1, column=0, sticky="w", padx=12, pady=(0, 8))
        widgets = [self, name, meta]
        if playing:
            badge = ctk.CTkLabel(self, text="", image=icons.icon("play", 14, theme.ACCENT))
            badge.grid(row=0, column=1, rowspan=2, padx=10)
            widgets.append(badge)
        for widget in widgets:
            widget.bind("<Button-1>", lambda _e: on_click(playlist.id))


class ItemRow(ctk.CTkFrame):
    """One wallpaper inside the selected playlist, with move / remove buttons."""

    def __init__(self, master, page: "PlaylistsPage", item: MediaItem, index: int, count: int, now_showing: bool):
        super().__init__(master, corner_radius=8, fg_color=theme.NAV_SELECTED if now_showing else "transparent")
        self.grid_columnconfigure(2, weight=1)
        ctk.CTkLabel(self, text=str(index + 1), width=26, font=theme.font(12), text_color=theme.TEXT_SUBTLE).grid(
            row=0, column=0, rowspan=2, padx=(6, 0))
        ctk.CTkLabel(self, text="", image=page.app.thumbs.get(item, ITEM_THUMB)).grid(
            row=0, column=1, rowspan=2, padx=8, pady=6)
        ctk.CTkLabel(self, text=item.filename, font=theme.font(13), text_color=theme.TEXT, anchor="w").grid(
            row=0, column=2, sticky="sw")
        kind = f"Video · {item.duration_text}" if item.is_video else "Image"
        ctk.CTkLabel(self, text=f"{kind}  ·  {item.resolution_text}" + ("   ·  Now showing" if now_showing else ""),
                     font=theme.font(11), text_color=theme.ACCENT if now_showing else theme.TEXT_MUTED,
                     anchor="w").grid(row=1, column=2, sticky="nw")
        buttons = ctk.CTkFrame(self, fg_color="transparent")
        buttons.grid(row=0, column=3, rowspan=2, padx=6)
        up = icon_button(buttons, "up", lambda: page.move_item(item, -1))
        down = icon_button(buttons, "down", lambda: page.move_item(item, +1))
        up.pack(side="left")
        down.pack(side="left")
        icon_button(buttons, "close", lambda: page.remove_item(item)).pack(side="left", padx=(6, 0))
        if index == 0:
            up.configure(state="disabled")
        if index == count - 1:
            down.configure(state="disabled")


class AddWallpapersDialog(ctk.CTkToplevel):
    """Click tiles to add them to (or remove them from) the playlist."""

    def __init__(self, page: "PlaylistsPage", playlist_id: int):
        super().__init__(page, fg_color=theme.WINDOW_BG)
        self.page = page
        self.playlist_id = playlist_id
        self.title(f"Add wallpapers – {page.app.db.get_playlist(playlist_id).name}")
        self.geometry("900x620")
        self.minsize(600, 420)
        ctk.CTkLabel(self, text="Click wallpapers to add them; click again to remove. Highlighted = in the playlist.",
                     font=theme.font(13), text_color=theme.TEXT_MUTED).pack(anchor="w", padx=20, pady=(16, 8))
        self.chooser = MediaChooser(self, page.app, self._toggle, allow_video=True)
        self.chooser.pack(fill="both", expand=True, padx=16)
        button(self, "Done", self.destroy, style="primary", width=110).pack(anchor="e", padx=20, pady=14)
        self.chooser.refresh()
        self._sync_marks()
        self.after(150, lambda: (self.lift(), self.focus_force()))

    def _sync_marks(self) -> None:
        self.chooser.set_marked({m.id for m in self.page.app.db.playlist_items(self.playlist_id)})

    def _toggle(self, item: MediaItem) -> None:
        db = self.page.app.db
        if item.id in self.chooser.marked_ids:
            db.remove_from_playlist(self.playlist_id, item.id)
        else:
            db.add_to_playlist(self.playlist_id, [item.id])
        self._sync_marks()
        self.page.refresh()


class PlaylistsPage(BasePage):
    title = "Playlists"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.db = app.db
        self.player = app.playlists
        self.selected_id: int | None = None
        self._tick_job = None
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(1, weight=1)

        header = PageHeader(self, "Playlists", "Rotate through your wallpapers automatically.")
        header.grid(row=0, column=0, columnspan=2, sticky="ew", padx=32, pady=(28, 16))
        button(header.actions, "New playlist", self.new_playlist, icon="add", style="primary").pack()

        # Left: list of playlists
        left = Card(self, width=290)
        left.grid(row=1, column=0, sticky="ns", padx=(32, 12), pady=(0, 20))
        left.grid_propagate(False)
        left.grid_rowconfigure(0, weight=1)
        left.grid_columnconfigure(0, weight=1)
        self.list_frame = ctk.CTkScrollableFrame(left, fg_color="transparent")
        self.list_frame.grid(row=0, column=0, sticky="nsew", padx=6, pady=8)

        # Right: editor
        self.editor = Card(self)
        self.editor.grid(row=1, column=1, sticky="nsew", padx=(0, 32), pady=(0, 20))
        self.editor.grid_columnconfigure(0, weight=1)
        self.editor.grid_rowconfigure(4, weight=1)
        self._build_editor()

        self.empty = ctk.CTkLabel(self.editor, text="Create a playlist to rotate wallpapers automatically.",
                                  font=theme.font(14), text_color=theme.TEXT_MUTED,
                                  image=icons.icon("playlist", 44, theme.TEXT_SUBTLE), compound="top")

    def _build_editor(self) -> None:
        top = ctk.CTkFrame(self.editor, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=18, pady=(16, 8))
        top.grid_columnconfigure(0, weight=1)
        self.name_entry = ctk.CTkEntry(top, height=36, font=theme.font(16, "bold"), fg_color=theme.INPUT_BG,
                                       border_color=theme.CARD_BORDER, border_width=1, corner_radius=8)
        self.name_entry.grid(row=0, column=0, sticky="ew")
        self.name_entry.bind("<Return>", lambda _e: self._rename())
        self.name_entry.bind("<FocusOut>", lambda _e: self._rename())
        self.play_btn = button(top, "Play", self._toggle_play, icon="play", style="primary", width=100)
        self.play_btn.grid(row=0, column=1, padx=(10, 0))
        icon_button(top, "delete", self._delete, color=theme.DANGER).grid(row=0, column=2, padx=(6, 0))

        settings = ctk.CTkFrame(self.editor, fg_color="transparent")
        settings.grid(row=1, column=0, sticky="ew", padx=18)
        ctk.CTkLabel(settings, text="Order", font=theme.font(13), text_color=theme.TEXT).pack(side="left")
        self.mode = segmented(settings, list(MODES), self._change_mode, height=32)
        self.mode.pack(side="left", padx=(10, 24))
        ctk.CTkLabel(settings, text="Change every", font=theme.font(13), text_color=theme.TEXT).pack(side="left")
        self.interval = ctk.CTkEntry(settings, width=60, height=32, font=theme.font(13), justify="center",
                                     fg_color=theme.INPUT_BG, border_color=theme.CARD_BORDER, border_width=1)
        self.interval.pack(side="left", padx=(10, 6))
        self.interval.bind("<Return>", lambda _e: self._change_interval())
        self.interval.bind("<FocusOut>", lambda _e: self._change_interval())
        self.unit = ctk.CTkOptionMenu(settings, values=list(UNITS), width=100, height=32, font=theme.font(13),
                                      fg_color=theme.SECONDARY_BTN, button_color=theme.SECONDARY_BTN,
                                      button_hover_color=theme.SECONDARY_BTN_HOVER, text_color=theme.TEXT,
                                      command=lambda _v: self._change_interval())
        self.unit.pack(side="left")

        self.status = ctk.CTkLabel(self.editor, text="", font=theme.font(12), text_color=theme.TEXT_MUTED,
                                   anchor="w")
        self.status.grid(row=2, column=0, sticky="ew", padx=20, pady=(8, 0))

        items_header = ctk.CTkFrame(self.editor, fg_color="transparent")
        items_header.grid(row=3, column=0, sticky="ew", padx=18, pady=(8, 4))
        self.items_label = section_label(items_header, "Wallpapers")
        self.items_label.pack(side="left")
        button(items_header, "Add wallpapers", self._add_items, icon="add", height=30).pack(side="right")

        self.items_frame = ctk.CTkScrollableFrame(self.editor, fg_color="transparent")
        self.items_frame.grid(row=4, column=0, sticky="nsew", padx=10, pady=(0, 12))
        self.items_frame.grid_columnconfigure(0, weight=1)
        self.editor_widgets = [top, settings, self.status, items_header, self.items_frame]

    # -- lifecycle ---------------------------------------------------------------------

    def on_show(self) -> None:
        self.refresh()
        self._tick()

    def on_hide(self) -> None:
        if self._tick_job:
            self.after_cancel(self._tick_job)
            self._tick_job = None

    def _tick(self) -> None:
        """Update the countdown once a second while the page is visible."""
        self._update_status()
        self._tick_job = self.after(1000, self._tick)

    def refresh(self) -> None:
        playlists = self.db.list_playlists()
        if self.selected_id not in {p.id for p in playlists}:
            active = self.player.active_playlist()
            self.selected_id = active.id if active else (playlists[0].id if playlists else None)
        active = self.player.active_playlist()

        for child in self.list_frame.winfo_children():
            child.destroy()
        for playlist in playlists:
            PlaylistRow(self.list_frame, playlist, playlist.id == self.selected_id,
                        bool(active and active.id == playlist.id), self.select).pack(fill="x", pady=2)
        if not playlists:
            ctk.CTkLabel(self.list_frame, text="No playlists yet", font=theme.font(13),
                         text_color=theme.TEXT_MUTED).pack(pady=20)
        self._show_editor()

    def select(self, playlist_id: int) -> None:
        self.focus()  # commit pending name/interval edits of the previous playlist first
        self.selected_id = playlist_id
        self.refresh()

    # -- editor ------------------------------------------------------------------------

    def _selected(self) -> Playlist | None:
        return self.db.get_playlist(self.selected_id) if self.selected_id else None

    def _show_editor(self) -> None:
        playlist = self._selected()
        if playlist is None:
            for widget in self.editor_widgets:
                widget.grid_remove()
            self.empty.grid(row=0, column=0, rowspan=5, pady=120)
            return
        self.empty.grid_remove()
        for widget in self.editor_widgets:
            widget.grid()

        self.name_entry.delete(0, "end")
        self.name_entry.insert(0, playlist.name)
        self.mode.set(next(label for label, mode in MODES.items() if mode == playlist.mode))
        unit = "hours" if playlist.interval_seconds % 3600 == 0 else "minutes"
        self.unit.set(unit)
        self.interval.delete(0, "end")
        self.interval.insert(0, str(max(1, playlist.interval_seconds // UNITS[unit])))

        items = self.db.playlist_items(playlist.id)
        self.items_label.configure(text=f"Wallpapers ({len(items)})")
        for child in self.items_frame.winfo_children():
            child.destroy()
        showing = self.player.current_media_id() if self._is_playing(playlist) else None
        for index, item in enumerate(items):
            ItemRow(self.items_frame, self, item, index, len(items), item.id == showing).grid(
                row=index, column=0, sticky="ew", pady=1)
        if not items:
            ctk.CTkLabel(self.items_frame, text="No wallpapers yet - use “Add wallpapers”.", font=theme.font(13),
                         text_color=theme.TEXT_MUTED).grid(row=0, column=0, pady=40)
        self._update_status()

    def _is_playing(self, playlist: Playlist | None) -> bool:
        active = self.player.active_playlist()
        return bool(playlist and active and active.id == playlist.id)

    def _update_status(self) -> None:
        playlist = self._selected()
        if playlist is None:
            return
        playing = self._is_playing(playlist)
        self.play_btn.configure(text="Stop" if playing else "Play",
                                image=icons.icon("stop" if playing else "play", 16, theme.ACCENT_TEXT))
        if playing:
            remaining = self.player.seconds_until_change() or 0
            self.status.configure(text=f"Playing on your desktop  ·  next change in {format_countdown(remaining)}",
                                  text_color=theme.ACCENT)
        else:
            self.status.configure(text="Not playing", text_color=theme.TEXT_MUTED)

    # -- actions -------------------------------------------------------------------------

    def new_playlist(self) -> None:
        dialog = ctk.CTkInputDialog(title="New playlist", text="Name of the new playlist:")
        name = (dialog.get_input() or "").strip()
        if name:
            self.selected_id = self.db.create_playlist(name)
            self.refresh()

    def _rename(self) -> None:
        playlist = self._selected()
        name = self.name_entry.get().strip()
        if playlist and name and name != playlist.name:
            self.db.update_playlist(playlist.id, name=name)
            self.refresh()

    def _change_mode(self, label: str) -> None:
        if self.selected_id:
            self.db.update_playlist(self.selected_id, mode=MODES[label])
            self.refresh()

    def _change_interval(self) -> None:
        playlist = self._selected()
        if not playlist:
            return
        try:
            seconds = int(float(self.interval.get().replace(",", ".")) * UNITS[self.unit.get()])
        except ValueError:
            seconds = 0
        if seconds < MIN_INTERVAL_SECONDS:
            self.status.configure(text="The interval must be at least 1 minute.", text_color=theme.DANGER)
            return
        if seconds != playlist.interval_seconds:
            self.db.update_playlist(playlist.id, interval_seconds=seconds)
            self.refresh()

    def _toggle_play(self) -> None:
        playlist = self._selected()
        if not playlist:
            return
        if self._is_playing(playlist):
            self.player.stop()
            self.refresh()
            return
        self.play_btn.configure(state="disabled")

        def finish(exc=None):
            self.play_btn.configure(state="normal")
            self.refresh()
            if exc:
                messagebox.showerror("Could not play the playlist", str(exc), parent=self)

        run_in_thread(self, lambda: self.player.start(playlist.id), on_done=lambda _r: finish(), on_error=finish)

    def _delete(self) -> None:
        playlist = self._selected()
        if playlist and messagebox.askyesno("Delete playlist", f"Delete the playlist “{playlist.name}”?\n\n"
                                            "The wallpapers stay in your library.", parent=self):
            if self._is_playing(playlist):
                self.player.stop()
            self.db.delete_playlist(playlist.id)
            self.selected_id = None
            self.refresh()

    def _add_items(self) -> None:
        if self.selected_id:
            AddWallpapersDialog(self, self.selected_id)

    def move_item(self, item: MediaItem, delta: int) -> None:
        self.db.move_in_playlist(self.selected_id, item.id, delta)
        self.refresh()

    def remove_item(self, item: MediaItem) -> None:
        self.db.remove_from_playlist(self.selected_id, item.id)
        self.refresh()
