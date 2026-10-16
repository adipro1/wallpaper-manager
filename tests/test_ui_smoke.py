"""Builds the real main window (with a stand-in tray) and visits every page.

Only reads Windows state (current wallpaper / lock screen); never changes it.
"""

import queue
import time
import tkinter

import customtkinter as ctk
import pytest

from wallpaper_manager import app as app_module
from wallpaper_manager import media
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import Database
from wallpaper_manager.ui import icons

from .samples import make_sample_set


class DummyTray:
    def __init__(self, _wallpapers):
        self.actions = queue.Queue()

    def start(self):
        pass

    def stop(self):
        pass

    def notify(self, _message):
        pass


def pump(window, seconds=0.3):
    end = time.time() + seconds
    while time.time() < end:
        window.update()
        time.sleep(0.01)


@pytest.fixture
def app(tmp_path, monkeypatch):
    monkeypatch.setattr(app_module, "Tray", DummyTray)
    # Test windows must never grab the keyboard from whatever the user is doing.
    monkeypatch.setattr(tkinter.Misc, "focus_force", lambda self: None)
    icons.icon.cache_clear()  # cached icons belong to the previous test's Tk root
    paths = AppPaths(tmp_path / "data").ensure()
    db = Database(paths.db_path)
    media.import_files(db, [str(p) for p in make_sample_set(tmp_path / "media").values()], paths.thumb_dir)
    window = app_module.WallpaperManagerApp(db, paths)
    errors = []
    window.report_callback_exception = lambda exc, val, tb: errors.append(val)
    window.errors = errors
    pump(window)
    yield window
    window.exit_app()
    db.close()
    ctk.set_appearance_mode("dark")


def test_every_page_renders_in_both_themes_and_small_window(app):
    for mode in ("light", "dark"):
        ctk.set_appearance_mode(mode)
        for key, *_rest in app_module.NAV_ITEMS:
            app.show_page(key)
            pump(app, 0.15)
    app.geometry("980x620")
    for key, *_rest in app_module.NAV_ITEMS:
        app.show_page(key)
        pump(app, 0.15)
    assert app.errors == []


def test_library_selection_preview_and_filters(app):
    library = app.show_page("library")
    pump(app)
    assert len(library.items) == 6
    library.select_item(library.items[0])
    pump(app, 0.5)
    library.type_filter.set("Videos")
    library.refresh(reset_page=True)
    assert {m.media_type for m in library.items} == {"video"}
    library.toggle_favorite(library.items[0])
    library.favorites_only.select()
    library.refresh(reset_page=True)
    assert len(library.items) == 1
    assert app.errors == []


def test_close_hides_to_tray_and_shows_again(app):
    app.on_close()
    pump(app)
    assert app.state() == "withdrawn"
    app.tray.actions.put("open")
    pump(app, 0.5)
    assert app.state() == "normal"
    assert app.errors == []


class Click:
    """Stand-in for a Tk click event with modifier keys."""

    def __init__(self, ctrl=False, shift=False):
        self.state = (0x0004 if ctrl else 0) | (0x0001 if shift else 0)


def test_library_multi_select_and_bulk_actions(app, monkeypatch, tmp_path):
    library = app.show_page("library")
    pump(app)
    items = library.items
    library.select_item(items[0], Click())
    library.select_item(items[2], Click(ctrl=True))
    assert library.selected_ids == {items[0].id, items[2].id}
    assert library.details.bulk.winfo_ismapped() or library.details.bulk.grid_info()

    library.select_item(items[4], Click(shift=True))  # range from the last Ctrl+click
    assert library.selected_ids == {m.id for m in items[2:5]}

    library.bulk_favorite(True)
    assert all(app.db.get_media(m.id).favorite for m in items[2:5])

    library.select_all()
    pid = app.db.create_playlist("Mix")
    library._add_to_playlist(pid, "Mix", library.selected_items)
    library._add_to_playlist(pid, "Mix", library.selected_items)  # duplicates are skipped
    assert app.db.get_playlist(pid).item_count == len(items)

    import wallpaper_manager.ui.pages.library as library_module
    monkeypatch.setattr(library_module.messagebox, "askyesno", lambda *a, **k: True)
    library.select_item(items[0], Click())
    library.select_item(items[1], Click(ctrl=True))
    paths = [m.path for m in items[:2]]
    library.bulk_remove()
    assert app.db.count_media() == len(items) - 2
    assert all(__import__("os").path.exists(p) for p in paths)  # files are kept
    assert library.selected_ids == set()

    library.select_item(library.items[0], Click())  # single selection shows the details again
    assert library.details.item is not None and not library.details.bulk.grid_info()
    assert app.errors == []


def test_auto_pause_reason_priority_and_settings(app, monkeypatch):
    from wallpaper_manager import automation as automation_module
    state = {"locked": False, "screen_on": True, "fullscreen": False, "covered": False, "battery": False}
    monkeypatch.setattr(automation_module.system, "session_locked", lambda: state["locked"])
    monkeypatch.setattr(automation_module, "display_is_on", lambda: state["screen_on"])
    monkeypatch.setattr(automation_module.system, "fullscreen_app_running", lambda: state["fullscreen"])
    monkeypatch.setattr(automation_module.system, "desktop_covered", lambda: state["covered"])
    monkeypatch.setattr(automation_module.system, "on_battery", lambda: state["battery"])

    assert app.automation.pause_reason() is None
    state.update(battery=True)
    assert app.automation.pause_reason() is None  # battery pausing is off by default
    app.db.set_setting("pause_on_battery", True)
    assert app.automation.pause_reason() == "battery"
    state.update(covered=True)
    assert app.automation.pause_reason() == "covered"
    state.update(fullscreen=True)
    assert app.automation.pause_reason() == "fullscreen"
    state.update(screen_on=False)
    assert app.automation.pause_reason() == "screen_off"
    state.update(locked=True)
    assert app.automation.pause_reason() == "locked"

    app.db.set_setting("pause_when_hidden", False)
    app.db.set_setting("pause_on_fullscreen", False)
    app.db.set_setting("pause_when_covered", False)
    assert app.automation.pause_reason() == "battery"
