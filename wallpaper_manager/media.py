"""Reading media files: type detection, metadata, thumbnails and importing.

Videos are read with PyAV, which bundles the FFmpeg libraries, so no separate
FFmpeg installation is needed.
"""

import hashlib
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import av
from PIL import Image, UnidentifiedImageError

from . import imaging
from .database import Database, MediaItem

log = logging.getLogger(__name__)

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}
VIDEO_EXTENSIONS = {".mp4", ".webm"}
SUPPORTED_EXTENSIONS = IMAGE_EXTENSIONS | VIDEO_EXTENSIONS
THUMB_SIZE = (400, 225)  # 16:9, shown at roughly half size in the library grid

# File dialog filter, e.g. for tkinter.filedialog.askopenfilenames
FILE_TYPES = [
    ("Images and videos", " ".join(f"*{ext}" for ext in sorted(SUPPORTED_EXTENSIONS))),
    ("Images", " ".join(f"*{ext}" for ext in sorted(IMAGE_EXTENSIONS))),
    ("Videos", " ".join(f"*{ext}" for ext in sorted(VIDEO_EXTENSIONS))),
]


def media_type_for(path: str) -> str | None:
    ext = os.path.splitext(path)[1].lower()
    if ext in IMAGE_EXTENSIONS:
        return "image"
    if ext in VIDEO_EXTENSIONS:
        return "video"
    return None


def find_media_files(folder: str) -> list[str]:
    """All supported files in a folder and its sub-folders."""
    found = []
    for root, _dirs, files in os.walk(folder):
        found.extend(os.path.join(root, name) for name in files if media_type_for(name))
    return sorted(found)


# --- metadata --------------------------------------------------------------

@dataclass
class MediaInfo:
    media_type: str
    width: int | None
    height: int | None
    duration: float | None = None


def probe(path: str) -> MediaInfo:
    """Read type, resolution and (for videos) duration. Raises on unreadable files."""
    media_type = media_type_for(path)
    if media_type == "image":
        with Image.open(path) as img:
            width, height = img.size
            # EXIF orientation 5-8 means the photo is displayed rotated by 90 degrees.
            if img.getexif().get(0x0112, 1) in (5, 6, 7, 8):
                width, height = height, width
        return MediaInfo("image", width, height)
    if media_type == "video":
        with av.open(path) as container:
            if not container.streams.video:
                raise ValueError("File has no video stream")
            stream = container.streams.video[0]
            duration = None
            if container.duration:
                duration = container.duration / av.time_base
            elif stream.duration and stream.time_base:
                duration = float(stream.duration * stream.time_base)
            return MediaInfo("video", stream.codec_context.width, stream.codec_context.height, duration)
    raise ValueError(f"Unsupported file type: {path}")


def read_video_frame(path: str, seconds: float = 0.0) -> Image.Image:
    """Decode the frame shown at `seconds` into the video and return it as an RGB image."""
    with av.open(path) as container:
        stream = container.streams.video[0]
        stream.thread_type = "AUTO"
        if seconds > 0:
            try:
                # Jumps to the nearest keyframe *before* the target (offset is in
                # microseconds); we then decode forward to the exact frame.
                container.seek(int(seconds * av.time_base), backward=True, any_frame=False)
            except av.error.FFmpegError:
                log.debug("Seek failed in %s, decoding from the start", path)
        frame = None
        for frame in container.decode(stream):
            if frame.time is None or frame.time >= seconds - 0.001:
                break
        if frame is None:
            raise ValueError("Could not decode any video frames")
        return frame.to_image()


# --- thumbnails ------------------------------------------------------------

def thumbnail_name(path: str) -> str:
    """Thumbnail file name derived from the media path and modification time,
    so an edited file gets a fresh thumbnail."""
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        mtime = 0
    key = f"{os.path.normcase(os.path.abspath(path))}|{mtime}"
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:24] + ".jpg"


def create_thumbnail(path: str, media_type: str, thumb_dir: Path, duration: float | None = None) -> str:
    """Write a 16:9 JPEG thumbnail and return its file name."""
    if media_type == "video":
        # Skip a little into the video - first frames are often black.
        at = min(duration * 0.1, 10.0) if duration else 0.0
        img = read_video_frame(path, at)
    else:
        img = imaging.open_rgb(path, draft_size=(THUMB_SIZE[0] * 2, THUMB_SIZE[1] * 2))
    name = thumbnail_name(path)
    thumb_dir.mkdir(parents=True, exist_ok=True)
    imaging.cover(img, THUMB_SIZE).save(thumb_dir / name, "JPEG", quality=85)
    return name


def ensure_thumbnail(db: Database, item: MediaItem, thumb_dir: Path) -> Path | None:
    """Return the item's thumbnail path, recreating it if it was deleted.
    Returns None when the media file itself is missing or unreadable."""
    if item.thumbnail and (thumb_dir / item.thumbnail).exists():
        return thumb_dir / item.thumbnail
    if not os.path.exists(item.path):
        return None
    try:
        name = create_thumbnail(item.path, item.media_type, thumb_dir, item.duration)
    except Exception:  # noqa: BLE001 - a broken file should not break the library view
        log.warning("Could not create thumbnail for %s", item.path, exc_info=True)
        return None
    db.set_thumbnail(item.id, name)
    item.thumbnail = name
    return thumb_dir / name


def delete_thumbnail(item: MediaItem, thumb_dir: Path) -> None:
    if item.thumbnail:
        try:
            (thumb_dir / item.thumbnail).unlink(missing_ok=True)
        except OSError:
            log.warning("Could not delete thumbnail %s", item.thumbnail, exc_info=True)


# --- importing -------------------------------------------------------------

def describe_error(exc: Exception) -> str:
    """Short, user-friendly reason why a file could not be read."""
    if isinstance(exc, UnidentifiedImageError):
        return "not a valid image file"
    if isinstance(exc, av.error.InvalidDataError):
        return "not a valid video file"
    if isinstance(exc, (FileNotFoundError, av.error.FileNotFoundError)):
        return "file not found"
    if isinstance(exc, (PermissionError, av.error.PermissionError)):
        return "access denied"
    return str(exc) or exc.__class__.__name__

@dataclass
class ImportResult:
    added: list[int] = field(default_factory=list)
    skipped: int = 0  # already in the library or unsupported
    failed: list[tuple[str, str]] = field(default_factory=list)  # (path, reason)


ProgressCallback = Callable[[int, int, str], None]


def import_files(db: Database, paths: list[str], thumb_dir: Path,
                 progress: ProgressCallback | None = None) -> ImportResult:
    """Add files to the library (metadata + thumbnail). The files are not copied or moved."""
    result = ImportResult()
    total = len(paths)
    for index, raw_path in enumerate(paths, start=1):
        path = os.path.normpath(os.path.abspath(raw_path))
        if progress:
            progress(index, total, path)
        if not media_type_for(path) or db.has_media(path):
            result.skipped += 1
            continue
        try:
            info = probe(path)
            thumb = create_thumbnail(path, info.media_type, thumb_dir, info.duration)
            media_id = db.add_media(path, info.media_type, info.width, info.height, info.duration,
                                    os.path.getsize(path), thumb)
        except Exception as exc:  # noqa: BLE001 - one bad file should not stop the import
            log.warning("Failed to import %s: %s", path, exc)
            result.failed.append((path, describe_error(exc)))
            continue
        if media_id is None:
            result.skipped += 1
        else:
            result.added.append(media_id)
    log.info("Import finished: %d added, %d skipped, %d failed",
             len(result.added), result.skipped, len(result.failed))
    return result
