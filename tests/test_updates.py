import urllib.error

import pytest

from wallpaper_manager.database import Database
from wallpaper_manager.updates import UpdateChecker, parse_version


def test_parse_version_orders_numerically():
    assert parse_version("v1.10.0") > parse_version("1.9.9")
    assert parse_version("1.0.0") == (1, 0, 0)
    assert parse_version("v2.0.0-beta") > parse_version("1.99")


class Clock:
    now = 1_000_000.0

    def __call__(self):
        return self.now


def make(tmp_path, latest="1.1.0", current="1.0.0"):
    db = Database(tmp_path / "t.db")
    calls = []

    def fetch():
        calls.append(1)
        if isinstance(latest, Exception):
            raise latest
        return latest

    clock = Clock()
    return db, UpdateChecker(db, fetch=fetch, clock=clock, current=current), calls, clock


def test_reports_newer_version_and_checks_once_a_day(tmp_path):
    db, checker, calls, clock = make(tmp_path)
    assert checker.check_if_due() == "1.1.0"
    assert checker.check_if_due() == "1.1.0" and len(calls) == 1  # cached
    clock.now += 24 * 3600
    checker.check_if_due()
    assert len(calls) == 2
    db.close()


def test_same_or_older_version_is_not_an_update(tmp_path):
    db, checker, calls, clock = make(tmp_path, latest="1.0.0")
    assert checker.check_if_due() is None
    db.close()


def test_offline_and_disabled(tmp_path):
    db, checker, calls, clock = make(tmp_path, latest=OSError("offline"))
    assert checker.check_if_due() is None
    assert db.get_setting("last_update_check") is None  # retried next time
    db.set_setting("check_for_updates", False)
    assert checker.check_if_due() is None and len(calls) == 1
    db.close()


def test_manual_check_reports_errors(tmp_path):
    db, checker, calls, clock = make(tmp_path, latest=OSError("offline"))
    with pytest.raises(OSError):
        checker.check_if_due(force=True)
    db.close()


def test_404_means_no_release_yet(tmp_path):
    db, checker, calls, clock = make(tmp_path, latest=urllib.error.HTTPError("u", 404, "Not Found", None, None))
    assert checker.check_if_due(force=True) is None
    db.close()
