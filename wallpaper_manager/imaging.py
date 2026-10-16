"""Pillow helpers for resizing images to a box."""

from PIL import Image, ImageDraw, ImageOps


def cover(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Scale and centre-crop so the image fills `size` completely."""
    return ImageOps.fit(img, size, Image.Resampling.LANCZOS)


def contain(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Scale so the whole image fits inside `size`, keeping its aspect ratio."""
    return ImageOps.contain(img, size, Image.Resampling.LANCZOS)


def rounded(img: Image.Image, radius: int) -> Image.Image:
    """Return an RGBA copy with transparent rounded corners."""
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def shorten_middle(text: str, max_len: int) -> str:
    """Shorten long text such as a folder path by replacing its middle with '…'."""
    if len(text) <= max_len:
        return text
    keep = max_len - 1
    return text[: keep // 2] + "…" + text[-(keep - keep // 2):]


def open_rgb(path: str, draft_size: tuple[int, int] | None = None) -> Image.Image:
    """Open an image, apply its EXIF rotation and convert it to RGB (first frame for GIFs).

    `draft_size` lets JPEG decoding skip detail we don't need, which makes
    thumbnails of very large photos much faster.
    """
    with Image.open(path) as img:
        if draft_size:
            img.draft("RGB", draft_size)
        img = ImageOps.exif_transpose(img)
        return img.convert("RGB")
