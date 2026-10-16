"""Main window: sidebar navigation plus a content area that hosts the pages."""

import logging
import traceback
from tkinter import messagebox

import customtkinter as ctk

from . import APP_NAME, __version__
from .config import AppPaths
from .database import Database
from .ui import icons, theme
from .ui.pages.desktop import DesktopPage
from .ui.pages.home import HomePage
from .ui.pages.library import LibraryPage
from .ui.pages.lockscreen import LockScreenPage
from .ui.pages.playlists import PlaylistsPage
from .ui.pages.schedule import SchedulePage
from .ui.pages.settings import SettingsPage

log = logging.getLogger(__name__)

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

        self.title(APP_NAME)
        self.geometry("1240x800")
        self.minsize(1040, 680)

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
        log.info("Main window ready")

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
