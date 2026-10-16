"""Start Wallpaper Manager:  python main.py"""

import logging
import sys

from wallpaper_manager import APP_ID, APP_NAME, __version__, winapi
from wallpaper_manager.config import default_paths
from wallpaper_manager.database import Database
from wallpaper_manager.logging_setup import setup_logging


def main() -> int:
    paths = default_paths()
    log_file = setup_logging(paths.log_dir)
    log = logging.getLogger("main")
    log.info("Starting %s %s (data: %s)", APP_NAME, __version__, paths.data_dir)

    winapi.set_app_user_model_id(APP_ID)
    db = Database(paths.db_path)

    # Imported here so logging is configured before the UI modules load.
    from wallpaper_manager.app import WallpaperManagerApp

    try:
        app = WallpaperManagerApp(db, paths)
        app.mainloop()
    except Exception:
        log.critical("Fatal error - see %s", log_file, exc_info=True)
        raise
    finally:
        db.close()
        log.info("Exited")
    return 0


if __name__ == "__main__":
    sys.exit(main())
