Wallpaper Manager 2.1 is a big feature release. Your library, playlists, schedule and settings carry over (a copy of the old database is kept as `library.v4.bak.db`).

## New
- **Discover**: search wallhaven.cc and add a picture to your library, or set it, with one click.
- **Make a picture alive**: Ken Burns zoom, parallax that follows the mouse, rain on glass or snowfall, for any picture.
- **Wallpaper settings**: Lively wallpapers with a `LivelyProperties.json` get their sliders, colors and switches in the Library; shaders can declare settings with comments (`// @slider speed 0 2 1 Speed`) and use pictures (`// @image`).
- **Wallpaper Engine**: import video and web wallpapers from your Steam workshop subscriptions, settings included.
- **Global keyboard shortcuts** (switch them on in Settings) and a **command line** (`WallpaperManager.exe --next`, `--set`, `--playlist`, `--update`, ... see `--help`).
- **Schedule by the sun**: rules at `sunrise`, `sunset - 30`, ... for a city you pick (worked out offline).
- **Pause for apps**: while any other app is in front, or while apps you choose are in front or running.
- **Library**: tags, missing-file finder, watched folders that keep the library up to date by themselves.
- **Lock screen slideshow** from a playlist.
- **Accent color from the wallpaper** (optional; your own color comes back when you switch it off).
- **Backup and restore** in one `.wpmbackup` file, for a new PC.
- **Performance** panel with CPU, memory and GPU use; frame rate caps for shaders and web wallpapers.
- **One-click updates** from now on: "Install now" in Settings (or `--update`).
- **German** translation (Settings > Appearance > Language), better keyboard and screen reader support, "reduce motion".

## Install
Download `WallpaperManager-2.1.0-setup.exe` (no administrator rights needed) or the zip. Installing over 2.0 or 1.x keeps your data.

## Tested
Windows 11 23H2, one display: the unit and UI tests, real Chromium and desktop tests, the accent color and lock screen on a real PC, and updating 2.0 -> 2.1 with the new one-click update.
