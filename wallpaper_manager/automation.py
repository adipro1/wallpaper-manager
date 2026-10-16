"""Background jobs that run on the main window's timers while the app is open:

- start the saved live wallpaper after launch, and keep its window alive (watchdog)
- apply due schedule rules and advance the running playlist (on a worker thread)
- pause the live wallpaper while nobody can see it (locked, screen off, fullscreen
  app, maximized windows, battery) and resume it afterwards
- check for a newer release once a day
"""

import logging

from . import system
from .desktop_host import display_is_on
from .ui.widgets import run_in_thread

log = logging.getLogger(__name__)

RESTORE_DELAY_MS = 500           # start the saved live wallpaper shortly after the window appears
LIVE_CHECK_MS = 3000             # is the live wallpaper window still alive? (Explorer restarts)
CHANGE_CHECK_MS = 5000           # is a schedule rule or the next playlist item due?
AUTO_PAUSE_CHECK_MS = 2000       # can anyone see the live wallpaper right now?
UPDATE_CHECK_DELAY_MS = 15_000   # look for updates shortly after start, not during it


class Automation:
    def __init__(self, app):
        self.app = app
        self._changing = False  # one automatic wallpaper change at a time

    def start(self) -> None:
        after = self.app.after
        after(RESTORE_DELAY_MS, self._restore_live_wallpaper)
        after(LIVE_CHECK_MS, self._check_live_wallpaper)
        after(CHANGE_CHECK_MS, self._check_due_changes)
        after(AUTO_PAUSE_CHECK_MS, self._check_auto_pause)
        after(UPDATE_CHECK_DELAY_MS, self._check_for_updates)

    # -- live wallpaper -----------------------------------------------------------------

    def _restore_live_wallpaper(self) -> None:
        """Start the live wallpaper that was playing when the app was last closed."""
        run_in_thread(self.app, self.app.wallpapers.restore_on_startup,
                      on_done=lambda started: started and self.app.refresh_visible_page(),
                      on_error=lambda exc: log.warning("Could not restore the live wallpaper: %s", exc))

    def _check_live_wallpaper(self) -> None:
        self.app.after(LIVE_CHECK_MS, self._check_live_wallpaper)
        try:
            self.app.wallpapers.check_live()
        except Exception:  # noqa: BLE001 - keep the watchdog running
            log.exception("Live wallpaper check failed")

    # -- schedule and playlists -----------------------------------------------------------

    def _check_due_changes(self) -> None:
        """Apply a due schedule rule, or advance the running playlist, on a worker thread.
        One change at a time, so the two never change the wallpaper simultaneously."""
        self.app.after(CHANGE_CHECK_MS, self._check_due_changes)
        if self._changing:
            return
        if self.app.scheduler.is_due():
            work = self.app.scheduler.apply_due
        elif self.app.playlists.is_due():
            work = self.app.playlists.advance
        else:
            return
        self._changing = True

        def finished(_result=None):
            self._changing = False
            self.app.refresh_visible_page()

        def failed(exc):
            log.warning("Automatic wallpaper change failed: %s", exc)
            finished()

        run_in_thread(self.app, work, on_done=finished, on_error=failed)

    # -- automatic pausing ----------------------------------------------------------------

    def _check_auto_pause(self) -> None:
        self.app.after(AUTO_PAUSE_CHECK_MS, self._check_auto_pause)
        wallpapers = self.app.wallpapers
        if not wallpapers.video_active:
            return
        try:
            reason = self.pause_reason()
        except OSError:
            log.debug("Auto-pause check failed", exc_info=True)
            reason = None
        before = wallpapers.auto_pause_reason
        wallpapers.update_auto_pause(reason)
        if wallpapers.auto_pause_reason != before:
            self.app.refresh_visible_page()

    def pause_reason(self) -> str | None:
        """Why the live wallpaper should be paused right now (most important first), or None."""
        setting = self.app.db.get_setting
        if setting("pause_when_hidden", True):
            if system.session_locked():
                return "locked"
            if not display_is_on():
                return "screen_off"
        if setting("pause_on_fullscreen", True) and system.fullscreen_app_running():
            return "fullscreen"
        if setting("pause_when_covered", True) and system.desktop_covered():
            return "covered"
        if setting("pause_on_battery", False) and system.on_battery():
            return "battery"
        return None

    # -- updates ----------------------------------------------------------------------------

    def _check_for_updates(self) -> None:
        """Once a day look for a newer release on GitHub; mention each new version once."""
        def done(version):
            if version and self.app.db.get_setting("notified_version") != version:
                self.app.db.set_setting("notified_version", version)
                self.app.tray.notify(f"Version {version} is available. Open Settings to download it.")

        run_in_thread(self.app, self.app.updates.check_if_due, on_done=done, on_error=lambda exc: None)
