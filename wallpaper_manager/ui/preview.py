"""In-app previews for images, animated GIFs and videos.

Videos are decoded with PyAV on a background thread. Audio is never decoded,
so previews are always silent. Frames are scaled down to the preview size by
FFmpeg (cheap), and only the newest frame is handed to the Tk thread, which
draws it into a reusable PhotoImage.
"""

import logging
import os
import threading
import time
import tkinter as tk

import av
import customtkinter as ctk
from PIL import Image, ImageSequence, ImageTk

from .. import imaging
from ..database import MediaItem
from . import icons, theme

log = logging.getLogger(__name__)

FRAME_POLL_MS = 15
MAX_GIF_FRAMES = 300


def fit_size(src_w: int, src_h: int, box_w: int, box_h: int) -> tuple[int, int]:
    """Largest size with the source aspect ratio that fits inside the box (even numbers for FFmpeg)."""
    scale = min(box_w / src_w, box_h / src_h)
    return max(2, int(src_w * scale) // 2 * 2), max(2, int(src_h * scale) // 2 * 2)


class VideoDecoder(threading.Thread):
    """Decodes a video in a loop on a background thread, keeping only the newest frame."""

    def __init__(self, path: str):
        super().__init__(daemon=True, name="preview-decoder")
        self.path = path
        self.target_size = (640, 360)  # set by the UI thread whenever the preview is resized
        self.error: Exception | None = None
        self._stop_event = threading.Event()
        self._paused = threading.Event()
        self._lock = threading.Lock()
        self._frame: Image.Image | None = None

    def stop(self) -> None:
        self._stop_event.set()
        self._paused.clear()

    def set_paused(self, paused: bool) -> None:
        if paused:
            self._paused.set()
        else:
            self._paused.clear()

    @property
    def paused(self) -> bool:
        return self._paused.is_set()

    def take_frame(self) -> Image.Image | None:
        with self._lock:
            frame, self._frame = self._frame, None
        return frame

    def run(self) -> None:
        try:
            with av.open(self.path) as container:
                stream = container.streams.video[0]
                stream.thread_type = "AUTO"  # let FFmpeg decode on several cores
                while not self._stop_event.is_set():
                    if not self._play_once(container, stream):
                        raise ValueError("The video contains no decodable frames")
                    container.seek(0)  # loop back to the start
        except Exception as exc:  # noqa: BLE001 - shown in the preview instead of crashing
            log.warning("Video preview failed for %s: %s", self.path, exc)
            self.error = exc

    def _play_once(self, container, stream) -> bool:
        """Play the video from the current position to the end, in real time."""
        start_clock = time.perf_counter()
        first_ts = None
        decoded_any = False
        for frame in container.decode(stream):
            if self._stop_event.is_set():
                return True
            decoded_any = True
            if self._paused.is_set():
                paused_at = time.perf_counter()
                while self._paused.is_set() and not self._stop_event.is_set():
                    time.sleep(0.05)
                start_clock += time.perf_counter() - paused_at

            ts = frame.time or 0.0
            if first_ts is None:
                first_ts = ts
            due = start_clock + (ts - first_ts)
            if due - time.perf_counter() < -0.25:
                continue  # we are running behind: drop this frame instead of converting it

            width, height = fit_size(frame.width, frame.height, *self.target_size)
            image = frame.to_image(width=width, height=height)
            delay = due - time.perf_counter()
            if delay > 0:
                self._stop_event.wait(delay)
            with self._lock:
                self._frame = image
        return decoded_any


class MediaPreview(ctk.CTkFrame):
    """A black box that shows an image, animates a GIF, or plays a video (muted, looping)."""

    def __init__(self, master, show_controls: bool = True, **kwargs):
        kwargs.setdefault("fg_color", theme.PREVIEW_BG)
        kwargs.setdefault("corner_radius", 10)
        super().__init__(master, **kwargs)
        self.pack_propagate(False)
        self.grid_propagate(False)

        # A plain tk.Label is used for the picture: it lets us update one
        # PhotoImage in place for every video frame, which is much cheaper
        # than creating a new CTkImage each time.
        self._picture = tk.Label(self, bg=theme.PREVIEW_BG, fg="#9A9A9A", bd=0, highlightthickness=0,
                                font=(theme.FONT_FAMILY, 11))
        self._picture.pack(fill="both", expand=True, padx=4, pady=4)
        self._picture.bind("<Configure>", self._on_resize)

        self._photo: ImageTk.PhotoImage | None = None
        self._image: Image.Image | None = None  # still image being shown
        self._gif_frames: list[tuple[Image.Image, int]] = []
        self._gif_index = 0
        self._decoder: VideoDecoder | None = None
        self._job: str | None = None
        self._resize_job: str | None = None

        self._controls = None
        if show_controls:
            self._controls = ctk.CTkButton(
                self, text="", width=34, height=34, corner_radius=0, fg_color="#1E1E1E", hover_color="#3A3A3A",
                image=icons.icon("pause", 14, "#FFFFFF"), command=self.toggle_pause)
        self.show_message("")

    # -- public API ------------------------------------------------------------

    def show(self, item: MediaItem | None) -> None:
        self.stop()
        if item is None:
            self.show_message("")
            return
        if not os.path.exists(item.path):
            self.show_message("File not found")
            return
        try:
            if item.is_video:
                self._start_video(item.path)
            else:
                self._start_image(item.path)
        except Exception as exc:  # noqa: BLE001
            log.warning("Preview failed for %s", item.path, exc_info=True)
            self.show_message(f"Can't preview this file\n{exc}")

    def stop(self) -> None:
        """Stop any playback and release the file."""
        if self._job:
            self.after_cancel(self._job)
            self._job = None
        if self._decoder:
            self._decoder.stop()
            self._decoder = None
        self._gif_frames = []
        self._image = None
        if self._controls:
            self._controls.place_forget()

    def toggle_pause(self) -> None:
        if self._decoder:
            self._decoder.set_paused(not self._decoder.paused)
            paused = self._decoder.paused
        elif self._gif_frames:
            paused = self._job is not None
            if paused:
                self.after_cancel(self._job)
                self._job = None
            else:
                self._next_gif_frame()
        else:
            return
        self._controls.configure(image=icons.icon("play" if paused else "pause", 14, "#FFFFFF"))

    def show_message(self, text: str) -> None:
        self._photo = None
        self._picture.configure(image="", text=text)

    def destroy(self):
        self.stop()
        super().destroy()

    # -- drawing ---------------------------------------------------------------

    def _box_size(self) -> tuple[int, int]:
        return max(self._picture.winfo_width(), 16), max(self._picture.winfo_height(), 16)

    def _show_frame(self, img: Image.Image) -> None:
        if self._photo is not None and (self._photo.width(), self._photo.height()) == img.size:
            self._photo.paste(img)  # same size: update the existing image in place
        else:
            self._photo = ImageTk.PhotoImage(img)
            self._picture.configure(image=self._photo, text="")

    def _show_controls(self) -> None:
        if self._controls:
            self._controls.configure(image=icons.icon("pause", 14, "#FFFFFF"))
            self._controls.place(relx=1.0, rely=1.0, x=-12, y=-12, anchor="se")

    def _on_resize(self, _event=None) -> None:
        if self._resize_job:
            self.after_cancel(self._resize_job)
        self._resize_job = self.after(60, self._apply_resize)

    def _apply_resize(self) -> None:
        self._resize_job = None
        if self._decoder:
            self._decoder.target_size = self._box_size()
        elif self._image is not None:
            self._show_frame(imaging.contain(self._image, self._box_size()))

    # -- images and GIFs ---------------------------------------------------------

    def _start_image(self, path: str) -> None:
        with Image.open(path) as img:
            if getattr(img, "is_animated", False):
                for frame in ImageSequence.Iterator(img):
                    self._gif_frames.append((frame.convert("RGB"), max(int(frame.info.get("duration", 100)), 20)))
                    if len(self._gif_frames) >= MAX_GIF_FRAMES:
                        break
        if len(self._gif_frames) > 1:
            self._gif_index = 0
            self._show_controls()
            self._next_gif_frame()
            return
        self._gif_frames = []
        # Keep at most a 2560px copy in memory; that is plenty for any preview size.
        self._image = imaging.contain(imaging.open_rgb(path, draft_size=(2560, 1440)), (2560, 1440))
        self._show_frame(imaging.contain(self._image, self._box_size()))

    def _next_gif_frame(self) -> None:
        frame, duration = self._gif_frames[self._gif_index]
        self._show_frame(imaging.contain(frame, self._box_size()))
        self._gif_index = (self._gif_index + 1) % len(self._gif_frames)
        self._job = self.after(duration, self._next_gif_frame)

    # -- video ---------------------------------------------------------------------

    def _start_video(self, path: str) -> None:
        self.show_message("Loading…")
        self._decoder = VideoDecoder(path)
        self._decoder.target_size = self._box_size()
        self._decoder.start()
        self._show_controls()
        self._poll_video()

    def _poll_video(self) -> None:
        decoder = self._decoder
        if decoder is None:
            return
        if decoder.error:
            self.stop()
            self.show_message(f"Can't play this video\n{decoder.error}")
            return
        frame = decoder.take_frame()
        if frame is not None:
            self._show_frame(frame)
        self._job = self.after(FRAME_POLL_MS, self._poll_video)


class PreviewWindow(ctk.CTkToplevel):
    """Large preview in its own window. Esc closes it."""

    def __init__(self, master, item: MediaItem):
        super().__init__(master, fg_color=theme.WINDOW_BG)
        self.title(f"Preview – {item.filename}")
        self.geometry("1100x660")
        self.minsize(480, 300)
        self.preview = MediaPreview(self, corner_radius=0)
        self.preview.pack(fill="both", expand=True)
        self.bind("<Escape>", lambda _e: self.close())
        self.protocol("WM_DELETE_WINDOW", self.close)
        # Wait until the window has its real size before loading, so the first
        # frame is rendered at the right resolution.
        self.after(120, lambda: self.preview.show(item))
        self.after(150, self._bring_to_front)

    def _bring_to_front(self) -> None:
        self.lift()
        self.focus_force()

    def close(self) -> None:
        self.preview.stop()
        self.destroy()
