"""System tray icon and menu (pystray).

pystray runs its own thread, and Tkinter must only be used from the main
thread, so menu clicks are just put in a queue; the app drains the queue from
its Tk main loop (see WallpaperManagerApp._poll_events).
"""

import logging
import queue
import threading

import pystray
from PIL import Image

from . import APP_NAME
from .config import ICON_PNG
from .wallpaper import WallpaperService

log = logging.getLogger(__name__)


class Tray:
    def __init__(self, wallpapers: WallpaperService):
        self.actions: queue.Queue[str] = queue.Queue()

        def action(name: str):
            return lambda _icon, _item: self.actions.put(name)

        # 'enabled' callbacks are evaluated each time the menu opens.
        video = wallpapers
        menu = pystray.Menu(
            pystray.MenuItem("Open", action("open"), default=True),  # default = double-click
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Next wallpaper", action("next")),
            pystray.MenuItem("Random wallpaper", action("random")),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Pause video", action("pause"),
                             enabled=lambda _item: video.video_active and not video.video_paused),
            pystray.MenuItem("Resume video", action("resume"),
                             enabled=lambda _item: video.video_active and video.video_paused),
            pystray.MenuItem("Stop video", action("stop"), enabled=lambda _item: video.video_active),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Exit", action("exit")),
        )
        self.icon = pystray.Icon("WallpaperManager", Image.open(ICON_PNG), APP_NAME, menu)

    def start(self) -> None:
        # A daemon thread, so the process can always exit - even if the app crashes
        # before it gets to stop the icon (pystray's own thread would keep it alive).
        threading.Thread(target=self.icon.run, name="tray", daemon=True).start()

    def notify(self, message: str) -> None:
        try:
            self.icon.notify(message, APP_NAME)
        except Exception:  # noqa: BLE001 - notifications are a nicety
            log.debug("Tray notification failed", exc_info=True)

    def stop(self) -> None:
        try:
            self.icon.stop()
        except Exception:  # noqa: BLE001
            log.debug("Stopping the tray icon failed", exc_info=True)
