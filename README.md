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
- SQLite storage for settings and library metadata
- Error logging to a rotating log file

**Planned** — see the roadmap below.

## Requirements

- Windows 10/11 (developed on Windows 11)
- Python 3.12+

FFmpeg does **not** need to be installed: video decoding uses
[PyAV](https://pyav.org/), whose wheels ship with the FFmpeg libraries.

## Getting started

```powershell
py -3.12 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

Run the tests:

```powershell
pip install -r requirements-dev.txt
python -m pytest
```

## Where data is stored

Everything the app generates lives in `%LOCALAPPDATA%\WallpaperManager`:

| Path | Contents |
| --- | --- |
| `library.db` | SQLite database (settings and library metadata) |
| `thumbnails\` | Generated thumbnails |
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
  imaging.py                Pillow resize helpers
  winapi.py                 Windows API helpers (ctypes)
  app.py                    Main window and sidebar navigation
  ui/
    theme.py                Colours and fonts
    icons.py                Icons rendered from the Segoe Fluent Icons font
    widgets.py              Shared widgets (cards, buttons, page base class)
    preview.py              Image / GIF / video preview widget and window
    pages/                  One module per sidebar page
tests/                      pytest tests
```

## Roadmap

1. ✅ Project setup, main GUI, navigation, logging, SQLite
2. ✅ Media import, library, thumbnails, favorites, search, previews
3. Static desktop wallpapers
4. Live video wallpapers behind the desktop icons
5. Lock screen images, including frames extracted from videos
6. Playlists (random / sequential, custom intervals)
7. Time-based scheduling
8. Tray icon, start with Windows, pause on fullscreen apps / battery
9. Polish, tests, packaging
