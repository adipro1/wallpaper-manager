"""Settings page."""

import logging

import customtkinter as ctk

from ... import APP_NAME, __version__, winapi
from .. import theme
from ..widgets import BasePage, Card, PageHeader, button

log = logging.getLogger(__name__)

APPEARANCE_OPTIONS = {"Dark": "dark", "Light": "light", "System": "system"}


class SettingRow(ctk.CTkFrame):
    """One row inside a settings card: title + description on the left, a control on the right."""

    def __init__(self, master, title: str, description: str = ""):
        super().__init__(master, fg_color="transparent")
        self.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(self, text=title, font=theme.font(14), text_color=theme.TEXT, anchor="w").grid(
            row=0, column=0, sticky="w")
        if description:
            ctk.CTkLabel(self, text=description, font=theme.font(12), text_color=theme.TEXT_MUTED, anchor="w",
                         justify="left", wraplength=520).grid(row=1, column=0, sticky="w")
        self.control = ctk.CTkFrame(self, fg_color="transparent")
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
        self.appearance = ctk.CTkSegmentedButton(
            row.control, values=list(APPEARANCE_OPTIONS), command=self._set_appearance, font=theme.font(13),
            selected_color=theme.ACCENT, selected_hover_color=theme.ACCENT_HOVER, height=32,
        )
        self.appearance.set(label)
        self.appearance.pack()

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

        # About
        about = self._card("About")
        ctk.CTkLabel(about, text=f"{APP_NAME}  ·  Version {__version__}", font=theme.font(13),
                     text_color=theme.TEXT_MUTED, anchor="w").pack(fill="x", padx=20, pady=(0, 18))

    def _card(self, title: str) -> Card:
        card = Card(self.body)
        card.pack(fill="x", padx=12, pady=(0, 16))
        ctk.CTkLabel(card, text=title, font=theme.font(15, "bold"), text_color=theme.TEXT, anchor="w").pack(
            fill="x", padx=20, pady=(16, 10))
        return card

    def _set_appearance(self, label: str) -> None:
        mode = APPEARANCE_OPTIONS[label]
        ctk.set_appearance_mode(mode)
        self.app.db.set_setting("appearance_mode", mode)
        log.info("Appearance mode set to %s", mode)
