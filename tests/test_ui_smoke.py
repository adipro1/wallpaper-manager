"""Builds the real main window (with a stand-in tray) and visits every page.

Only reads Windows state (current wallpaper / lock screen); never changes it.
"""

import queue
import time

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
