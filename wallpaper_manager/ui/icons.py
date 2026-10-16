"""Icons drawn from the Windows icon font (Segoe Fluent Icons on Windows 11,
Segoe MDL2 Assets on Windows 10) so they match the OS and stay sharp.

Each icon is rendered with Pillow into a CTkImage that has a light and a dark
variant, so it follows the appearance mode automatically.
"""

import os
from functools import lru_cache

import customtkinter as ctk
from PIL import Image, ImageDraw, ImageFont

from . import theme

GLYPHS = {
    "home": "\uE80F",
    "desktop": "\uE7F4",
    "lock": "\uE72E",
    "library": "\uE8F1",
    "playlist": "\uE8FD",
    "schedule": "\uE823",
    "settings": "\uE713",
    "star": "\uE734",
    "star_filled": "\uE735",
    "add": "\uE710",
    "folder": "\uE8B7",
    "folder_open": "\uE838",
    "delete": "\uE74D",
    "play": "\uE768",
    "pause": "\uE769",
    "stop": "\uE71A",
    "search": "\uE721",
    "video": "\uE714",
    "image": "\uE8B9",
    "open": "\uE8A7",
    "refresh": "\uE72C",
    "info": "\uE946",
    "up": "\uE70E",
    "down": "\uE70D",
    "close": "\uE711",
    "edit": "\uE70F",
}

_FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
_FONT_CANDIDATES = ("SegoeIcons.ttf", "segmdl2.ttf")
_SUPERSAMPLE = 2  # render at 2x so icons stay crisp with Windows display scaling


@lru_cache(maxsize=None)
def _font_path() -> str | None:
    for name in _FONT_CANDIDATES:
        path = os.path.join(_FONT_DIR, name)
        if os.path.exists(path):
            return path
    return None


def _render(glyph: str, size: int, color: str) -> Image.Image:
    px = size * _SUPERSAMPLE
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    font_path = _font_path()
    if font_path is None:  # no icon font: leave a blank square instead of crashing
        return img
    font = ImageFont.truetype(font_path, px)
    draw = ImageDraw.Draw(img)
    # Centre the glyph using its real bounding box.
    left, top, right, bottom = draw.textbbox((0, 0), glyph, font=font)
    x = (px - (right - left)) / 2 - left
    y = (px - (bottom - top)) / 2 - top
    draw.text((x, y), glyph, font=font, fill=color)
    return img


@lru_cache(maxsize=None)
def icon(name: str, size: int = 16, color: tuple[str, str] | str = theme.TEXT) -> ctk.CTkImage:
    """Return a cached CTkImage for the named icon. `color` is (light, dark) or one colour."""
    glyph = GLYPHS[name]
    light, dark = (color, color) if isinstance(color, str) else color
    return ctk.CTkImage(
        light_image=_render(glyph, size, light),
        dark_image=_render(glyph, size, dark),
        size=(size, size),
    )
