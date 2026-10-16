"""Lock Screen page: set an image, or a frame from a video, as the Windows lock screen."""

import logging
import os
from tkinter import messagebox

import customtkinter as ctk

from ... import imaging
from ...database import MediaItem
from ...lockscreen import format_timestamp, spotlight_enabled
from .. import theme
from ..chooser import MediaChooser
from ..frame_picker import FramePicker
from ..widgets import BasePage, Card, PageHeader, button, run_in_thread, section_label

log = logging.getLogger(__name__)

PREVIEW_SIZE = (336, 189)


class LockScreenPage(BasePage):
    title = "Lock Screen"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.service = app.lockscreen
        self.busy = False
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1)

        header = PageHeader(self, "Lock Screen",
                            "Windows only shows pictures on the lock screen - for videos, pick a frame.")
        header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 16))
        self.restore_btn = button(header.actions, "Restore original", self._restore_original, icon="refresh")
        self.restore_btn.pack()

        current = Card(self)
        current.grid(row=1, column=0, sticky="ew", padx=32, pady=(0, 16))
        current.grid_columnconfigure(1, weight=1)
        self.preview = ctk.CTkLabel(current, text="", width=PREVIEW_SIZE[0], height=PREVIEW_SIZE[1])
        self.preview.grid(row=0, column=0, rowspan=3, padx=16, pady=16)
        section_label(current, "Current lock screen").grid(row=0, column=1, sticky="w", padx=(4, 16), pady=(18, 0))
        self.name = ctk.CTkLabel(current, text="", font=theme.font(13), text_color=theme.TEXT_MUTED, anchor="w",
                                 justify="left")
        self.name.grid(row=1, column=1, sticky="nw", padx=(4, 16))
        self.status = ctk.CTkLabel(current, text="", font=theme.font(12), text_color=theme.TEXT_MUTED, anchor="w")
        self.status.grid(row=2, column=1, sticky="sw", padx=(4, 16), pady=(0, 16))
        current.grid_rowconfigure(2, weight=1)

        chooser_card = Card(self)
        chooser_card.grid(row=2, column=0, sticky="nsew", padx=32, pady=(0, 20))
        top = ctk.CTkFrame(chooser_card, fg_color="transparent")
        top.pack(fill="x", padx=16, pady=(14, 6))
        section_label(top, "Choose an image or a video frame").pack(side="left")
        self.chooser = MediaChooser(chooser_card, app, self.apply, allow_video=True)
        self.chooser.pack(fill="both", expand=True, padx=12, pady=(0, 12))

    def on_show(self) -> None:
        self.refresh_current()
        self.chooser.refresh()

    def refresh_current(self) -> None:
        state = self.service.state
        item = self.service.current_item()
        self.chooser.set_current(item.id if item else None)
        self.restore_btn.configure(state="normal" if self.service.has_original() else "disabled")
        if item:
            text = item.filename
            if state.get("timestamp") is not None:
                text += f"\nFrame at {format_timestamp(state['timestamp'])}"
        elif spotlight_enabled():
            text = "Windows Spotlight"
        else:
            text = "Not set by Wallpaper Manager"
        self.name.configure(text=text)
        # Reading the image from Windows takes a moment, so do it in the background.
        run_in_thread(self, self._load_preview, on_done=self._show_preview,
                      on_error=lambda exc: self.preview.configure(image=None, text="No preview"))

    def _load_preview(self):
        size = (PREVIEW_SIZE[0] * 2, PREVIEW_SIZE[1] * 2)
        return imaging.rounded(imaging.cover(self.service.current_image().convert("RGB"), size), 20)

    def _show_preview(self, img) -> None:
        self._preview_image = ctk.CTkImage(img, img, PREVIEW_SIZE)
        self.preview.configure(image=self._preview_image, text="")

    # -- actions ---------------------------------------------------------------------

    def apply(self, item: MediaItem) -> None:
        """Images are applied directly; videos open the frame picker. Used by other pages too."""
        if item.is_video:
            if not os.path.exists(item.path):
                messagebox.showerror("File not found", item.path, parent=self)
                return
            FramePicker(self, self.app, item, on_applied=self._frame_applied)
        else:
            self._run(f"Applying {item.filename}…", lambda: self.service.set_image(item),
                      f"Lock screen set: {item.filename}")

    def _frame_applied(self) -> None:
        self.refresh_current()
        self.app.toast("Lock screen set from the chosen video frame", "success")

    def _run(self, description: str, func, done_message: str | None = None) -> None:
        if self.busy:
            return
        self.busy = True
        self.status.configure(text=description)

        def finish(exc=None):
            self.busy = False
            self.status.configure(text="")
            self.refresh_current()
            if exc:
                messagebox.showerror("Could not change the lock screen", str(exc), parent=self)
            elif done_message:
                self.app.toast(done_message, "success")

        run_in_thread(self, func, on_done=lambda _r: finish(), on_error=finish)

    def _restore_original(self) -> None:
        if messagebox.askyesno("Restore original lock screen",
                               "Go back to the lock screen you had before using Wallpaper Manager?", parent=self):
            self._run("Restoring…", self.service.restore_original, "Original lock screen restored")
