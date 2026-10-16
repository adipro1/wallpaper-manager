"""Download libmpv, the video engine used for live video wallpapers.

    python tools/get_libmpv.py

Puts libmpv-2.dll into the project's bin/ folder (ignored by Git, ~120 MB).
The build is pinned and its SHA-256 checked. The .7z archive is unpacked with
the tar.exe that ships with Windows 10/11, so no extra tools are needed.
"""

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ARCHIVE = "mpv-dev-x86_64-20260921-git-e76a35ec95.7z"
# Community Windows builds of mpv (https://github.com/shinchiro/mpv-winbuild-cmake) remove
# old builds after a while, so the same file is also kept in this project's own releases.
URLS = [
    f"https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260921/{ARCHIVE}",
    f"https://github.com/adipro1/wallpaper-manager/releases/download/libmpv-20260921/{ARCHIVE}",
]
SHA256 = "361392d64a231830ac79d1c0f91ddb68c6a709accd282b2c87b13889d382fdec"
DLL_NAME = "libmpv-2.dll"
BIN_DIR = Path(__file__).resolve().parent.parent / "bin"


def download(archive: Path) -> bool:
    """Try each source until one gives the file with the expected checksum."""
    for url in URLS:
        print(f"Downloading {url} ...")
        try:
            urllib.request.urlretrieve(url, archive)
        except OSError as exc:
            print(f"  failed: {exc}")
            continue
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest == SHA256:
            return True
        print(f"  checksum mismatch (got {digest})")
    return False


def main() -> int:
    target = BIN_DIR / DLL_NAME
    if target.exists():
        print(f"{target} already exists - nothing to do.")
        return 0

    tar = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "tar.exe"
    if not tar.exists():
        print("Windows tar.exe not found; extract libmpv-2.dll from the archive manually:")
        print(URLS[0])
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "mpv-dev.7z"
        if not download(archive):
            print("Could not download libmpv from any source.")
            return 1
        subprocess.run([str(tar), "-xf", str(archive), "-C", tmp, DLL_NAME], check=True)
        BIN_DIR.mkdir(exist_ok=True)
        shutil.move(str(Path(tmp) / DLL_NAME), target)

    print(f"Installed {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
