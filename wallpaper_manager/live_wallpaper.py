"""Video playback for live wallpapers, using libmpv inside the desktop host window.

libmpv decodes on the GPU where possible (hwdec) and draws straight into our
window, so a looping 1080p video costs very little CPU. libmpv-2.dll is not
installed with pip; tools/get_libmpv.py downloads it into the bin/ folder.
"""

import logging
import os

from .config import LIBMPV_DIR
from .desktop_host import DesktopHost, window_process_name

log = logging.getLogger(__name__)

LIBMPV_DLL = "libmpv-2.dll"


class LibmpvMissing(RuntimeError):
    pass


def libmpv_available() -> bool:
    return (LIBMPV_DIR / LIBMPV_DLL).exists()


def _import_mpv():
    """Import python-mpv once libmpv-2.dll can be found (it searches PATH)."""
    if not libmpv_available():
        raise LibmpvMissing(
            f"Live wallpapers need {LIBMPV_DLL}, which was not found in {LIBMPV_DIR}.\n\n"
            "Download it by running:\n    python tools/get_libmpv.py")
    if str(LIBMPV_DIR) not in os.environ["PATH"]:
        os.environ["PATH"] = str(LIBMPV_DIR) + os.pathsep + os.environ["PATH"]
    import mpv
    return mpv


def _mpv_log(level: str, component: str, message: str) -> None:
    # mpv's warnings are mostly harmless noise for us; errors are worth keeping.
    log.log({"fatal": logging.CRITICAL, "error": logging.ERROR}.get(level, logging.INFO),
            "mpv/%s: %s", component, message.strip())


class LivePlayer:
    """One looping video behind the desktop icons. Not thread-bound: libmpv is thread-safe."""

    def __init__(self):
        self.host: DesktopHost | None = None
        self.player = None
        self.path: str | None = None

    @property
    def active(self) -> bool:
        return self.player is not None

    @property
    def paused(self) -> bool:
        return bool(self.player and self.player.pause)

    def play(self, path: str, fill: bool = True, muted: bool = True) -> None:
        """Start (or switch to) a video. Switching reuses the window, so there is no flicker."""
        if self.player is None or not self.host.is_alive():
            self._create()
        self.player.panscan = 1.0 if fill else 0.0  # 1.0 = crop to fill the screen, 0 = letterbox
        self.player.mute = muted
        self.player.pause = False
        self.player.loadfile(path)
        self.path = path
        log.info("Live wallpaper playing %s", path)

    def _create(self) -> None:
        self.stop()
        mpv = _import_mpv()
        self.host = DesktopHost()
        self.host.start()
        try:
            self.player = mpv.MPV(
                wid=str(self.host.hwnd),       # draw into our behind-the-icons window
                loop_file="inf",                # loop forever
                mute=True,
                hwdec="auto-safe",              # GPU video decoding when available
                keepaspect=True,
                osc=False, osd_level=0,         # no on-screen controls or messages
                input_default_bindings=False, input_vo_keyboard=False, input_cursor=False,
                cursor_autohide="always",
                ytdl=False,
                log_handler=_mpv_log, loglevel="warn",
            )
        except Exception:
            self.host.close()
            self.host = None
            raise

    def pause(self) -> None:
        if self.player:
            self.player.pause = True

    def resume(self) -> None:
        if self.player:
            self.player.pause = False

    def set_muted(self, muted: bool) -> None:
        if self.player:
            self.player.mute = muted

    def set_fill(self, fill: bool) -> None:
        if self.player:
            self.player.panscan = 1.0 if fill else 0.0

    def is_healthy(self) -> bool:
        """False when Explorer restarted and took our window with it."""
        return self.player is None or (self.host is not None and self.host.is_alive())

    def competing_apps(self) -> list[str]:
        """Names of other apps (e.g. Lively.exe) drawing in the same desktop layer."""
        if not self.host:
            return []
        return sorted({window_process_name(hwnd) for hwnd in self.host.foreign_windows()})

    def fit_to_screen(self) -> None:
        if self.host:
            self.host.fit_to_screen()

    def stop(self) -> None:
        """Stop playback and remove the window."""
        if self.player:
            try:
                self.player.terminate()
            except Exception:  # noqa: BLE001 - shutting down anyway
                log.warning("Error while stopping mpv", exc_info=True)
            self.player = None
        if self.host:
            self.host.close()
            self.host = None
        if self.path:
            log.info("Live wallpaper stopped")
        self.path = None
