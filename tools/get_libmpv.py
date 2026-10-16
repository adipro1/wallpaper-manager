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

# Community Windows builds of mpv: https://github.com/shinchiro/mpv-winbuild-cmake
URL = ("https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260921/"
       "mpv-dev-x86_64-20260921-git-e76a35ec95.7z")
SHA256 = "361392d64a231830ac79d1c0f91ddb68c6a709accd282b2c87b13889d382fdec"
DLL_NAME = "libmpv-2.dll"
BIN_DIR = Path(__file__).resolve().parent.parent / "bin"


def main() -> int:
    target = BIN_DIR / DLL_NAME
    if target.exists():
        print(f"{target} already exists - nothing to do.")
        return 0

    tar = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "tar.exe"
    if not tar.exists():
        print("Windows tar.exe not found; extract libmpv-2.dll from the archive manually:\n" + URL)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "mpv-dev.7z"
        print(f"Downloading {URL} ...")
        urllib.request.urlretrieve(URL, archive)

        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != SHA256:
            print(f"Checksum mismatch!\n expected {SHA256}\n got      {digest}")
            return 1

        subprocess.run([str(tar), "-xf", str(archive), "-C", tmp, DLL_NAME], check=True)
        BIN_DIR.mkdir(exist_ok=True)
        shutil.move(str(Path(tmp) / DLL_NAME), target)

    print(f"Installed {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
