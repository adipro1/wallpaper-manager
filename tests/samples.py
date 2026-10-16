"""Generate small sample media files for tests (and manual UI testing)."""

from fractions import Fraction
from pathlib import Path

import av
from PIL import Image, ImageDraw


def make_image(path: Path, size=(320, 180), color=(40, 90, 160)) -> Path:
    img = Image.new("RGB", size, color)
    ImageDraw.Draw(img).ellipse((size[0] // 4, size[1] // 4, size[0] * 3 // 4, size[1] * 3 // 4), fill=(240, 200, 60))
    if path.suffix.lower() == ".gif":
        frames = [img, Image.new("RGB", size, (200, 60, 60)), Image.new("RGB", size, (60, 200, 90))]
        frames[0].save(path, save_all=True, append_images=frames[1:], duration=200, loop=0)
    else:
        img.save(path)
    return path


def make_video(path: Path, size=(320, 180), seconds=2, fps=10) -> Path:
    """Encode a short video whose brightness increases frame by frame."""
    codec = "libvpx-vp9" if path.suffix.lower() == ".webm" else "mpeg4"
    with av.open(str(path), "w") as container:
        stream = container.add_stream(codec, rate=fps)
        stream.width, stream.height = size
        stream.pix_fmt = "yuv420p"
        stream.time_base = Fraction(1, fps)
        frames = seconds * fps
        for i in range(frames):
            value = int(255 * i / max(frames - 1, 1))
            frame = av.VideoFrame.from_image(Image.new("RGB", size, (value, value // 2, 255 - value)))
            frame.pts = i
            for packet in stream.encode(frame):
                container.mux(packet)
        for packet in stream.encode():
            container.mux(packet)
    return path


def make_sample_set(folder: Path) -> dict[str, Path]:
    folder.mkdir(parents=True, exist_ok=True)
    return {
        "jpg": make_image(folder / "sunrise.jpg", (1920, 1080)),
        "png": make_image(folder / "city.png", (1280, 720), (90, 40, 140)),
        "webp": make_image(folder / "forest.webp", (800, 600), (30, 120, 60)),
        "gif": make_image(folder / "animated.gif"),
        "mp4": make_video(folder / "clip.mp4"),
        "webm": make_video(folder / "clip.webm", seconds=1),
    }
