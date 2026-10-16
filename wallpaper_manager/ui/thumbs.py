"""Thumbnail images for the UI (library cards, tiles), with type badges and caching."""

import os

import customtkinter as ctk
from PIL import Image, ImageDraw, ImageFont

from .. import imaging, media
from ..database import Database, MediaItem

_badge_font: ImageFont.FreeTypeFont | None = None


def _draw_badge(img: Image.Image, text: str, play_icon: bool = False) -> None:
    """Draw a small rounded label (e.g. video duration) in the bottom-right corner."""
    global _badge_font
    if _badge_font is None:
        try:
            _badge_font = ImageFont.truetype(os.path.join(os.environ.get("WINDIR", r"C:\Windows"),
                                                          "Fonts", "segoeuib.ttf"), 22)
        except OSError:
            _badge_font = ImageFont.load_default()
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    left, top, right, bottom = draw.textbbox((0, 0), text, font=_badge_font)
    icon_w = 20 if play_icon else 0
    w, h = right - left + 20 + icon_w, bottom - top + 12
    x0, y0 = img.width - w - 10, img.height - h - 10
    draw.rounded_rectangle((x0, y0, x0 + w, y0 + h), radius=8, fill=(0, 0, 0, 170))
    if play_icon:
        cy = y0 + h / 2
        draw.polygon([(x0 + 11, cy - 7), (x0 + 11, cy + 7), (x0 + 23, cy)], fill=(255, 255, 255, 255))
    draw.text((x0 + 10 + icon_w - left, y0 + 6 - top), text, font=_badge_font, fill=(255, 255, 255, 255))
    img.alpha_composite(overlay)


def badged_thumbnail(thumb_path, item: MediaItem, size: tuple[int, int]) -> Image.Image:
    """Rounded thumbnail rendered at 2x `size` (sharp on scaled displays) with a type badge."""
    px = (size[0] * 2, size[1] * 2)
    if thumb_path is None:
        img = Image.new("RGBA", px, (40, 40, 40, 255))
        _draw_badge(img, "Missing")
    else:
        with Image.open(thumb_path) as thumb:
            img = imaging.cover(thumb.convert("RGB"), px).convert("RGBA")
        if item.is_video:
            _draw_badge(img, item.duration_text or "Video", play_icon=True)
        elif item.filename.lower().endswith(".gif"):
            _draw_badge(img, "GIF")
    return imaging.rounded(img, 14)


class ThumbnailCache:
    """Creates CTkImages for library items once and reuses them."""

    def __init__(self, db: Database, thumb_dir):
        self.db = db
        self.thumb_dir = thumb_dir
        self._cache: dict[tuple, ctk.CTkImage] = {}

    def get(self, item: MediaItem, size: tuple[int, int]) -> ctk.CTkImage:
        cached = self._cache.get((item.id, item.thumbnail, size))
        if cached:
            return cached
        thumb_path = media.ensure_thumbnail(self.db, item, self.thumb_dir)  # may update item.thumbnail
        img = badged_thumbnail(thumb_path, item, size)
        image = ctk.CTkImage(img, img, size)
        if thumb_path is not None:  # don't cache "missing" placeholders; the file may come back
            self._cache[(item.id, item.thumbnail, size)] = image
        return image
