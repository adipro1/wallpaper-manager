"""Settings page."""

import logging
import webbrowser
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk

from ... import APP_NAME, __version__, system, winapi
from ...diagnostics import save_diagnostics
from ...live_wallpaper import libmpv_available
from ...updates import RELEASES_PAGE
from .. import theme
from ..widgets import BasePage, Card, PageHeader, button, run_in_thread, segmented

log = logging.getLogger(__name__)

APPEARANCE_OPTIONS = {"Dark": "dark", "Light": "light", "System": "system"}


class SettingRow(ctk.CTkFrame):
    """One row inside a settings card: title + description on the left, a control on the right."""

    def __init__(self, master, title: str, description: str = ""):
        super().__init__(master, fg_color="transparent")
        self.grid_columnconfigure(0, weight=1)
        self.title_label = ctk.CTkLabel(self, text=title, font=theme.font(14), text_color=theme.TEXT, anchor="w")
        self.title_label.grid(row=0, column=0, sticky="w")
        if description:
            ctk.CTkLabel(self, text=description, font=theme.font(12), text_color=theme.TEXT_MUTED, anchor="w",
                         justify="left", wraplength=520).grid(row=1, column=0, sticky="w")
        self.control = ctk.CTkFrame(self, fg_color="transparent", width=1, height=1)  # no 200px default
        self.control.grid(row=0, column=1, rowspan=2, sticky="e", padx=(16, 0))


class SettingsPage(BasePage):
    title = "Settings"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)
        PageHeader(self, "Settings", "Personalise how Wallpaper Manager looks and behaves.").grid(
            row=0, column=0, sticky="ew", padx=32, pady=(28, 20))

        body = ctk.CTkScrollableFrame(self, fg_color="transparent")
        body.grid(row=1, column=0, sticky="nsew", padx=(20, 8), pady=(0, 16))
        body.grid_columnconfigure(0, weight=1)
        self.body = body

        # Appearance
        appearance = self._card("Appearance")
        row = SettingRow(appearance, "App theme", "Choose dark, light, or follow the Windows setting.")
        row.pack(fill="x", padx=20, pady=(0, 18))
        current = app.db.get_setting("appearance_mode")
        label = next((k for k, v in APPEARANCE_OPTIONS.items() if v == current), "Dark")
        self.appearance = segmented(row.control, list(APPEARANCE_OPTIONS), self._set_appearance)
        self.appearance.set(label)
        self.appearance.pack()

        # General
        general = self._card("General")
        self._switch_row(general, "Close to tray",
                         "The close button hides the window; the app keeps running in the system tray.",
                         app.db.get_setting("close_to_tray", True),
                         lambda on: app.db.set_setting("close_to_tray", on))
        self._switch_row(general, "Start with Windows",
                         "Open Wallpaper Manager in the tray when you sign in.",
                         system.startup_enabled(), self._set_startup)
        self._switch_row(general, "Check for updates",
                         "Once a day, look for a newer version on GitHub. Nothing is installed automatically.",
                         app.db.get_setting("check_for_updates", True),
                         lambda on: app.db.set_setting("check_for_updates", on), last=True)

        # Performance
        performance = self._card("Performance")
        self._switch_row(performance, "Pause video for fullscreen apps",
                         "Pause the live wallpaper while a game or video runs full screen.",
                         app.db.get_setting("pause_on_fullscreen", True),
                         lambda on: app.db.set_setting("pause_on_fullscreen", on))
        self._switch_row(performance, "Pause video on battery",
                         "Pause the live wallpaper while the PC runs on battery power.",
                         app.db.get_setting("pause_on_battery", False),
                         lambda on: app.db.set_setting("pause_on_battery", on), last=True)

        # Storage
        storage = self._card("Storage")
        row = SettingRow(storage, "App data folder", str(app.paths.data_dir))
        row.pack(fill="x", padx=20, pady=(0, 12))
        button(row.control, "Open", lambda: winapi.open_folder(str(app.paths.data_dir)),
               icon="folder_open", width=100).pack()
        row = SettingRow(storage, "Log files", "Errors and diagnostic information are written here.")
        row.pack(fill="x", padx=20, pady=(0, 18))
        button(row.control, "Open", lambda: winapi.open_folder(str(app.paths.log_dir)),
               icon="folder_open", width=100).pack()

        # Live wallpapers
        live = self._card("Live wallpapers")
        self._switch_row(live, "Resume on start", "Start the last live wallpaper again when Wallpaper Manager opens.",
                         app.db.get_setting("resume_live_wallpaper", True),
                         lambda on: app.db.set_setting("resume_live_wallpaper", on))
        engine = "installed" if libmpv_available() else "missing - run  python tools/get_libmpv.py"
        row = SettingRow(live, "Video engine (libmpv)", f"Status: {engine}")
        row.pack(fill="x", padx=20, pady=(0, 18))

        # Diagnostics
        diagnostics = self._card("Diagnostics")
        row = SettingRow(diagnostics, "Save diagnostics file",
                         "Creates a zip with your Windows version, desktop layout, settings and log files - "
                         "send it along with a bug report. No pictures or videos are included.")
        row.pack(fill="x", padx=20, pady=(0, 18))
        self.diagnostics_btn = button(row.control, "Save…", self._save_diagnostics, icon="info", width=100)
        self.diagnostics_btn.pack()

        # About
        about = self._card("About")
        row = SettingRow(about, f"{APP_NAME}  ·  Version {__version__}", "")
        row.pack(fill="x", padx=20, pady=(0, 18))
        self.update_label = row.title_label
        self.update_button = button(row.control, "Check for updates", self._check_updates, icon="refresh")
        self.update_button.pack()
        self._show_update_state()

    def _card(self, title: str) -> Card:
        card = Card(self.body)
        card.pack(fill="x", padx=12, pady=(0, 16))
        ctk.CTkLabel(card, text=title, font=theme.font(15, "bold"), text_color=theme.TEXT, anchor="w").pack(
            fill="x", padx=20, pady=(16, 10))
        return card

    def _switch_row(self, card, title: str, description: str, value: bool, on_change, last: bool = False):
        row = SettingRow(card, title, description)
        row.pack(fill="x", padx=20, pady=(0, 18 if last else 12))
        switch = ctk.CTkSwitch(row.control, text="", width=46, progress_color=theme.ACCENT,
                               command=lambda: on_change(bool(switch.get())))
        if value:
            switch.select()
        switch.pack()
        return switch

    def _save_diagnostics(self) -> None:
        default_dir = Path.home() / "Desktop"
        path = filedialog.asksaveasfilename(
            parent=self, title="Save diagnostics", defaultextension=".zip",
            initialdir=str(default_dir if default_dir.exists() else Path.home()),
            initialfile=f"WallpaperManager-diagnostics-{datetime.now():%Y%m%d-%H%M}.zip",
            filetypes=[("Zip file", "*.zip")])
        if not path:
            return
        self.diagnostics_btn.configure(state="disabled")

        def done(saved):
            self.diagnostics_btn.configure(state="normal")
            winapi.show_in_explorer(str(saved))

        def failed(exc):
            self.diagnostics_btn.configure(state="normal")
            messagebox.showerror("Diagnostics", f"Could not save the diagnostics file:\n{exc}", parent=self)

        app = self.app
        run_in_thread(self, lambda: save_diagnostics(Path(path), app.db, app.paths, app.wallpapers),
                      on_done=done, on_error=failed)

    def _show_update_state(self) -> None:
        version = self.app.updates.available_version()
        if version:
            self.update_label.configure(text=f"{APP_NAME}  ·  Version {__version__}  ·  Version {version} is available")
            self.update_button.configure(text=f"Download {version}", command=lambda: webbrowser.open(RELEASES_PAGE),
                                         state="normal")
        else:
            self.update_label.configure(text=f"{APP_NAME}  ·  Version {__version__}")

    def _check_updates(self) -> None:
        self.update_button.configure(state="disabled", text="Checking…")

        def done(version):
            self.update_button.configure(state="normal", text="Check for updates")
            self._show_update_state()
            if not version:
                messagebox.showinfo("Updates", f"You have the latest version ({__version__}).", parent=self)

        def failed(exc):
            self.update_button.configure(state="normal", text="Check for updates")
            messagebox.showerror("Updates", f"Could not check for updates:\n{exc}", parent=self)

        run_in_thread(self, lambda: self.app.updates.check_if_due(force=True), on_done=done, on_error=failed)

    def _set_startup(self, enabled: bool) -> None:
        try:
            system.set_startup(enabled)
        except OSError as exc:
            log.error("Could not change the startup setting: %s", exc)
            messagebox.showerror("Start with Windows", f"Could not change the setting:\n{exc}", parent=self)

    def _set_appearance(self, label: str) -> None:
        mode = APPEARANCE_OPTIONS[label]
        ctk.set_appearance_mode(mode)
        self.app.db.set_setting("appearance_mode", mode)
        log.info("Appearance mode set to %s", mode)
