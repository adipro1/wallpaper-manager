"""Colours and fonts. Colours are (light, dark) pairs as CustomTkinter expects."""

import customtkinter as ctk

WINDOW_BG = ("#F3F3F3", "#202020")
SIDEBAR_BG = ("#EAEAEA", "#1A1A1A")
CARD_BG = ("#FFFFFF", "#2B2B2B")
CARD_HOVER = ("#F7F7F7", "#323232")
CARD_BORDER = ("#E3E3E3", "#363636")
INPUT_BG = ("#FFFFFF", "#2D2D2D")
PREVIEW_BG = "#111111"

TEXT = ("#1B1B1B", "#F5F5F5")
TEXT_MUTED = ("#616161", "#A3A3A3")
TEXT_SUBTLE = ("#8A8A8A", "#7A7A7A")

ACCENT = ("#005FB8", "#4CC2FF")
ACCENT_HOVER = ("#1975C5", "#47B1E8")
ACCENT_TEXT = ("#FFFFFF", "#002A40")

SEGMENT_BG = ("#E4E4E4", "#373737")
SEGMENT_SELECTED = ("#FFFFFF", "#4A4A4A")
SEGMENT_HOVER = ("#D8D8D8", "#3F3F3F")
NAV_HOVER = ("#E0E0E0", "#262626")
NAV_SELECTED = ("#D9D9D9", "#2D2D2D")
LIST_SELECTED = ("#E3E3E3", "#3A3A3A")  # selected row inside a card

SECONDARY_BTN = ("#FBFBFB", "#373737")
SECONDARY_BTN_HOVER = ("#F0F0F0", "#404040")

DANGER = ("#C42B1C", "#FF6B5E")
DANGER_HOVER = ("#A82518", "#E85B4F")
STAR = "#F5B400"

FONT_FAMILY = "Segoe UI"


def font(size: int = 13, weight: str = "normal") -> ctk.CTkFont:
    return ctk.CTkFont(family=FONT_FAMILY, size=size, weight=weight)
