Wallpaper Manager 3.1 "Depth & Music": photos that move like 3D scenes, and wallpapers that know what you're listening to. Your library, playlists, schedule and settings carry over.

## New
- **3D photos** (Library > a picture > "Make it alive…" > 3D photo): AI works out what is near and far in your photo, so it moves like a real 3D scene when the mouse moves, with a slow camera drift when it doesn't. Near things cover what is behind them. The AI model ([Depth Anything V2 Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Small), Apache-2.0, 27 MB) is downloaded the first time and runs on your PC; your pictures never leave it.
- **Layers**: several effects on one picture, each switched on and tuned in its settings: 3D motion, fireflies that fly behind the nearer branches, light rays, fog in the distance, rain and snow when it does outside, music bars and a soft light at the mouse.
- **Now Playing**: the app knows what Spotify, your browser or any other player is playing.
  - Web wallpapers made for Lively that show the song (`livelyCurrentTrack`) work as they do there.
  - New effect **Music colors**: the picture takes on the colors of the album art and glows with the bass.
  - **Colors follow the music** (Settings > Appearance): the app's accent takes the album art's color while a song plays, and the Windows accent too when "Match the Windows accent color to the wallpaper" is on. It goes back 10 seconds after the music stops.
- Shaders can use `iMusic` (the album art's color and whether music plays) and AI depth maps: `// @depth depth photo`.

## Fixed
- Closing the app for an update now shuts the web engine down cleanly.

## Install
Click "Install now" in Settings, or download `WallpaperManager-3.1.0-setup.exe` (no administrator rights needed) or the zip. Installing over an older version keeps your data. The installer is about 7 MB bigger (the AI runtime).

## Tested
Windows 11 23H2, one display: unit and UI tests; the real depth model on a test scene and a photo; every effect compiled and played in the real Chromium behind the desktop icons; the media watcher against a real browser video.
