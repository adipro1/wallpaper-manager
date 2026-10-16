"""File-system locations used by the app.

All generated data (database, thumbnails, logs) lives in
%LOCALAPPDATA%\\WallpaperManager so it never ends up in the project folder,
OneDrive or Git. Set the WPM_DATA_DIR environment variable to override it.
"""

import os
from dataclasses import dataclass
from pathlib import Path

from . import APP_ID


@dataclass(frozen=True)
class AppPaths:
    data_dir: Path

    @property
    def db_path(self) -> Path:
        return self.data_dir / "library.db"

    @property
    def thumb_dir(self) -> Path:
        return self.data_dir / "thumbnails"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def cache_dir(self) -> Path:
        return self.data_dir / "cache"

    def ensure(self) -> "AppPaths":
        for folder in (self.data_dir, self.thumb_dir, self.log_dir, self.cache_dir):
            folder.mkdir(parents=True, exist_ok=True)
        return self


def default_paths() -> AppPaths:
    override = os.environ.get("WPM_DATA_DIR")
    if override:
        base = Path(override)
    else:
        local = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        base = Path(local) / APP_ID
    return AppPaths(base).ensure()
