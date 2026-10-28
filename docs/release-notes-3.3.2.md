Wallpaper Manager 3.3.2: a fix for web wallpapers that pause.

## Fixed
- A paused web wallpaper (such as Night sky or Live Earth, or any web page or shader) left the desktop dark grey. That happened whenever it paused on its own: on battery, while windows covered the desktop, while the PC was locked and so on. Now the desktop shows the wallpaper's last picture while it is paused, or your usual desktop picture when there is none to show yet.

## Install
Click "Install now" in Settings, or download `WallpaperManager-3.3.2-setup.exe` (no administrator rights needed) or the zip. Installing over an older version keeps your data. Everything new in 3.3 is in the [3.3.0 notes](https://github.com/adipro1/wallpaper-manager/releases/tag/v3.3.0).

## Tested
Windows 11 23H2, one display, on battery: unit tests; the dark grey desktop under a paused Live Earth reproduced, and the desktop picture shown again by repainting it; Night sky and Live Earth captured correctly after a pause and resume.
