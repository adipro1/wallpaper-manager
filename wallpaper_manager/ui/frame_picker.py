"""Window for choosing a frame of a video (e.g. to use as the lock screen)."""

import logging
import os
import threading
import tkinter as tk
from tkinter import filedialog, messagebox

import customtkinter as ctk
from PIL import Image, ImageTk

from .. import imaging, media
from ..database import MediaItem
from ..lockscreen import format_timestamp
from . import theme
from .widgets import button, run_in_thread

log = logging.getLogger(__name__)


class FramePicker(ctk.CTkToplevel):
    """Scrub through a video, step frame by frame, then use or save the frame shown.

    Decoding a frame can take a moment for large videos, so it happens on a
    worker thread; while one frame is decoding, only the newest request is kept.
    """

    def __init__(self, master, app, item: MediaItem, on_applied=None):
        super().__init__(master, fg_color=theme.WINDOW_BG)
        self.app = app
        self.item = item
        self.on_applied = on_applied
        self.duration = max(item.duration or 0.0, 0.0)
        self.fps = 30.0
        self.position = min(1.0, self.duration / 2)
        self.frame: tuple[float, Image.Image] | None = None  # (time, full-resolution frame)
        self._lock = threading.Lock()
        self._wanted: float | None = None
        self._decoding = False
        self._frame_changed = False  # set by the worker, picked up by _poll on the Tk thread
        self._ready = False
        self._applying = False
        self._photo = None

        self.title(f"Choose a frame – {item.filename}")
        self.geometry("960x660")
        self.minsize(640, 480)
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(0, weight=1)

        self.picture = tk.Label(self, bg=theme.PREVIEW_BG, fg="#9A9A9A", bd=0, text="Loading…",
                                font=(theme.FONT_FAMILY, 11))
        self.picture.grid(row=0, column=0, sticky="nsew", padx=16, pady=(16, 8))
        self.picture.bind("<Configure>", lambda _e: self._redraw())

        bar = ctk.CTkFrame(self, fg_color="transparent")
        bar.grid(row=1, column=0, sticky="ew", padx=16)
        bar.grid_columnconfigure(1, weight=1)
        self.time_label = ctk.CTkLabel(bar, text="", font=theme.font(13), text_color=theme.TEXT, width=150,
                                       anchor="w")
        self.time_label.grid(row=0, column=0)
        self.slider = ctk.CTkSlider(bar, from_=0, to=max(self.duration, 0.01), command=self._on_slide,
                                    progress_color=theme.ACCENT, button_color=theme.ACCENT,
                                    button_hover_color=theme.ACCENT_HOVER)
        self.slider.grid(row=0, column=1, sticky="ew", padx=8)
        self.slider.set(self.position)

        controls = ctk.CTkFrame(self, fg_color="transparent")
        controls.grid(row=2, column=0, sticky="ew", padx=16, pady=(8, 16))
        steps = [("−1 s", lambda: self.step(-1.0)), ("−1 frame", lambda: self.step(-1 / self.fps)),
                 ("+1 frame", lambda: self.step(1 / self.fps)), ("+1 s", lambda: self.step(1.0))]
        for text, command in steps:
            button(controls, text, command, width=84, height=32).pack(side="left", padx=(0, 6))
        self.apply_btn = button(controls, "Set as lock screen", self._apply, icon="lock", style="primary")
        self.apply_btn.pack(side="right")
        button(controls, "Save frame…", self._save, icon="folder").pack(side="right", padx=8)

        self.bind("<Left>", lambda _e: self.step(-1 / self.fps))
        self.bind("<Right>", lambda _e: self.step(1 / self.fps))
        self.bind("<Escape>", lambda _e: self.destroy())
        self.after(150, self._bring_to_front)
        run_in_thread(self, lambda: media.video_fps(item.path), on_done=self._got_fps, on_error=lambda e: None)
        self.request(self.position)
        self._poll()

    def _bring_to_front(self) -> None:
        self.lift()
        self.focus_force()

    def _got_fps(self, fps: float) -> None:
        self.fps = fps or 30.0

    # -- choosing the time ---------------------------------------------------------

    def _on_slide(self, value: float) -> None:
        self.request(float(value), move_slider=False)

    def step(self, seconds: float) -> None:
        self.request(self.position + seconds)

    def request(self, seconds: float, move_slider: bool = True) -> None:
        last_frame = max(self.duration - 1 / self.fps, 0.0)
        self.position = min(max(seconds, 0.0), last_frame)
        if move_slider:
            self.slider.set(self.position)
        self.time_label.configure(text=f"{format_timestamp(self.position)} / {format_timestamp(self.duration)}")
        with self._lock:
            self._wanted = self.position
            if self._decoding:
                return  # the running worker picks up the newest request when it finishes
            self._decoding = True
        threading.Thread(target=self._decode_loop, daemon=True).start()

    def _decode_loop(self) -> None:
        while True:
            with self._lock:
                wanted, self._wanted = self._wanted, None
                if wanted is None:
                    self._decoding = False
                    break
            try:
                image = media.read_video_frame(self.item.path, wanted)
                self.frame = (wanted, image)
            except Exception:  # noqa: BLE001 - shown as "Loading…" / disabled button instead
                log.warning("Could not decode frame at %.2fs", wanted, exc_info=True)
                self.frame = None
            self._frame_changed = True

    def _poll(self) -> None:
        """Tk is not thread-safe, so the UI checks for newly decoded frames itself."""
        if self._frame_changed:
            self._frame_changed = False
            self._redraw()
        ready = self.frame is not None and not self._decoding and not self._applying
        if ready != self._ready:
            self._ready = ready
            self.apply_btn.configure(state="normal" if ready else "disabled")
        self.after(40, self._poll)

    def _redraw(self) -> None:
        if not self.frame:
            self.picture.configure(image="", text="Loading…")
            return
        box = (max(self.picture.winfo_width(), 32), max(self.picture.winfo_height(), 32))
        self._photo = ImageTk.PhotoImage(imaging.contain(self.frame[1], box))
        self.picture.configure(image=self._photo, text="")

    # -- actions -----------------------------------------------------------------------

    def _apply(self) -> None:
        seconds = self.frame[0] if self.frame else self.position
        self._applying = True
        self.apply_btn.configure(text="Applying…")

        def done(_result):
            if self.on_applied:
                self.on_applied()
            self.destroy()

        def failed(exc):
            self._applying = False
            self.apply_btn.configure(text="Set as lock screen")
            messagebox.showerror("Could not set the lock screen", str(exc), parent=self)

        run_in_thread(self, lambda: self.app.lockscreen.set_video_frame(self.item, seconds),
                      on_done=done, on_error=failed)

    def _save(self) -> None:
        if not self.frame:
            return
        seconds, image = self.frame
        stem = os.path.splitext(self.item.filename)[0]
        path = filedialog.asksaveasfilename(
            parent=self, title="Save frame", defaultextension=".png",
            initialfile=f"{stem} {format_timestamp(seconds).replace(':', '-')}.png",
            filetypes=[("PNG image", "*.png"), ("JPEG image", "*.jpg")])
        if path:
            image.save(path, quality=95)
            log.info("Saved frame %.2fs of %s to %s", seconds, self.item.path, path)
