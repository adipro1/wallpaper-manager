"""Diagnostics file for bug reports: a zip with a readable report and the log files.

Contains the Windows version, screen setup, the desktop window layout (the most
likely thing to differ on other PCs, e.g. Windows 11 24H2), video engine
status, settings and library counts. No media files are included.
"""

import ctypes
import json
import logging
import platform
import sys
import winreg
import zipfile
from datetime import datetime
from pathlib import Path

from . import APP_NAME, __version__, desktop_host, lockscreen, system, winapi
from .config import AppPaths
from .database import Database
from .live_wallpaper import libmpv_available
from .wallpaper import WallpaperService

log = logging.getLogger(__name__)


def windows_version() -> str:
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion") as key:
            def value(name, default=""):
                try:
                    return winreg.QueryValueEx(key, name)[0]
                except OSError:
                    return default
            build = int(value("CurrentBuild", "0") or 0)
            # The registry still says "Windows 10" on Windows 11; build 22000+ is Windows 11.
            product = str(value("ProductName")).replace("Windows 10", "Windows 11") if build >= 22000 \
                else value("ProductName")
            return f"{product} {value('DisplayVersion')} (build {build}.{value('UBR', 0)})"
    except OSError:
        return platform.platform()


def _section(title: str, lines: list[str]) -> str:
    return f"== {title} ==\n" + "\n".join(lines) + "\n"


def _safe(func, default="unavailable"):
    try:
        return func()
    except Exception as exc:  # noqa: BLE001 - a diagnostics report must never fail
        return f"{default} ({exc})"


def build_report(db: Database, paths: AppPaths, wallpapers: WallpaperService) -> str:
    live = wallpapers.live
    host = getattr(live, "host", None)
    scale = _safe(lambda: ctypes.windll.user32.GetDpiForSystem() / 96)
    sections = [
        _section("App", [
            f"{APP_NAME} {__version__}",
            f"Packaged build: {getattr(sys, 'frozen', False)}  ({sys.executable})",
            f"Python {sys.version.split()[0]}",
            f"Data folder: {paths.data_dir}",
            f"Report created: {datetime.now():%Y-%m-%d %H:%M:%S}",
        ]),
        _section("Windows", [
            windows_version(),
            f"Display scale: {scale}",
            f"Work area (px): {_safe(winapi.get_work_area)}",
            f"On battery: {_safe(system.on_battery)}",
            f"Fullscreen app running: {_safe(system.fullscreen_app_running)}",
            f"Windows Spotlight on lock screen: {_safe(lockscreen.spotlight_enabled)}",
            f"Current desktop wallpaper: {_safe(winapi.get_desktop_wallpaper)}",
            f"Start with Windows: {_safe(system.startup_enabled)}",
        ]),
        _section("Live wallpaper", [
            f"libmpv found: {libmpv_available()}",
            f"Playing: {live.active}  paused: {live.paused}  auto-pause reason: {wallpapers.auto_pause_reason}",
            f"mpv version: {_safe(lambda: live.player.mpv_version) if live.active else '-'}",
            f"GPU decoding: {_safe(lambda: live.player.hwdec_current) if live.active else '-'}",
            f"Window behind the icons: {_safe(host.is_behind_icons) if host else '-'}",
            f"Other wallpaper apps in the layer: {_safe(live.competing_apps) if live.active else '-'}",
        ]),
        _section("Desktop windows (Explorer)", _safe(desktop_host.describe_desktop, []) or ["(none found)"]),
        _section("Library", [
            json.dumps(_safe(db.media_stats)),
            f"Playlists: {len(db.list_playlists())}   Schedule rules: {len(db.list_rules())}",
        ]),
        _section("Settings", [f"{row['key']} = {row['value']}"
                              for row in db.query("SELECT key, value FROM settings ORDER BY key")]),
    ]
    return "\n".join(sections)


def save_diagnostics(dest: Path, db: Database, paths: AppPaths, wallpapers: WallpaperService) -> Path:
    """Write the report and all log files into one zip and return its path."""
    dest = Path(dest)
    report = build_report(db, paths, wallpapers)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("report.txt", report)
        for log_file in sorted(paths.log_dir.glob("*.log*")):
            archive.write(log_file, f"logs/{log_file.name}")
    log.info("Saved diagnostics to %s", dest)
    return dest
