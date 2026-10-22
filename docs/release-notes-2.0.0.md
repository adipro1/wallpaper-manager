Wallpaper Manager 2.0 is a complete rewrite in Kotlin with Compose for Desktop. Your library, playlists, schedule and settings from 1.x carry over automatically (a copy of the old database is kept as `library.v3.bak.db`).

## New in 2.0
- **Videos from the internet**: paste a YouTube, Vimeo, ... link or a plain .mp4/.webm/.m3u8 link; stream it or save it for offline use.
- **Interactive web wallpapers** like Lively: a folder with an `index.html`, an `.html` file, a `.zip` (Lively wallpapers import as they are) or a website. Clicks, moves and the wheel on the empty desktop reach the page.
- **Shader wallpapers** (Shadertoy-style GLSL) with the new **Customize** editor: live preview, compile errors with line numbers, four samples.
- **Audio-reactive wallpapers**: web pages and shaders can react to what your PC plays (analysed live, never recorded).
- **Several displays**: live wallpapers on every display, or a different wallpaper per display.
- **Smarter pausing**: per display when maximized windows cover it.
- A new look ("Origami Fold"), in dark and light.

## Install
Download `WallpaperManager-2.0.0-setup.exe` (no administrator rights needed) or the zip. Installing over 1.x replaces it. The web engine (about 150 MB) is downloaded the first time you use a web or shader wallpaper.

## Tested
Installer, upgrade from 1.x, lock screen and `--self-test` on Windows 11 23H2 (one display). Multiple displays and the Windows 11 24H2 desktop layout are supported but haven't been tested on real hardware yet - please report problems.
