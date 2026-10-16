"""Generate the app icon (wallpaper_manager/assets/icon.png and icon.ico).

    python tools/make_icon.py

A rounded square with a blue gradient and the Fluent "picture" glyph.
"""

import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path(__file__).resolve().parent.parent / "wallpaper_manager" / "assets"
SIZE = 256
TOP, BOTTOM = (76, 194, 255), (0, 95, 184)  # Windows 11 accent blues


def make_icon() -> Image.Image:
    gradient = Image.new("RGBA", (SIZE, SIZE))
    draw = ImageDraw.Draw(gradient)
    for y in range(SIZE):
        t = y / (SIZE - 1)
        draw.line([(0, y), (SIZE, y)], fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)) + (255,))
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).rounded_rectangle((8, 8, SIZE - 9, SIZE - 9), radius=56, fill=255)
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    icon.paste(gradient, mask=mask)

    font_path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", "SegoeIcons.ttf")
    font = ImageFont.truetype(font_path, 150)
    glyph = "\uE8B9"  # picture
    draw = ImageDraw.Draw(icon)
    left, top, right, bottom = draw.textbbox((0, 0), glyph, font=font)
    draw.text(((SIZE - (right - left)) / 2 - left, (SIZE - (bottom - top)) / 2 - top), glyph, font=font,
              fill=(255, 255, 255, 255))
    return icon


if __name__ == "__main__":
    ASSETS.mkdir(parents=True, exist_ok=True)
    image = make_icon()
    image.save(ASSETS / "icon.png")
    image.save(ASSETS / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("Wrote", ASSETS / "icon.png", "and icon.ico")
