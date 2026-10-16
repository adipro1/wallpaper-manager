import zipfile

from wallpaper_manager import diagnostics, media
from wallpaper_manager.config import AppPaths
from wallpaper_manager.database import Database
from wallpaper_manager.wallpaper import WallpaperService

from .samples import make_image
from .test_wallpaper import FakeLivePlayer


def test_diagnostics_zip_has_report_and_logs(tmp_path):
    paths = AppPaths(tmp_path / "data").ensure()
    (paths.log_dir / "wallpaper_manager.log").write_text("2026-10-16 [INFO] main: Starting\n")
    db = Database(paths.db_path)
    media.import_files(db, [str(make_image(tmp_path / "a.png"))], paths.thumb_dir)
    db.set_setting("appearance_mode", "light")
    service = WallpaperService(db, paths, FakeLivePlayer())

    out = diagnostics.save_diagnostics(tmp_path / "diag.zip", db, paths, service)
    with zipfile.ZipFile(out) as archive:
        assert sorted(archive.namelist()) == ["logs/wallpaper_manager.log", "report.txt"]
        report = archive.read("report.txt").decode("utf-8")
    for heading in ("== App ==", "== Windows ==", "== Live wallpaper ==", "== Desktop windows (Explorer) ==",
                    "== Library ==", "== Settings =="):
        assert heading in report
    assert "Windows" in diagnostics.windows_version()
    assert '"total": 1' in report and 'appearance_mode = "light"' in report
    db.close()
