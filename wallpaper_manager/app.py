"""Main window: sidebar navigation plus a content area that hosts the pages."""

import logging
import traceback
from tkinter import messagebox

import customtkinter as ctk
from customtkinter.windows.widgets.scaling import ScalingTracker

from . import APP_NAME, __version__, system, winapi
from .config import ICON_ICO, AppPaths
from .database import Database
from .lockscreen import LockScreenService
from .playlists import PlaylistPlayer
from .scheduler import Scheduler
from .system import SingleInstance
from .tray import Tray
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
LIVE_CHECK_MS = 3000
AUTO_PAUSE_CHECK_MS = 2000  # fullscreen / battery check
EVENT_POLL_MS = 200  # tray clicks and requests from a second instance
AUTOMATION_CHECK_MS = 5000  # how often to check the schedule and the running playlist  # how often to check that the live wallpaper window is still alive

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
    def __init__(self, db: Database, paths: AppPaths, start_minimized: bool = False,
                 instance: SingleInstance | None = None):
        ctk.set_appearance_mode(db.get_setting("appearance_mode"))
        super().__init__(fg_color=theme.WINDOW_BG)
        self.db = db
        self.paths = paths
        self.thumbs = ThumbnailCache(db, paths.thumb_dir)
        self.wallpapers = WallpaperService(db, paths)
        self.lockscreen = LockScreenService(db, paths)
        self.playlists = PlaylistPlayer(db, self.wallpapers)
        self.scheduler = Scheduler(db, self.wallpapers, self.playlists)
        self._advancing = False
        self.instance = instance
        self._tray_hint_shown = False
        self._shown_once = not start_minimized

        self.title(APP_NAME)
        self.iconbitmap(str(ICON_ICO))
        self.minsize(980, 620)
        self._fit_to_screen()
        if start_minimized:  # started with Windows: live in the tray until opened
            self.withdraw()

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
        if not start_minimized:
            self.after(200, self._ensure_window_size)
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self._bind_shortcuts()
        self.tray = Tray(self.wallpapers)
        self.tray.start()
        self.after(EVENT_POLL_MS, self._poll_events)
        self.after(AUTO_PAUSE_CHECK_MS, self._check_auto_pause)
        self.after(500, self._restore_live_wallpaper)
        self.after(LIVE_CHECK_MS, self._check_live_wallpaper)
        self.after(AUTOMATION_CHECK_MS, self._check_automation)
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

    def _check_automation(self) -> None:
        """Apply a due schedule rule, or advance the running playlist (on a worker thread).
        One check at a time, so the two never change the wallpaper simultaneously."""
        self.after(AUTOMATION_CHECK_MS, self._check_automation)
        if self._advancing:
            return
        if self.scheduler.is_due():
            work = self.scheduler.apply_due
        elif self.playlists.is_due():
            work = self.playlists.advance
        else:
            return
        self._advancing = True

        def finished(_result=None):
            self._advancing = False
            self.refresh_visible_page()

        def failed(exc):
            log.warning("Automatic wallpaper change failed: %s", exc)
            finished()

        run_in_thread(self, work, on_done=finished, on_error=failed)

    def refresh_visible_page(self) -> None:
        """Update pages that show the current wallpaper after it changed in the background."""
        if self.state() == "withdrawn":
            return
        if self.current_page in ("home", "desktop"):
            self.pages[self.current_page].refresh_current()
        elif self.current_page in ("playlists", "schedule"):
            self.pages[self.current_page].refresh()

    def _check_auto_pause(self) -> None:
        """Pause the live wallpaper while a fullscreen app runs or (optionally) on battery."""
        self.after(AUTO_PAUSE_CHECK_MS, self._check_auto_pause)
        if not self.wallpapers.video_active:
            return
        reason = None
        try:
            if self.db.get_setting("pause_on_fullscreen", True) and system.fullscreen_app_running():
                reason = "fullscreen"
            elif self.db.get_setting("pause_on_battery", False) and system.on_battery():
                reason = "battery"
        except OSError:
            log.debug("Auto-pause check failed", exc_info=True)
        before = self.wallpapers.auto_pause_reason
        self.wallpapers.update_auto_pause(reason)
        if self.wallpapers.auto_pause_reason != before:
            self.refresh_visible_page()

    # -- tray, window visibility and exit ------------------------------------------------

    def _poll_events(self) -> None:
        """Handle tray menu clicks and "show yourself" requests from a second app instance."""
        while not self.tray.actions.empty():
            self._handle_tray_action(self.tray.actions.get_nowait())
            if not self.winfo_exists():
                return  # "Exit" was chosen
        if self.instance and self.instance.show_requested():
            self.show_window()
        self.after(EVENT_POLL_MS, self._poll_events)

    def _handle_tray_action(self, action: str) -> None:
        log.info("Tray: %s", action)
        if action == "exit":
            self.exit_app()
            return
        if action == "open":
            self.show_window()
        elif action == "pause":
            self.wallpapers.pause_video()
        elif action == "resume":
            self.wallpapers.resume_video()
        elif action == "stop":
            self.wallpapers.stop_video()
        elif action in ("next", "random"):
            self.change_wallpaper(action)
        self.refresh_visible_page()

    def change_wallpaper(self, action: str) -> None:
        """Tray "Next" advances the running playlist (or steps through the library);
        "Random" picks any library item and, being a manual choice, stops the playlist."""
        if action == "next" and self.playlists.active_playlist():
            work = self.playlists.advance
        elif action == "next":
            work = self.wallpapers.next_from_library
        else:
            self.playlists.stop()
            work = self.wallpapers.random_from_library

        def failed(exc):
            log.warning("Could not change the wallpaper from the tray: %s", exc)
            self.tray.notify(f"Could not change the wallpaper: {exc}")

        run_in_thread(self, work, on_done=lambda _r: self.refresh_visible_page(), on_error=failed)

    def show_window(self) -> None:
        first_show = not self._shown_once
        self.deiconify()
        self.lift()
        self.focus_force()
        if first_show:
            self._shown_once = True
            self.after(200, self._ensure_window_size)
        if self.current_page:
            self.pages[self.current_page].on_show()

    def hide_to_tray(self) -> None:
        for page in self.pages.values():
            page.on_hide()  # e.g. stop preview videos
        self.withdraw()
        if not self._tray_hint_shown:
            self._tray_hint_shown = True
            self.tray.notify("Still running in the system tray. Right-click the icon for options.")

    def on_close(self) -> None:
        """Window close button: hide to the tray (default) or quit."""
        if self.db.get_setting("close_to_tray", True):
            self.hide_to_tray()
        else:
            self.exit_app()

    def exit_app(self) -> None:
        log.info("Exiting")
        for page in self.pages.values():
            page.on_hide()
        try:
            self.wallpapers.shutdown()
        finally:
            self.tray.stop()
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

    def _bind_shortcuts(self) -> None:
        """Ctrl+1…7 switch pages, Ctrl+F searches the library, Ctrl+I imports files."""
        for number, (key, *_rest) in enumerate(NAV_ITEMS, start=1):
            self.bind_all(f"<Control-Key-{number}>", lambda _e, k=key: self.show_page(k))
        self.bind_all("<Control-f>", lambda _e: self._focus_library_search())
        self.bind_all("<Control-i>", lambda _e: self.show_page("library").import_files())

    def _focus_library_search(self) -> None:
        self.show_page("library").search_entry.focus_set()

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
