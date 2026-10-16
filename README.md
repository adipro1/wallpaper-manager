# Wallpaper Manager

A Windows 11 desktop utility for managing static and live (video) wallpapers,
built with Python, CustomTkinter, Pillow and SQLite.

> Status: in development. See [Roadmap](#roadmap) for what works today.

## Features

**Working now**

- Modern sidebar UI (Home, Desktop, Lock Screen, Library, Playlists, Schedule, Settings)
- Dark / light / system appearance, saved between sessions
- Import JPG, PNG, WEBP, GIF, MP4 and WEBM files (individual files or whole folders)
- Wallpaper library with thumbnails, resolution, duration and file size
- Favorites, search by name, image/video filter, sorting, pagination
- Live previews: images, animated GIFs and muted, looping videos (in the
  library and in a large preview window)
- Remove from library (keeps the file) or delete file (moves it to the Recycle Bin)
- Home page with the current desktop wallpaper, library stats and recent imports
- Static desktop wallpapers from the Desktop page or the Library, with Fill / Fit /
  Stretch / Center / Tile positioning and a Random button (WEBP and GIF are
  converted to PNG automatically, since Windows doesn't accept them reliably)
- Your original wallpaper is backed up before the first change and can be
  restored with **Restore original**
- **Live video wallpapers** (MP4/WEBM) that play *behind the desktop icons*:
  looping, muted by default, pause / resume / stop, Fill or Fit, optional sound,
  switching videos without flicker, and resuming the last video on start
- SQLite storage for settings and library metadata
- Error logging to a rotating log file

**Planned** — see the roadmap below.

## Requirements

- Windows 10/11 (developed on Windows 11)
- Python 3.12+

FFmpeg does **not** need to be installed: thumbnails and previews use
[PyAV](https://pyav.org/), whose wheels ship with the FFmpeg libraries.

Live wallpapers are played by [libmpv](https://mpv.io/) (GPU decoding, very
low CPU use). Its DLL is ~120 MB, so it is not stored in Git; download it once
with `python tools/get_libmpv.py` (a pinned build, checked with SHA-256, saved
to `bin\libmpv-2.dll`). Everything else works without it.

## Getting started

```powershell
py -3.12 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python tools/get_libmpv.py   # once, for live video wallpapers
python main.py
```

Run the tests:

```powershell
pip install -r requirements-dev.txt
python -m pytest
```

## How live wallpapers work

Explorer draws the desktop in two layers: a `WorkerW` window that paints the
wallpaper and, above it, the window holding the icons (`SHELLDLL_DefView`).
The app sends Explorer's `Progman` window the (undocumented) message `0x052C`
to create that split, then creates its own window as a child of the wallpaper
layer. libmpv renders the video into that window, so it appears above the
wallpaper and below the icons. See `wallpaper_manager/desktop_host.py`.

- Closing the app stops the video and repaints the static wallpaper; the video
  starts again next time (can be turned off in Settings).
- If Explorer restarts, the app notices within a few seconds and recreates the window.
- Other live wallpaper apps (e.g. Lively Wallpaper, Wallpaper Engine) use the
  same layer. If one is running, the Desktop page warns that it may cover the
  video - pause or close it.
- Tested on Windows 11 23H2. Windows 11 24H2 changed the desktop window layout;
  the 24H2 code path follows the known approach but has not been tested yet.

## Where data is stored

Everything the app generates lives in `%LOCALAPPDATA%\WallpaperManager`:

| Path | Contents |
| --- | --- |
| `library.db` | SQLite database (settings and library metadata) |
| `thumbnails\` | Generated thumbnails |
| `backup\` | Copy of your wallpaper from before the first change |
| `cache\` | Converted wallpaper images (e.g. WEBP → PNG) |
| `logs\wallpaper_manager.log` | Log file (rotates at 1 MB) |

Your media files are never copied or moved; the database only stores their
paths and metadata (file name, type, resolution, duration, size, favorite
flag, date added). **Remove** only takes an item out of the library.
**Delete file from disk…** asks for confirmation and moves the file to the
Recycle Bin. Set the `WPM_DATA_DIR` environment variable to use a
different data folder (handy for testing).

## Project layout

```
main.py                     Entry point
wallpaper_manager/
  config.py                 Data folder locations
  database.py               SQLite schema, migrations, queries
  logging_setup.py          Log file + uncaught error logging
  media.py                  File types, metadata (Pillow / PyAV), thumbnails, importing
  wallpaper.py              Applying wallpapers, original backup / restore, live state
  live_wallpaper.py         libmpv video player for live wallpapers
  desktop_host.py           Win32 window behind the desktop icons (WorkerW)
  imaging.py                Pillow resize helpers
  winapi.py                 Windows API helpers (ctypes)
  app.py                    Main window and sidebar navigation
  ui/
    theme.py                Colours and fonts
    icons.py                Icons rendered from the Segoe Fluent Icons font
    widgets.py              Shared widgets (cards, buttons, page base class)
    preview.py              Image / GIF / video preview widget and window
    thumbs.py               Thumbnail images with badges, cached
    chooser.py              Searchable tile grid for picking a wallpaper
    pages/                  One module per sidebar page
tools/get_libmpv.py         Downloads libmpv-2.dll into bin/
tests/                      pytest tests
```

## Roadmap

1. ✅ Project setup, main GUI, navigation, logging, SQLite
2. ✅ Media import, library, thumbnails, favorites, search, previews
3. ✅ Static desktop wallpapers
4. ✅ Live video wallpapers behind the desktop icons
5. Lock screen images, including frames extracted from videos
6. Playlists (random / sequential, custom intervals)
7. Time-based scheduling
8. Tray icon, start with Windows, pause on fullscreen apps / battery
9. Polish, tests, packaging
