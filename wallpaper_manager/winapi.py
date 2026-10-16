"""Small Windows API helpers called through ctypes."""

import ctypes
import logging
import os
import subprocess
import winreg
from ctypes import wintypes

log = logging.getLogger(__name__)

user32 = ctypes.WinDLL("user32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)

SPI_GETDESKWALLPAPER = 0x0073


def set_app_user_model_id(app_id: str) -> None:
    """Give the process its own taskbar identity, so Windows shows our window
    icon instead of grouping it under the generic python.exe icon."""
    try:
        shell32.SetCurrentProcessExplicitAppUserModelID(ctypes.c_wchar_p(app_id))
    except (AttributeError, OSError):
        log.debug("SetCurrentProcessExplicitAppUserModelID unavailable")


def get_transcoded_wallpaper() -> str | None:
    """Windows keeps its own copy of the current wallpaper picture here."""
    path = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Themes", "TranscodedWallpaper")
    return path if os.path.isfile(path) else None


SPI_GETWORKAREA = 0x0030


def get_work_area() -> tuple[int, int, int, int] | None:
    """Primary screen area not covered by the taskbar, as (left, top, right, bottom)
    in physical pixels (the app is DPI aware)."""
    rect = wintypes.RECT()
    if not user32.SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(rect), 0):
        return None
    return rect.left, rect.top, rect.right, rect.bottom


def get_desktop_wallpaper() -> str | None:
    """Return the path of the current desktop wallpaper, or None if unknown."""
    buf = ctypes.create_unicode_buffer(wintypes.MAX_PATH * 2)
    if not user32.SystemParametersInfoW(SPI_GETDESKWALLPAPER, len(buf), buf, 0):
        return None
    return buf.value or None


# --- static desktop wallpaper -------------------------------------------------

SPI_SETDESKWALLPAPER = 0x0014
SPIF_UPDATEINIFILE = 0x01  # save the change in the user profile
SPIF_SENDCHANGE = 0x02  # tell other apps the setting changed

# How Windows positions the picture: (WallpaperStyle, TileWallpaper) registry values
# under HKCU\Control Panel\Desktop. These are the same values the Settings app writes.
WALLPAPER_STYLES = {
    "fill": ("10", "0"),
    "fit": ("6", "0"),
    "stretch": ("2", "0"),
    "center": ("0", "0"),
    "tile": ("0", "1"),
}
_DESKTOP_KEY = r"Control Panel\Desktop"


def set_desktop_wallpaper(path: str, persist: bool = True) -> None:
    """Set the desktop wallpaper image. `path` should be a JPG, PNG or BMP file."""
    flags = SPIF_UPDATEINIFILE | SPIF_SENDCHANGE if persist else 0
    if not user32.SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, ctypes.c_wchar_p(path), flags):
        raise ctypes.WinError(ctypes.get_last_error())


def get_wallpaper_style() -> str:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _DESKTOP_KEY) as key:
            style = str(winreg.QueryValueEx(key, "WallpaperStyle")[0])
            tile = str(winreg.QueryValueEx(key, "TileWallpaper")[0])
    except OSError:
        return "fill"
    for name, values in WALLPAPER_STYLES.items():
        if values == (style, tile):
            return name
    return "fill"


def set_wallpaper_style(style: str) -> None:
    """Write the position style; it takes effect the next time the wallpaper is set."""
    wallpaper_style, tile = WALLPAPER_STYLES[style]
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _DESKTOP_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, "WallpaperStyle", 0, winreg.REG_SZ, wallpaper_style)
        winreg.SetValueEx(key, "TileWallpaper", 0, winreg.REG_SZ, tile)


# --- Recycle Bin -------------------------------------------------------------
# SHFileOperationW with FOF_ALLOWUNDO moves a file to the Recycle Bin instead of
# deleting it permanently, so an accidental "Delete file" can still be undone.

FO_DELETE = 0x0003
FOF_SILENT = 0x0004
FOF_NOCONFIRMATION = 0x0010
FOF_ALLOWUNDO = 0x0040
FOF_NOERRORUI = 0x0400


class SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [
        ("hwnd", wintypes.HWND),
        ("wFunc", wintypes.UINT),
        ("pFrom", wintypes.LPCWSTR),
        ("pTo", wintypes.LPCWSTR),
        ("fFlags", ctypes.c_ushort),
        ("fAnyOperationsAborted", wintypes.BOOL),
        ("hNameMappings", ctypes.c_void_p),
        ("lpszProgressTitle", wintypes.LPCWSTR),
    ]


def send_to_recycle_bin(path: str) -> None:
    """Move a file to the Recycle Bin. Raises OSError on failure."""
    op = SHFILEOPSTRUCTW()
    op.wFunc = FO_DELETE
    # pFrom is a list of paths ending in a double NUL; ctypes adds the final one.
    op.pFrom = os.path.abspath(path) + "\0"
    op.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI
    result = shell32.SHFileOperationW(ctypes.byref(op))
    if result != 0 or op.fAnyOperationsAborted:
        raise OSError(f"Could not move file to the Recycle Bin (error {result:#x}): {path}")


def show_in_explorer(path: str) -> None:
    """Open File Explorer with the file selected."""
    subprocess.Popen(f'explorer /select,"{os.path.normpath(path)}"')


def open_folder(path: str) -> None:
    os.startfile(path)  # noqa: S606 - opens the folder in Explorer
