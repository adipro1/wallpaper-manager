"""Main window: sidebar navigation plus a content area that hosts the pages."""

import logging
import traceback
from tkinter import messagebox

import customtkinter as ctk
from customtkinter.windows.widgets.scaling import ScalingTracker

from . import APP_NAME, __version__, winapi
from .config import AppPaths
from .database import Database
from .lockscreen import LockScreenService
from .wallpaper import WallpaperService
from .ui import icons, theme
from .ui.thumbs import ThumbnailCache
from .ui.widgets import run_in_thread
from .ui.pages.desktop import DesktopPage
from .ui.pages.home import HomePage
from .ui.pages.library import LibraryPage
from .ui.pages.lockscreen import LockScreenPage
from .ui.pages.playlists import PlaylistsPage
from .ui.pages.schedule import SchedulePage
from .ui.pages.settings import SettingsPage

log = logging.getLogger(__name__)

DEFAULT_SIZE = (1240, 800)
LIVE_CHECK_MS = 3000  # how often to check that the live wallpaper window is still alive

# (key, label, icon, page class) in sidebar order
NAV_ITEMS = [
    ("home", "Home", "home", HomePage),
    ("desktop", "Desktop", "desktop", DesktopPage),
    ("lockscreen", "Lock Screen", "lock", LockScreenPage),
    ("library", "Library", "library", LibraryPage),
    ("playlists", "Playlists", "playlist", PlaylistsPage),
    ("schedule", "Schedule", "schedule", SchedulePage),
    ("settings", "Settings", "settings", SettingsPage),
]


class WallpaperManagerApp(ctk.CTk):
    def __init__(self, db: Database, paths: AppPaths):
        ctk.set_appearance_mode(db.get_setting("appearance_mode"))
        super().__init__(fg_color=theme.WINDOW_BG)
        self.db = db
        self.paths = paths
        self.thumbs = ThumbnailCache(db, paths.thumb_dir)
        self.wallpapers = WallpaperService(db, paths)
        self.lockscreen = LockScreenService(db, paths)

        self.title(APP_NAME)
        self.minsize(980, 620)
        self._fit_to_screen()

        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        self._build_sidebar()
        self.content = ctk.CTkFrame(self, fg_color="transparent", corner_radius=0)
        self.content.grid(row=0, column=1, sticky="nsew")
        self.content.grid_columnconfigure(0, weight=1)
        self.content.grid_rowconfigure(0, weight=1)

        self.pages: dict = {}
        self.current_page: str | None = None
        self.show_page("home")
        self.after(200, self._ensure_window_size)
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(500, self._restore_live_wallpaper)
        self.after(LIVE_CHECK_MS, self._check_live_wallpaper)
        log.info("Main window ready")

    # -- live wallpaper lifecycle --------------------------------------------------

    def _restore_live_wallpaper(self) -> None:
        """Start the live wallpaper that was playing when the app was last closed."""
        def on_error(exc):
            log.warning("Could not restore the live wallpaper: %s", exc)

        def on_done(started):
            if started and self.current_page == "desktop":
                self.pages["desktop"].refresh_current()

        run_in_thread(self, self.wallpapers.restore_on_startup, on_done=on_done, on_error=on_error)

    def _check_live_wallpaper(self) -> None:
        try:
            self.wallpapers.check_live()
        except Exception:  # noqa: BLE001 - keep the watchdog running
            log.exception("Live wallpaper check failed")
        self.after(LIVE_CHECK_MS, self._check_live_wallpaper)

    def on_close(self) -> None:
        log.info("Closing")
        for page in self.pages.values():
            page.on_hide()
        try:
            self.wallpapers.shutdown()
        finally:
            self.destroy()

    def _preferred_size(self) -> tuple[int, int, int, int] | None:
        """Preferred window size (scaled units) and position (physical pixels),
        shrunk to fit and centred on the screen's work area."""
        work_area = winapi.get_work_area()
        if work_area is None:
            return None
        left, top, right, bottom = work_area
        scale = ScalingTracker.get_window_scaling(self)
        width = min(DEFAULT_SIZE[0], int((right - left) / scale) - 32)
        height = min(DEFAULT_SIZE[1], int((bottom - top) / scale) - 48)  # room for the title bar
        x = left + ((right - left) - int(width * scale)) // 2
        y = top + max(((bottom - top) - int((height + 32) * scale)) // 2, 0)
        return width, height, x, y

    def _fit_to_screen(self) -> None:
        size = self._preferred_size()
        # CustomTkinter scales width/height by the DPI factor but not the x/y position.
        self.geometry("{}x{}+{}+{}".format(*size) if size else "{}x{}".format(*DEFAULT_SIZE))

    def _ensure_window_size(self, attempts: int = 5) -> None:
        """On high-DPI screens Windows clamps the size requested before the window is
        first shown. Re-apply it once the window is visible, retrying briefly if needed."""
        size = self._preferred_size()
        if size is None:
            return
        scale = ScalingTracker.get_window_scaling(self)
        if self.winfo_width() < int(size[0] * scale) - 4:
            self._fit_to_screen()
            if attempts > 1:
                self.after(200, lambda: self._ensure_window_size(attempts - 1))

    # -- sidebar -------------------------------------------------------------

    def _build_sidebar(self) -> None:
        sidebar = ctk.CTkFrame(self, width=232, corner_radius=0, fg_color=theme.SIDEBAR_BG)
        sidebar.grid(row=0, column=0, sticky="nsw")
        sidebar.grid_propagate(False)
        sidebar.grid_columnconfigure(0, weight=1)

        brand = ctk.CTkFrame(sidebar, fg_color="transparent")
        brand.grid(row=0, column=0, sticky="ew", padx=20, pady=(24, 22))
        ctk.CTkLabel(brand, text="", image=icons.icon("image", 26, theme.ACCENT)).pack(side="left")
        ctk.CTkLabel(brand, text="Wallpaper\nManager", justify="left", font=theme.font(15, "bold"),
                     text_color=theme.TEXT).pack(side="left", padx=(10, 0))

        self.nav_buttons: dict[str, ctk.CTkButton] = {}
        for row, (key, label, icon_name, _page) in enumerate(NAV_ITEMS, start=1):
            btn = ctk.CTkButton(
                sidebar, text=label, anchor="w", height=40, corner_radius=8,
                image=icons.icon(icon_name, 18), compound="left",
                font=theme.font(14), text_color=theme.TEXT,
                fg_color="transparent", hover_color=theme.NAV_HOVER,
                command=lambda k=key: self.show_page(k),
            )
            btn.grid(row=row, column=0, sticky="ew", padx=12, pady=2)
            self.nav_buttons[key] = btn

        sidebar.grid_rowconfigure(len(NAV_ITEMS) + 1, weight=1)
        ctk.CTkLabel(sidebar, text=f"Version {__version__}", font=theme.font(11),
                     text_color=theme.TEXT_SUBTLE).grid(row=len(NAV_ITEMS) + 2, column=0, sticky="w",
                                                         padx=24, pady=16)

    # -- navigation ------------------------------------------------------------

    def show_page(self, key: str):
        if key == self.current_page:
            return self.pages[key]
        if self.current_page:
            old = self.pages[self.current_page]
            old.on_hide()
            old.grid_remove()
            self.nav_buttons[self.current_page].configure(fg_color="transparent")

        if key not in self.pages:  # pages are built the first time they are opened
            page_cls = next(cls for k, _l, _i, cls in NAV_ITEMS if k == key)
            self.pages[key] = page_cls(self.content, self)
        page = self.pages[key]
        page.grid(row=0, column=0, sticky="nsew")
        self.nav_buttons[key].configure(fg_color=theme.NAV_SELECTED)
        self.current_page = key
        page.on_show()
        return page

    # -- error handling --------------------------------------------------------

    def report_callback_exception(self, exc, val, tb):
        """Tk calls this for exceptions raised inside UI callbacks."""
        log.error("Error in UI callback:\n%s", "".join(traceback.format_exception(exc, val, tb)))
        messagebox.showerror(APP_NAME, f"Something went wrong:\n\n{val}\n\nDetails were written to the log file.")
