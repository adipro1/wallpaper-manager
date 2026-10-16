"""Small reusable widgets and helpers shared by the pages."""

import logging
import threading
from tkinter import messagebox
from typing import Any, Callable

import customtkinter as ctk

from ..config import ICON_ICO
from . import icons, theme

log = logging.getLogger(__name__)


class Card(ctk.CTkFrame):
    """Rounded surface used to group content."""

    def __init__(self, master, **kwargs):
        kwargs.setdefault("fg_color", theme.CARD_BG)
        kwargs.setdefault("corner_radius", 12)
        kwargs.setdefault("border_width", 1)
        kwargs.setdefault("border_color", theme.CARD_BORDER)
        super().__init__(master, **kwargs)


def button(master, text: str, command=None, icon: str | None = None, style: str = "secondary", **kwargs):
    """Create a button in one of three styles: 'primary', 'secondary' or 'danger'."""
    styles = {
        "primary": dict(fg_color=theme.ACCENT, hover_color=theme.ACCENT_HOVER, text_color=theme.ACCENT_TEXT,
                        border_width=0),
        "secondary": dict(fg_color=theme.SECONDARY_BTN, hover_color=theme.SECONDARY_BTN_HOVER,
                          text_color=theme.TEXT, border_width=1, border_color=theme.CARD_BORDER),
        "danger": dict(fg_color="transparent", hover_color=theme.SECONDARY_BTN_HOVER,
                       text_color=theme.DANGER, border_width=1, border_color=theme.CARD_BORDER),
    }
    opts = dict(styles[style], height=34, corner_radius=8, font=theme.font(13))
    if icon:
        color = {"primary": theme.ACCENT_TEXT, "danger": theme.DANGER}.get(style, theme.TEXT)
        opts.update(image=icons.icon(icon, 16, color), compound="left")
    opts.update(kwargs)
    return ctk.CTkButton(master, text=text, command=command, **opts)


def icon_button(master, icon_name: str, command, color=theme.TEXT_MUTED) -> ctk.CTkButton:
    """Small borderless button showing only an icon (move up, remove, edit, ...)."""
    return ctk.CTkButton(master, text="", width=30, height=30, corner_radius=6, fg_color="transparent",
                         hover_color=theme.NAV_HOVER, image=icons.icon(icon_name, 14, color), command=command)


def segmented(master, values: list[str], command=None, **kwargs) -> ctk.CTkSegmentedButton:
    """Segmented control styled like the Windows 11 selector bars."""
    opts = dict(height=34, font=theme.font(13), corner_radius=8, fg_color=theme.SEGMENT_BG,
                unselected_color=theme.SEGMENT_BG, unselected_hover_color=theme.SEGMENT_HOVER,
                selected_color=theme.SEGMENT_SELECTED, selected_hover_color=theme.SEGMENT_SELECTED,
                text_color=theme.TEXT)
    opts.update(kwargs)
    return ctk.CTkSegmentedButton(master, values=values, command=command, **opts)


class PageHeader(ctk.CTkFrame):
    """Page title, optional subtitle, and a right-aligned area for action buttons."""

    def __init__(self, master, title: str, subtitle: str = ""):
        super().__init__(master, fg_color="transparent")
        self.grid_columnconfigure(0, weight=1)
        self.title_label = ctk.CTkLabel(self, text=title, font=theme.font(28, "bold"), text_color=theme.TEXT,
                                        anchor="w")
        self.title_label.grid(row=0, column=0, sticky="w")
        self.subtitle = ctk.CTkLabel(self, text=subtitle, font=theme.font(13), text_color=theme.TEXT_MUTED,
                                     anchor="w")
        self.subtitle.grid(row=1, column=0, sticky="w")
        # width/height=1: an empty CTkFrame would otherwise reserve 200x200 px
        self.actions = ctk.CTkFrame(self, fg_color="transparent", width=1, height=1)
        self.actions.grid(row=0, column=1, rowspan=2, sticky="e")


def section_label(master, text: str) -> ctk.CTkLabel:
    return ctk.CTkLabel(master, text=text, font=theme.font(15, "bold"), text_color=theme.TEXT, anchor="w")


class BasePage(ctk.CTkFrame):
    """Base class for the pages shown in the main content area."""

    title = ""

    def __init__(self, master, app):
        super().__init__(master, fg_color="transparent")
        self.app = app

    def on_show(self) -> None:
        """Called every time the page becomes visible."""

    def on_hide(self) -> None:
        """Called when another page is shown (stop previews, timers, ...)."""


def apply_window_icon(window) -> None:
    """Use the app icon for a CTkToplevel (CustomTkinter would otherwise set its own)."""
    window.iconbitmap(str(ICON_ICO))


class PlaceholderPage(BasePage):
    """Shown for features that arrive in a later development phase."""

    icon_name = "info"
    subtitle = ""
    planned: list[str] = []

    def __init__(self, master, app):
        super().__init__(master, app)
        self.grid_columnconfigure(0, weight=1)
        PageHeader(self, self.title, self.subtitle).grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 20))
        card = Card(self)
        card.grid(row=1, column=0, sticky="ew", padx=32)
        ctk.CTkLabel(card, text="", image=icons.icon(self.icon_name, 40, theme.ACCENT)).pack(pady=(32, 8))
        ctk.CTkLabel(card, text="Coming in a later update", font=theme.font(17, "bold"),
                     text_color=theme.TEXT).pack()
        bullets = ctk.CTkFrame(card, fg_color="transparent")
        bullets.pack(pady=(6, 28))
        for line in self.planned:
            ctk.CTkLabel(bullets, text=f"•   {line}", font=theme.font(13), text_color=theme.TEXT_MUTED,
                         anchor="w").pack(anchor="w")


def run_in_thread(
    widget,
    func: Callable[[], Any],
    on_done: Callable[[Any], None] | None = None,
    on_error: Callable[[BaseException], None] | None = None,
    on_tick: Callable[[], None] | None = None,
    poll_ms: int = 60,
) -> threading.Thread:
    """Run `func` in a background thread and call the callbacks on the Tk thread.

    Tkinter is not thread-safe, so the worker never touches widgets; instead the
    UI polls the thread with `after()` and calls `on_tick` while it runs.
    """
    result: dict[str, Any] = {}

    def worker():
        try:
            result["value"] = func()
        except Exception as exc:  # noqa: BLE001 - reported to the user below
            log.exception("Background task failed")
            result["error"] = exc

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()

    def poll():
        if not widget.winfo_exists():
            return
        if on_tick:
            on_tick()
        if thread.is_alive():
            widget.after(poll_ms, poll)
        elif "error" in result:
            if on_error:
                on_error(result["error"])
            else:
                messagebox.showerror("Error", str(result["error"]))
        elif on_done:
            on_done(result.get("value"))

    widget.after(poll_ms, poll)
    return thread
