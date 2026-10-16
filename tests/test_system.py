import uuid

from wallpaper_manager import system


def test_single_instance_and_show_signal():
    name = f"WallpaperManagerTest-{uuid.uuid4().hex}"
    first = system.SingleInstance(name)
    second = system.SingleInstance(name)
    try:
        assert not first.already_running and second.already_running
        assert not first.show_requested()
        second.ask_existing_to_show()
        assert first.show_requested()
        assert not first.show_requested()  # auto-reset: one request, one show
    finally:
        second.close()
        first.close()


def test_startup_command_runs_main_minimized_without_console():
    command = system.startup_command()
    assert "pythonw.exe" in command and command.endswith('main.py" --minimized')


def test_power_and_fullscreen_queries_return_booleans():
    assert isinstance(system.on_battery(), bool)
    assert isinstance(system.fullscreen_app_running(), bool)
