"""Build a standalone app folder with PyInstaller, optionally a zip and an installer.

    pip install -r requirements-dev.txt
    python tools/get_libmpv.py      # once, for live wallpapers
    python tools/build.py [--zip] [--installer]

Results in dist/:
    WallpaperManager/WallpaperManager.exe     the app folder (copy it anywhere)
    WallpaperManager-<version>-win64.zip      --zip: the folder zipped, ready to share
    WallpaperManager-<version>-setup.exe      --installer: needs Inno Setup 6 (ISCC.exe)
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from wallpaper_manager import __version__  # noqa: E402

NAME = "WallpaperManager"
ASSETS = ROOT / "wallpaper_manager" / "assets"
LIBMPV = ROOT / "bin" / "libmpv-2.dll"
USER_README = ROOT / "packaging" / "READ ME FIRST.txt"
APP_DIR = ROOT / "dist" / NAME


def build_app() -> None:
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

    if LIBMPV.exists():
        (APP_DIR / "bin").mkdir(exist_ok=True)
        shutil.copy2(LIBMPV, APP_DIR / "bin" / LIBMPV.name)
        print(f"Copied {LIBMPV.name}")
    else:
        print("Note: bin/libmpv-2.dll not found - live wallpapers won't work in this build. "
              "Run  python tools/get_libmpv.py  and build again.")
    shutil.copy2(USER_README, APP_DIR / USER_README.name)


def make_zip() -> Path:
    out = ROOT / "dist" / f"{NAME}-{__version__}-win64.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in sorted(APP_DIR.rglob("*")):
            if file.is_file():
                archive.write(file, Path(NAME) / file.relative_to(APP_DIR))
    print(f"Zip: {out} ({out.stat().st_size / 1e6:.0f} MB)")
    return out


def find_iscc() -> str | None:
    candidates = [shutil.which("iscc"),
                  os.path.expandvars(r"%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"),
                  os.path.expandvars(r"%ProgramFiles%\Inno Setup 6\ISCC.exe"),
                  os.path.expandvars(r"%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe")]
    return next((c for c in candidates if c and os.path.exists(c)), None)


def make_installer() -> Path | None:
    iscc = find_iscc()
    if not iscc:
        print("Inno Setup 6 (ISCC.exe) not found - skipping the installer.")
        return None
    subprocess.run([iscc, f"/DAppVersion={__version__}", str(ROOT / "packaging" / f"{NAME}.iss")], check=True)
    out = ROOT / "dist" / f"{NAME}-{__version__}-setup.exe"
    print(f"Installer: {out}")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Build Wallpaper Manager")
    parser.add_argument("--zip", action="store_true", help="also create the shareable zip")
    parser.add_argument("--installer", action="store_true", help="also create the installer (Inno Setup 6)")
    args = parser.parse_args()

    build_app()
    if args.zip:
        make_zip()
    if args.installer:
        make_installer()
    print(f"\nDone: {APP_DIR / (NAME + '.exe')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
