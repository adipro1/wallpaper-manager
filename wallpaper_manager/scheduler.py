"""Time-based wallpaper schedule, e.g. 07:00 → morning.jpg, 18:00 → city.mp4.

Rules repeat every day. At any moment the *active* rule is the one with the
latest time that is not after now (wrapping round to yesterday's last rule
before the first rule of the day). Each occurrence of a rule is applied once:
the last applied occurrence is saved, so after a restart the app catches up
with the rule that should be showing, but doesn't re-apply it every time.

Editing the rules or switching the schedule on doesn't change the wallpaper
straight away - changes take effect at the next rule time (or via "Apply now").
"""

import logging
from datetime import datetime, timedelta
from typing import Callable

from .database import Database, ScheduleRule
from .playlists import PlaylistPlayer
from .wallpaper import WallpaperService

log = logging.getLogger(__name__)


def parse_time(text: str) -> str:
    """'7:5' / '07:05' / '0705' -> '07:05'. Raises ValueError for invalid times."""
    text = text.strip()
    if ":" in text:
        hours, minutes = text.split(":", 1)
    elif text.isdigit() and len(text) in (3, 4):
        hours, minutes = text[:-2], text[-2:]
    else:
        raise ValueError(f"“{text}” is not a time (use HH:MM)")
    hours, minutes = int(hours), int(minutes)
    if not (0 <= hours < 24 and 0 <= minutes < 60):
        raise ValueError(f"“{text}” is not a valid time")
    return f"{hours:02}:{minutes:02}"


class Scheduler:
    def __init__(self, db: Database, wallpapers: WallpaperService, playlists: PlaylistPlayer,
                 clock: Callable[[], datetime] = datetime.now):
        self.db = db
        self.wallpapers = wallpapers
        self.playlists = playlists
        self.clock = clock

    # -- settings ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        return bool(self.db.get_setting("schedule_enabled", True))

    def set_enabled(self, enabled: bool) -> None:
        self.db.set_setting("schedule_enabled", enabled)
        self.mark_current_applied()

    # -- working out which rule applies ---------------------------------------------

    def _rules(self) -> list[ScheduleRule]:
        return [r for r in self.db.list_rules() if r.enabled]

    def _occurrences(self, now: datetime) -> list[tuple[datetime, ScheduleRule]]:
        """Yesterday's, today's and tomorrow's occurrence of every enabled rule, in time order."""
        result = []
        for day_offset in (-1, 0, 1):
            day = (now + timedelta(days=day_offset)).replace(hour=0, minute=0, second=0, microsecond=0)
            for rule in self._rules():
                result.append((day + timedelta(minutes=rule.minutes), rule))
        return sorted(result, key=lambda pair: (pair[0], pair[1].id))

    def active(self) -> tuple[datetime, ScheduleRule] | None:
        """The rule that should be showing now, with the time it started."""
        now = self.clock()
        past = [pair for pair in self._occurrences(now) if pair[0] <= now]
        return past[-1] if past else None

    def upcoming(self) -> tuple[datetime, ScheduleRule] | None:
        now = self.clock()
        return next((pair for pair in self._occurrences(now) if pair[0] > now), None)

    @staticmethod
    def _key(occurrence: tuple[datetime, ScheduleRule]) -> str:
        when, rule = occurrence
        return f"{rule.id}@{when.isoformat(timespec='minutes')}"

    # -- applying ------------------------------------------------------------------------

    def is_due(self) -> bool:
        if not self.enabled:
            return False
        current = self.active()
        return current is not None and self._key(current) != self.db.get_setting("schedule_last_applied")

    def mark_current_applied(self) -> None:
        """Treat the rule that is active right now as done (used after edits)."""
        current = self.active()
        self.db.set_setting("schedule_last_applied", self._key(current) if current else None)

    def apply_due(self) -> ScheduleRule | None:
        """Apply the active rule if it hasn't been applied yet. Call from a worker thread."""
        if not self.is_due():
            return None
        current = self.active()
        # Mark first: if the file is broken we don't want to retry every few seconds.
        self.db.set_setting("schedule_last_applied", self._key(current))
        self.apply_rule(current[1])
        return current[1]

    def apply_rule(self, rule: ScheduleRule) -> None:
        if rule.playlist_id:
            self.playlists.start(rule.playlist_id)
        else:
            item = self.db.get_media(rule.media_id)
            if item is None:
                raise ValueError("The wallpaper of this rule is no longer in the library")
            self.playlists.stop()
            self.wallpapers.set(item)
        log.info("Schedule: applied %s → %s", rule.time, rule.target_name)
