"""Desktop page: shows the current desktop wallpaper and lets the user pick a new one."""

import logging
import os
from tkinter import messagebox

import customtkinter as ctk

from ... import imaging, media, winapi
from ...database import MediaItem
from ...desktop_host import DesktopLayerError
from ...live_wallpaper import LibmpvMissing
from ...wallpaper import IMAGE_STYLES, VIDEO_STYLES
from .. import icons, theme
from ..chooser import MediaChooser
from ..widgets import (
    BasePage,
    Card,
    PageHeader,
    button,
    run_in_thread,
    section_label,
    segmented,
)

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

        header = PageHeader(self, "Desktop", "Choose a picture or a live video for your desktop.")
        header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 16))
        self.restore_btn = button(header.actions, "Restore original", self._restore_original, icon="refresh")
        self.restore_btn.pack()

        # Current wallpaper
        current = Card(self)
        current.grid(row=1, column=0, sticky="ew", padx=32, pady=(0, 16))
        current.grid_columnconfigure(1, weight=1)
        self.preview = ctk.CTkLabel(current, text="", width=PREVIEW_SIZE[0], height=PREVIEW_SIZE[1])
        self.preview.grid(row=0, column=0, rowspan=5, padx=16, pady=16)
        self.heading = section_label(current, "Current wallpaper")
        self.heading.grid(row=0, column=1, sticky="w", padx=(4, 16), pady=(18, 0))
        self.name = ctk.CTkLabel(current, text="", font=theme.font(13), text_color=theme.TEXT_MUTED, anchor="w",
                                 justify="left")
        self.name.grid(row=1, column=1, sticky="nw", padx=(4, 16))

        # Position: one control for images, a smaller one for videos (only one is shown).
        position = ctk.CTkFrame(current, fg_color="transparent")
        position.grid(row=2, column=1, sticky="nw", padx=(4, 16), pady=(12, 0))
        ctk.CTkLabel(position, text="Position", font=theme.font(13), text_color=theme.TEXT).grid(
            row=0, column=0, sticky="w", padx=(0, 12))
        self.image_style = segmented(position, [STYLE_LABELS[s] for s in IMAGE_STYLES], self._change_image_style,
                                     height=32)
        self.video_style = segmented(position, [STYLE_LABELS[s] for s in VIDEO_STYLES], self._change_video_style,
                                     height=32)

        # Live video controls
        self.video_controls = ctk.CTkFrame(current, fg_color="transparent")
        self.video_controls.grid(row=3, column=1, sticky="nw", padx=(4, 16), pady=(10, 0))
        self.pause_btn = button(self.video_controls, "Pause", self._toggle_pause, icon="pause", width=110, height=32)
        self.pause_btn.pack(side="left")
        button(self.video_controls, "Stop", self._stop_video, icon="stop", width=100, height=32).pack(
            side="left", padx=8)
        self.sound = ctk.CTkSwitch(self.video_controls, text="Sound", font=theme.font(13),
                                   progress_color=theme.ACCENT, command=self._toggle_sound)
        self.sound.pack(side="left", padx=(8, 0))

        self.status = ctk.CTkLabel(current, text="", font=theme.font(12), text_color=theme.TEXT_MUTED, anchor="w",
                                   justify="left", wraplength=460)
        self.status.grid(row=4, column=1, sticky="sw", padx=(4, 16), pady=(8, 16))
        current.grid_rowconfigure(4, weight=1)

        # Chooser
        chooser_card = Card(self)
        chooser_card.grid(row=2, column=0, sticky="nsew", padx=32, pady=(0, 20))
        top = ctk.CTkFrame(chooser_card, fg_color="transparent")
        top.pack(fill="x", padx=16, pady=(14, 6))
        section_label(top, "Choose a wallpaper").pack(side="left")
        button(top, "Random", self._random, icon="refresh", height=30).pack(side="right")
        self.chooser = MediaChooser(chooser_card, app, self._pick, allow_video=True)
        self.chooser.pack(fill="both", expand=True, padx=12, pady=(0, 12))

    def on_show(self) -> None:
        self.refresh_current()
        self.chooser.refresh()

    # -- current wallpaper ---------------------------------------------------------

    def refresh_current(self) -> None:
        state = self.service.state
        item = self.service.current_item()
        is_video = state.kind == "video" and item is not None
        self.chooser.set_current(item.id if item else None)
        self.restore_btn.configure(state="normal" if self.service.has_original() else "disabled")

        if is_video:
            self.image_style.grid_remove()
            self.video_style.grid(row=0, column=1, sticky="w")
            self.video_style.set(STYLE_LABELS[state.style])
            self.video_controls.grid()
            paused = self.service.video_paused
            self.pause_btn.configure(text="Resume" if paused else "Pause",
                                     image=icons.icon("play" if paused else "pause", 16, theme.TEXT))
            self.sound.select() if not self.service.muted else self.sound.deselect()
        else:
            self.video_style.grid_remove()
            self.video_controls.grid_remove()
            self.image_style.grid(row=0, column=1, sticky="w")
            # The position can only be changed for wallpapers this app has set.
            managed = state.kind == "image" and item is not None
            self.image_style.set(STYLE_LABELS[state.style if managed else winapi.get_wallpaper_style()])
            self.image_style.configure(state="normal" if managed else "disabled")

        if item:
            self.name.configure(text=f"{item.filename}\n{imaging.shorten_middle(os.path.dirname(item.path), 44)}")
            self._show_preview(item.path, video=item.is_video)
        else:
            path = winapi.get_desktop_wallpaper()
            self.name.configure(text="Not set by Wallpaper Manager" if path else "No wallpaper picture")
            self._show_preview(path)
        self._update_status()

    def _update_status(self) -> None:
        state = self.service.state
        if state.kind != "video":
            self.heading.configure(text="Current wallpaper")
            self.status.configure(text=self._playlist_text(), text_color=theme.TEXT_MUTED)
            return
        if not self.service.video_active:
            self.heading.configure(text="Live wallpaper")
            self.status.configure(text="Not playing. Pick the video again to start it.", text_color=theme.TEXT_MUTED)
            return
        self.heading.configure(text="Live wallpaper  ·  " + ("Paused" if self.service.video_paused else "Playing"))
        others = self.service.competing_apps()
        reason = self.service.auto_pause_reason
        if reason:
            why = {"fullscreen": "a fullscreen app is running", "battery": "the PC is on battery",
                   "covered": "a maximized window covers the desktop", "locked": "the PC is locked",
                   "screen_off": "the screen is off"}.get(reason, reason)
            self.status.configure(text=f"Paused automatically because {why}. It resumes by itself.",
                                  text_color=theme.TEXT_MUTED)
        elif others:
            self.status.configure(
                text=f"{', '.join(others)} is also showing a live wallpaper and may cover this one. "
                     "Close or pause it to see your video.", text_color=theme.DANGER)
        else:
            self.status.configure(text=self._playlist_text(), text_color=theme.TEXT_MUTED)

    def _playlist_text(self) -> str:
        playlist = self.app.playlists.active_playlist()
        if not playlist:
            return ""
        minutes = round((self.app.playlists.seconds_until_change() or 0) / 60)
        return f"Playlist “{playlist.name}” is running  ·  next change in about {minutes} min"

    def _show_preview(self, path: str | None, video: bool = False) -> None:
        if not path or not os.path.exists(path):
            self._preview_key = None
            self.preview.configure(image=None, text="No preview", text_color=theme.TEXT_MUTED)
            return
        key = (path, os.path.getmtime(path))
        if key == self._preview_key:
            return
        size = (PREVIEW_SIZE[0] * 2, PREVIEW_SIZE[1] * 2)
        try:
            if video:
                img = media.read_video_frame(path, 1.0)
            else:
                img = imaging.open_rgb(path, draft_size=size)
            img = imaging.rounded(imaging.cover(img, size), 20)
        except Exception:  # noqa: BLE001 - a broken preview must not break the page
            log.warning("Could not preview %s", path, exc_info=True)
            return
        self._preview_key = key
        self._preview_image = ctk.CTkImage(img, img, PREVIEW_SIZE)
        self.preview.configure(image=self._preview_image, text="")

    # -- actions -----------------------------------------------------------------------

    def _run(self, description: str, func, done_message: str | None = None) -> None:
        """Run a wallpaper change in the background (applying a large image or starting
        the video engine can take a moment)."""
        if self.busy:
            return
        self.busy = True
        self.status.configure(text=description, text_color=theme.TEXT_MUTED)

        def done(_result):
            self.busy = False
            self.refresh_current()
            if done_message:
                self.app.toast(done_message, "success")
            # Other wallpaper apps are only detectable once our window exists.
            self.after(1500, self._update_status)

        def failed(exc):
            self.busy = False
            self.refresh_current()
            if isinstance(exc, LibmpvMissing):
                title = "Live wallpapers unavailable"
            elif isinstance(exc, DesktopLayerError):
                title = "Live wallpapers not supported here"
            else:
                title = "Could not change the wallpaper"
            messagebox.showerror(title, str(exc), parent=self)

        run_in_thread(self, func, on_done=done, on_error=failed)

    def _pick(self, item: MediaItem) -> None:
        self.apply(item)

    def apply(self, item: MediaItem) -> None:
        """Set a library item (image or video) as the desktop wallpaper. Used by other pages too.
        Choosing a wallpaper by hand stops a running playlist, like Windows' own slideshow."""
        self.app.playlists.stop()
        kind = "Live wallpaper" if item.is_video else "Desktop wallpaper"
        self._run(f"Applying {item.filename}…", lambda: self.service.set(item), f"{kind} set: {item.filename}")

    def _change_image_style(self, label: str) -> None:
        style = next(s for s, lbl in STYLE_LABELS.items() if lbl == label)
        self._run("Updating position…", lambda: self.service.set_image_style(style))

    def _change_video_style(self, label: str) -> None:
        self.service.set_video_style(next(s for s, lbl in STYLE_LABELS.items() if lbl == label))

    def _toggle_pause(self) -> None:
        if self.service.video_paused:
            self.service.resume_video()
        else:
            self.service.pause_video()
        self.refresh_current()

    def _toggle_sound(self) -> None:
        self.service.set_muted(not self.sound.get())

    def _stop_video(self) -> None:
        self._run("Stopping…", self.service.stop_video)

    def _random(self) -> None:
        current = self.service.current_item()
        item = self.chooser.random_item(exclude_id=current.id if current else None)
        if item:
            self.apply(item)

    def _restore_original(self) -> None:
        if messagebox.askyesno("Restore original wallpaper",
                               "Go back to the wallpaper you had before using Wallpaper Manager?", parent=self):
            self._run("Restoring…", self.service.restore_original, "Original wallpaper restored")
