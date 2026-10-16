"""Build a standalone app folder with PyInstaller.

    pip install -r requirements-dev.txt
    python tools/get_libmpv.py      # once, for live wallpapers
    python tools/build.py

Result: dist/WallpaperManager/WallpaperManager.exe (plus its _internal folder and
bin/libmpv-2.dll). Copy the whole WallpaperManager folder to wherever you like;
the app keeps its data in %LOCALAPPDATA%\\WallpaperManager either way.
"""

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAME = "WallpaperManager"
ASSETS = ROOT / "wallpaper_manager" / "assets"
LIBMPV = ROOT / "bin" / "libmpv-2.dll"


def main() -> int:
    command = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm", "--clean",
        "--windowed",                       # no console window
        "--name", NAME,
        "--icon", str(ASSETS / "icon.ico"),
        "--specpath", str(ROOT / "build"),  # keep the generated .spec out of the project root
        "--add-data", f"{ASSETS};wallpaper_manager/assets",
        "--collect-data", "customtkinter",  # its themes and fonts
        "--collect-submodules", "winrt",    # lock screen API, imported lazily so not auto-detected
        "--hidden-import", "pystray._win32",
        str(ROOT / "main.py"),
    ]
    print(" ".join(command))
    subprocess.run(command, check=True, cwd=ROOT)

    app_dir = ROOT / "dist" / NAME
    if LIBMPV.exists():
        (app_dir / "bin").mkdir(exist_ok=True)
        shutil.copy2(LIBMPV, app_dir / "bin" / LIBMPV.name)
        print(f"Copied {LIBMPV.name}")
    else:
        print("Note: bin/libmpv-2.dll not found - live wallpapers won't work in this build. "
              "Run  python tools/get_libmpv.py  and build again.")
    print(f"\nDone: {app_dir / (NAME + '.exe')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
