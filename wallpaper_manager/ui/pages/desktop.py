"""Desktop page: shows the current desktop wallpaper and lets the user pick a new one."""

import logging
import os
from tkinter import messagebox

import customtkinter as ctk

from ... import imaging, winapi
from ...database import MediaItem
from ...wallpaper import IMAGE_STYLES
from .. import theme
from ..chooser import MediaChooser
from ..widgets import BasePage, Card, PageHeader, button, run_in_thread, section_label, segmented

log = logging.getLogger(__name__)

PREVIEW_SIZE = (336, 189)
STYLE_LABELS = {"fill": "Fill", "fit": "Fit", "stretch": "Stretch", "center": "Center", "tile": "Tile"}


class DesktopPage(BasePage):
    title = "Desktop"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.service = app.wallpapers
        self.busy = False
        self._preview_key = None
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1)

        header = PageHeader(self, "Desktop", "Choose the picture shown on your desktop.")
        header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 16))
        self.restore_btn = button(header.actions, "Restore original", self._restore_original, icon="refresh")
        self.restore_btn.pack()

        # Current wallpaper
        current = Card(self)
        current.grid(row=1, column=0, sticky="ew", padx=32, pady=(0, 16))
        current.grid_columnconfigure(1, weight=1)
        self.preview = ctk.CTkLabel(current, text="", width=PREVIEW_SIZE[0], height=PREVIEW_SIZE[1])
        self.preview.grid(row=0, column=0, rowspan=4, padx=16, pady=16)
        section_label(current, "Current wallpaper").grid(row=0, column=1, sticky="w", padx=(4, 16), pady=(18, 0))
        self.name = ctk.CTkLabel(current, text="", font=theme.font(13), text_color=theme.TEXT_MUTED, anchor="w",
                                 justify="left")
        self.name.grid(row=1, column=1, sticky="nw", padx=(4, 16))

        self.controls = ctk.CTkFrame(current, fg_color="transparent")
        self.controls.grid(row=2, column=1, sticky="nw", padx=(4, 16), pady=(12, 0))
        ctk.CTkLabel(self.controls, text="Position", font=theme.font(13), text_color=theme.TEXT).grid(
            row=0, column=0, sticky="w", padx=(0, 12))
        self.style = segmented(self.controls, [STYLE_LABELS[s] for s in IMAGE_STYLES], self._change_style,
                               height=32)
        self.style.grid(row=0, column=1, sticky="w")
        self.status = ctk.CTkLabel(current, text="", font=theme.font(12), text_color=theme.TEXT_MUTED, anchor="w")
        self.status.grid(row=3, column=1, sticky="sw", padx=(4, 16), pady=(0, 16))
        current.grid_rowconfigure(3, weight=1)

        # Chooser
        chooser_card = Card(self)
        chooser_card.grid(row=2, column=0, sticky="nsew", padx=32, pady=(0, 20))
        top = ctk.CTkFrame(chooser_card, fg_color="transparent")
        top.pack(fill="x", padx=16, pady=(14, 6))
        section_label(top, "Choose a wallpaper").pack(side="left")
        button(top, "Random", self._random, icon="refresh", height=30).pack(side="right")
        self.chooser = MediaChooser(chooser_card, app, self._pick, allow_video=False)
        self.chooser.pack(fill="both", expand=True, padx=12, pady=(0, 12))

    def on_show(self) -> None:
        self.refresh_current()
        self.chooser.refresh()

    # -- current wallpaper ---------------------------------------------------------

    def refresh_current(self) -> None:
        state = self.service.state
        item = self.service.current_item()
        self.chooser.set_current(item.id if item else None)
        self.restore_btn.configure(state="normal" if self.service.has_original() else "disabled")
        # The position can only be changed for wallpapers this app has set.
        managed = state.kind == "image" and item is not None
        self.style.set(STYLE_LABELS[state.style if managed else winapi.get_wallpaper_style()])
        self.style.configure(state="normal" if managed else "disabled")

        if item:
            self.name.configure(text=f"{item.filename}\n{imaging.shorten_middle(os.path.dirname(item.path), 44)}")
            self._show_preview(item.path)
        else:
            path = winapi.get_desktop_wallpaper()
            self.name.configure(text="Not set by Wallpaper Manager" if path else "No wallpaper picture")
            self._show_preview(path)
        if state.kind is None:
            self.status.configure(text="")

    def _show_preview(self, path: str | None) -> None:
        if not path or not os.path.exists(path):
            self._preview_key = None
            self.preview.configure(image=None, text="No preview", text_color=theme.TEXT_MUTED)
            return
        key = (path, os.path.getmtime(path))
        if key == self._preview_key:
            return
        try:
            img = imaging.open_rgb(path, draft_size=(PREVIEW_SIZE[0] * 2, PREVIEW_SIZE[1] * 2))
            img = imaging.rounded(imaging.cover(img, (PREVIEW_SIZE[0] * 2, PREVIEW_SIZE[1] * 2)), 20)
        except OSError:
            log.warning("Could not preview %s", path, exc_info=True)
            return
        self._preview_key = key
        self._preview_image = ctk.CTkImage(img, img, PREVIEW_SIZE)
        self.preview.configure(image=self._preview_image, text="")

    # -- actions -----------------------------------------------------------------------

    def _run(self, description: str, func) -> None:
        """Run a wallpaper change in the background (setting a large image can take a moment)."""
        if self.busy:
            return
        self.busy = True
        self.status.configure(text=description)

        def done(_result):
            self.busy = False
            self.status.configure(text="")
            self.refresh_current()

        def failed(exc):
            self.busy = False
            self.status.configure(text="")
            messagebox.showerror("Could not change the wallpaper", str(exc), parent=self)

        run_in_thread(self, func, on_done=done, on_error=failed)

    def _pick(self, item: MediaItem) -> None:
        self.apply(item)

    def apply(self, item: MediaItem) -> None:
        """Set a library item as the desktop wallpaper (used by other pages too)."""
        self._run(f"Applying {item.filename}…", lambda: self.service.set_image(item))

    def _change_style(self, label: str) -> None:
        style = next(s for s, lbl in STYLE_LABELS.items() if lbl == label)
        self._run("Updating position…", lambda: self.service.set_image_style(style))

    def _random(self) -> None:
        current = self.service.current_item()
        item = self.chooser.random_item(exclude_id=current.id if current else None)
        if item:
            self.apply(item)

    def _restore_original(self) -> None:
        if messagebox.askyesno("Restore original wallpaper",
                               "Go back to the wallpaper you had before using Wallpaper Manager?", parent=self):
            self._run("Restoring…", self.service.restore_original)
