"""Checks GitHub once a day for a newer release.

Only reads the public "latest release" information - nothing is sent and
nothing is downloaded or installed automatically.
"""

import json
import logging
import time
import urllib.error
import urllib.request

from . import __version__
from .database import Database

log = logging.getLogger(__name__)

REPO = "adipro1/wallpaper-manager"
LATEST_RELEASE_API = f"https://api.github.com/repos/{REPO}/releases/latest"
RELEASES_PAGE = f"https://github.com/{REPO}/releases/latest"
CHECK_INTERVAL_SECONDS = 24 * 3600


def parse_version(text: str) -> tuple[int, ...]:
    """'v1.10.2' -> (1, 10, 2); anything unparsable counts as 0."""
    parts = []
    for part in text.strip().lstrip("vV").split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def fetch_latest_version(timeout: float = 8.0) -> str:
    """Version number of the latest published release, e.g. '1.1.0'."""
    request = urllib.request.Request(LATEST_RELEASE_API, headers={
        "Accept": "application/vnd.github+json", "User-Agent": f"WallpaperManager/{__version__}"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)["tag_name"].lstrip("vV")


class UpdateChecker:
    def __init__(self, db: Database, fetch=fetch_latest_version, clock=time.time, current: str = __version__):
        self.db = db
        self.fetch = fetch
        self.clock = clock
        self.current = current

    @property
    def enabled(self) -> bool:
        return bool(self.db.get_setting("check_for_updates", True))

    def available_version(self) -> str | None:
        """Newer version found by the last check, if any (no network access)."""
        latest = self.db.get_setting("latest_version")
        return latest if latest and parse_version(latest) > parse_version(self.current) else None

    def check_if_due(self, force: bool = False) -> str | None:
        """Ask GitHub at most once a day (or now with force=True). Call from a worker thread.
        Returns the newer version number, or None."""
        if not self.enabled and not force:
            return None
        last = self.db.get_setting("last_update_check", 0) or 0
        if force or self.clock() - last >= CHECK_INTERVAL_SECONDS:
            try:
                latest = self.fetch()
            except urllib.error.HTTPError as exc:
                if exc.code != 404:
                    return self._failed(exc, force)
                latest = None  # 404: no release has been published yet
            except Exception as exc:  # noqa: BLE001 - offline etc.
                return self._failed(exc, force)
            self.db.set_setting("last_update_check", self.clock())
            self.db.set_setting("latest_version", latest)
            log.info("Update check: latest release is %s (running %s)", latest, self.current)
        return self.available_version()

    def _failed(self, exc: Exception, force: bool) -> str | None:
        """Automatic checks fail silently and retry next time; a manual check reports the error."""
        log.info("Update check failed: %s", exc)
        if force:
            raise exc
        return self.available_version()
