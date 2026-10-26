package wpm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import wpm.data.Database
import wpm.data.MediaItem
import wpm.data.Settings
import wpm.media.MediaLibrary
import wpm.media.findMediaFiles
import wpm.media.mediaTypeFor
import wpm.engine.AudioCapture
import wpm.engine.LiveKind
import wpm.engine.LivePlayer
import wpm.engine.LiveRouter
import wpm.engine.LiveSource
import wpm.engine.MpvLivePlayer
import wpm.engine.MultiLive
import wpm.win32.Monitors
import wpm.engine.WebLivePlayer
import wpm.media.SHADER_EXTENSIONS
import wpm.media.Shaders
import wpm.media.WebWallpapers
import wpm.media.WallpaperProperties
import wpm.media.WallpaperProperty
import kotlinx.serialization.json.JsonPrimitive
import wpm.media.VideoReader
import wpm.media.UrlImporter
import wpm.media.YtDlp
import wpm.mpv.Mpv
import wpm.mpv.NativeBin
import wpm.mpv.MpvVideoReader
import wpm.services.Automation
import wpm.services.LockScreenApi
import wpm.services.PlaylistPlayer
import wpm.services.Scheduler
import wpm.services.UpdateChecker
import wpm.services.SystemProbe
import wpm.services.WindowsSystemProbe
import wpm.services.LockScreenService
import wpm.services.PowerShellLockScreen
import wpm.services.WallpaperService
import wpm.ui.ThumbnailCache
import wpm.win32.CommandReply
import wpm.win32.DesktopWallpaperApi
import wpm.win32.GlobalHotkeys
import wpm.win32.Hotkey
import wpm.win32.WindowsDesktop
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

enum class Page(val label: String) {
    Home("Home"),
    Desktop("Desktop"),
    LockScreen("Lock Screen"),
    Library("Library"),
    Discover("Discover"),
    Playlists("Playlists"),
    Schedule("Schedule"),
    Customize("Customize"),
    Settings("Settings"),
}

enum class ToastKind { Info, Success, Error }

data class ToastMessage(val text: String, val kind: ToastKind, val id: Long = System.nanoTime())

/** Keyboard shortcuts the window forwards to the visible page. */
enum class Shortcut { Find, SelectAll, Delete, Escape }

data class ImportProgress(val done: Int, val total: Int, val current: String)

private val log = logger("app")

/** Everything the UI needs, shared by all pages. Lives as long as the process. */
class AppState(
    val paths: AppPaths,
    val db: Database,
    startMinimized: Boolean,
    desktopApi: DesktopWallpaperApi = WindowsDesktop,
    lockScreenApi: LockScreenApi = PowerShellLockScreen(paths.cacheDir),
    live: LivePlayer? = null,
    videoReader: VideoReader? = if (Mpv.available) MpvVideoReader(paths.cacheDir.resolve("frames")) else null,
    private val systemProbe: SystemProbe = WindowsSystemProbe,
    /** Where the Discover page finds wallpapers online. */
    val online: wpm.media.WallpaperSource = wpm.media.Wallhaven(),
    /** Where "Living weather" gets the weather outside. */
    weatherSource: wpm.services.WeatherSource = wpm.services.OpenMeteo(),
    /** Where "Now playing" learns what music plays. */
    mediaWatcher: wpm.services.MediaWatcher = wpm.services.PowerShellMediaWatcher(paths.cacheDir, paths.cacheDir.resolve("album-art")),
    /** Draws a desktop widget (a see-through window behind the icons). */
    widgetOverlay: (wpm.widgets.WidgetKind) -> wpm.win32.Overlay = { wpm.win32.DesktopOverlay(it.name.lowercase()) },
) {
    val settings = Settings(db)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val library = MediaLibrary(db, paths.thumbDir, videoReader)
    /** Status of the web engine while it starts or downloads, for the UI. */
    var webEngineStatus by mutableStateOf<String?>(null)
        private set
    val chromiumDir: Path = paths.dataDir.resolve("chromium")

    /** Starts the web engine (downloading it the first time), showing progress. Blocking. */
    fun startChromium(): org.cef.CefApp = try {
        wpm.engine.Chromium.get(chromiumDir, settings.boolean("web_muted", true)) { webEngineStatus = it }
    } finally {
        webEngineStatus = null
    }
    /** One player per display: mpv for videos, Chromium for pages and shaders (created when first needed). */
    private val displays: MultiLive? = if (live == null) MultiLive(Monitors::all) { display ->
        val area = { Monitors.all().firstOrNull { it.id == display.id }?.bounds ?: display.bounds }
        LiveRouter(MpvLivePlayer(area)) { WebLivePlayer(chromiumDir, { webEngineStatus = it }, area) }
    }.also { it.allDisplays = settings.boolean("live_all_displays", true) } else null
    private val livePlayer: LivePlayer = live ?: displays!!

    /** Web engines that exist right now (one per display showing a page or shader). */
    fun webEngines(): List<WebLivePlayer> =
        displays?.players()?.values?.mapNotNull { (it as? LiveRouter)?.web as? WebLivePlayer } ?: emptyList()

    /** System audio levels for audio-reactive wallpapers (runs only while one is showing). */
    val audio = AudioCapture { levels -> webEngines().forEach { if (it.wantsAudio) it.deliverAudio(levels) } }
    val wallpapers = WallpaperService(db, paths, desktopApi, livePlayer).also {
        it.videosAvailable = videoReader != null
        it.sourceFor = ::liveSourceFor
    }
    val web = WebWallpapers(db, library, paths.dataDir.resolve("web"))
    /** Built-in live wallpapers of the real world (the night sky). */
    val scenes = wpm.media.Scenes(db, settings, web, paths.dataDir.resolve("scenes"))
    /** Yesterday's satellite pictures for Live Earth (downloaded when it is first shown, then daily). */
    val earth = wpm.services.EarthImagery(scenes.folderOf(wpm.media.Scenes.EARTH).resolve("textures"))
    private val iss = wpm.services.IssTracker()
    /** A soft sound in the background that follows the weather (Settings > Ambient sound). */
    val ambience = wpm.services.Ambience(settings, paths.cacheDir.resolve("ambient"))
    /** [ambience] is on, for the UI and the tray. */
    var ambientOn by mutableStateOf(ambience.enabled)
        private set

    fun setAmbient(on: Boolean) {
        ambientOn = on
        scope.launch(Dispatchers.IO) { runCatching { ambience.setEnabled(on) } }
    }
    val properties = WallpaperProperties(db)
    val missing = wpm.media.MissingFiles(db)
    /** AI depth maps for 3D photos (the model is downloaded the first time one is made). */
    val depthMaps = wpm.media.DepthMaps(paths.dataDir.resolve("models"), paths.cacheDir.resolve("depth"))
    val shaders = Shaders(db, web, paths.dataDir.resolve("shaders"), paths.cacheDir.resolve("shader-pages"), properties) { picture ->
        if (depthMaps.installed) depthMaps.of(picture) else null
    }.also { it.cleanPreviews() }
    val lockScreen = LockScreenService(db, paths, lockScreenApi, videoReader)
    val ytDlp = YtDlp(NativeBin.dir.resolve("yt-dlp.exe"))
    val urls = UrlImporter(db, library, ytDlp, downloadFolder())
    val playlists: PlaylistPlayer = PlaylistPlayer(db, wallpapers, place = { scheduler.place ?: wpm.services.guessCity() })
    val lockSlideshow = wpm.services.LockScreenSlideshow(db, lockScreen)
    val accent = wpm.services.AccentFromWallpaper(settings)
    /** Windows' "accent color on Start and taskbar", so the taskbar takes the wallpaper's color. */
    val taskbarTint = wpm.services.TaskbarTint(settings)
    /** Wallpaper Manager as the Windows screensaver. */
    val screensaverSetup = wpm.services.ScreensaverSetup(settings)
    /** What is playing: for wallpapers that show it, and for "colors follow the music". */
    val nowPlaying = wpm.services.NowPlaying(mediaWatcher) { track ->
        currentTrack = track
        scope.launch { pushTrack() }
    }
    /** [nowPlaying]'s track, for the UI. */
    var currentTrack by mutableStateOf<wpm.services.Track?>(null)
        private set
    /** Watched folders; new and deleted files show up in the library by themselves. */
    val watched = wpm.services.WatchedFolders(db, library) { change ->
        libraryChanged()
        val parts = buildList {
            if (change.added > 0) add(tr("{0} new", change.added))
            if (change.removed > 0) add(tr("{0} removed", change.removed))
            if (change.moved > 0) add(tr("{0} moved", change.moved))
        }
        toast(tr("Watched folders: {0}", parts.joinToString(", ")))
    }
    val scheduler: Scheduler = Scheduler(db, wallpapers, playlists)
    /** The sun and weather where the user is (the Schedule's city, or a guess from the time zone), for shaders. */
    val world = wpm.services.World({ scheduler.place ?: wpm.services.guessCity() }, source = weatherSource)
    val updates = UpdateChecker(db)
    private val usage by lazy { wpm.win32.ResourceMonitor() }
    /** Clock, weather, music, system and calendar widgets on the desktop. */
    val widgets = wpm.widgets.DesktopWidgets(settings, ::widgetData, newOverlay = widgetOverlay)
    /** Shows a tray notification (set by the window code). */
    var notify: (String) -> Unit = {}
    /** False when libmpv is missing: videos can't be imported or played. */
    val videoSupport: Boolean = videoReader != null
    val thumbs = ThumbnailCache(library)

    /** The main window, for file dialogs. */
    var window: Window? = null

    var page by mutableStateOf(Page.Home)
    var windowVisible by mutableStateOf(!startMinimized)
    /** Bumped to bring the window to the front (tray, a second launch, a shortcut). */
    var showRequests by mutableIntStateOf(0)
        private set
    /** Set before exiting when the app should start again (after a restore or an update). */
    @Volatile var restartRequested = false
    /** Asks the window code to exit (set by Main). */
    var exitApp: () -> Unit = {}

    /** Exits and starts again (e.g. to apply a restored backup). */
    fun restart() {
        restartRequested = true
        exitApp()
    }

    /** Completed once the saved wallpaper has been restored after starting. */
    val ready = CompletableDeferred<Unit>()
    var appearance by mutableStateOf(settings.string("appearance_mode", "dark"))
        private set
    var systemDark by mutableStateOf(systemUsesDarkTheme())
        private set
    /** The app's accent color as 0xRRGGBB (Settings > Appearance); null for the built-in vermilion. */
    var themeColor by mutableStateOf(wpm.ui.parseAccent(settings.string("theme_color", "")))
        private set
    /** No page animations (Settings; follows Windows' "Animation effects" until changed). */
    var reduceMotion by mutableStateOf(settings.boolean("reduce_motion", !wpm.win32.systemAnimationsEnabled()))
    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    /** Bumped whenever library contents change, so pages reload. */
    var libraryVersion by mutableIntStateOf(0)
        private set
    /** Bumped whenever the desktop wallpaper or lock screen changes. */
    var wallpaperVersion by mutableIntStateOf(0)
        private set
    var importProgress by mutableStateOf<ImportProgress?>(null)
        private set
    /** Non-null while the "add a video from the internet" dialog is open (with this link). */
    var addUrl by mutableStateOf<String?>(null)

    private val _shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    val shortcuts: SharedFlow<Shortcut> = _shortcuts

    /** Wallpaper changes run one at a time (from the UI, tray, playlists and schedule). */
    private val changeLock = Mutex()

    val darkTheme: Boolean
        get() = when (appearance) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

    init {
        // Follow the Windows theme while "system" is selected.
        scope.launch {
            while (true) {
                delay(5_000)
                systemDark = systemUsesDarkTheme()
            }
        }
        // Picture changes fold, fade, dissolve or ripple (with the real desktop layer only).
        if (displays != null) wallpapers.transition = ::playTransition
        // Shaders showing right now follow the sun (and the weather).
        scope.launch {
            while (true) {
                delay(60_000)
                // A failure (e.g. the database closing as the app exits) must not end the loop or crash the app.
                try {
                    refreshWeather()
                    withContext(Dispatchers.IO) { refreshEarth() }
                    pushWorld()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warning("Couldn't update the sky for shaders: $e")
                }
            }
        }
    }

    private val transitions = wpm.engine.PictureTransitions(chromiumDir, Monitors::all)

    /** The transition chosen in Settings ("off", "random" or one of [wpm.media.TRANSITIONS]). */
    var transitionStyle by mutableStateOf(settings.string("transition", "Fold"))
        private set

    fun updateTransitionStyle(style: String) {
        transitionStyle = style
        settings.set("transition", style)
    }

    /** Plays the chosen transition from [from] to [to]; [apply] sets the new picture. */
    private fun playTransition(from: Path, to: Path, apply: () -> Unit) {
        val style = transitionStyle.let { if (it == "random") wpm.media.TRANSITIONS.random() else it }
        // Not without the web engine (no 150 MB download for this) and not with "reduce motion".
        if (style !in wpm.media.TRANSITIONS || reduceMotion || !wpm.engine.Chromium.installed(chromiumDir)) return apply()
        val seconds = 1.4
        val largest = Monitors.all().maxOfOrNull { maxOf(it.bounds.width, it.bounds.height) } ?: 0
        transitions.play(shaders.transitionPage(style, from, to, seconds, largest), seconds, apply)
    }

    /** Sends the current sky to every shader and scene on screen. */
    fun pushWorld() {
        val script = world.script()
        val engines = webEngines()
        engines.filter { it.source?.contains("/shader-") == true }.forEach { it.runScript(script) }
        engines.filter { scenes.isScene(it.source) }.takeIf { it.isNotEmpty() }?.let { shown ->
            val sky = sceneScript()
            shown.forEach { it.runScript(sky + if (scenes.sceneOf(it.source) == wpm.media.Scenes.EARTH) texturesScript() else "") }
        }
    }

    private fun sceneScript() = world.sceneScript(settings.boolean("live_weather", true), wpm.I18n.language,
        status = tr("Getting pictures of the Earth from NASA…").takeIf { !earth.ready }) { tr(it) }

    private fun texturesScript() = earth.version().takeIf { earth.ready }
        ?.let { "window.wpm&&window.wpm.setTextures&&window.wpm.setTextures(${kotlinx.serialization.json.JsonPrimitive(it)});" } ?: ""

    private fun earthShown() = webEngines().any { scenes.sceneOf(it.source) == wpm.media.Scenes.EARTH }

    /** Gets the day's pictures of the Earth while Live Earth is on screen (once a day). */
    private fun refreshEarth() {
        if (earthShown() && earth.refresh()) pushWorld()
    }

    /** What the widgets show now (only the parts [setup] needs: the weather may be looked up). */
    fun widgetData(setup: wpm.widgets.WidgetSetup): wpm.widgets.WidgetData {
        val kinds = setup.widgets.map { it.kind }.toSet()
        if (wpm.widgets.WidgetKind.Weather in kinds && world.refreshWeather()) pushWorld()
        return wpm.widgets.WidgetData(
            weather = world.weather.takeIf { wpm.widgets.WidgetKind.Weather in kinds },
            place = (scheduler.place ?: wpm.services.guessCity())?.name,
            track = nowPlaying.track.takeIf { wpm.widgets.WidgetKind.Music in kinds },
            system = if (wpm.widgets.WidgetKind.System in kinds) usage.systemSample() else null,
            accent = shownThemeColor ?: 0xFF6B52,
        )
    }

    // -- screensaver ------------------------------------------------------------------------------

    /** What the screensaver shows while it is up. */
    @Volatile var screensaver: wpm.ui.ScreensaverShow? = null
        private set
    private var screensaverHost: wpm.ui.ScreensaverHost? = null
    private var lentLive: wpm.win32.LentWindow? = null
    /** This copy was started only for the screensaver, so it exits when the screensaver closes. */
    @Volatile var exitAfterScreensaver = false

    /**
     * Shows the screensaver until the mouse or keyboard is used: a playlist's pictures as a
     * slideshow, or the desktop wallpaper (a live one keeps playing in it).
     */
    @Synchronized
    fun startScreensaver(): String {
        if (screensaver != null) return tr("The screensaver is already showing")
        val playlist = screensaverSetup.source.removePrefix("playlist:").toLongOrNull()
        val pictures = playlist?.let { id -> db.playlistItems(id).filter { it.isImage }.mapNotNull { it.localPath }.filter { java.nio.file.Files.exists(it) } }.orEmpty()
        val liveWindow = livePlayer.window.takeIf { wallpapers.videoActive }
        val show = when {
            pictures.isNotEmpty() -> wpm.ui.ScreensaverShow(pictures, shownThemeColor ?: 0xFF6B52)
            liveWindow != null -> wpm.ui.ScreensaverShow(emptyList(), shownThemeColor ?: 0xFF6B52)
            else -> wpm.ui.ScreensaverShow(listOfNotNull(wallpapers.currentPicture()), shownThemeColor ?: 0xFF6B52)
        }
        val host = wpm.ui.ScreensaverHost(show)
        screensaverHost = host
        screensaver = show
        if (show.pictures.isEmpty() && liveWindow != null) {
            lentLive = runCatching { wpm.win32.lendWindow(liveWindow, host.primary) }.onFailure { log.warning("Screensaver: $it") }.getOrNull()
        }
        log.info("Screensaver started (${if (show.pictures.isEmpty()) "live wallpaper" else "${show.pictures.size} picture(s)"})")
        scope.launch(Dispatchers.IO) {
            // Any input ends it (after a moment, so the input that started it doesn't count).
            delay(1_000)
            val start = wpm.win32.lastInputTick()
            while (screensaver === show && wpm.win32.lastInputTick() == start) delay(150)
            stopScreensaver()
        }
        return tr("Screensaver started")
    }

    @Synchronized
    fun stopScreensaver() {
        if (screensaver == null) return
        lentLive?.let { runCatching { it.giveBack() }.onFailure { e -> log.warning("Screensaver: $e") } }
        lentLive = null
        screensaverHost?.close()
        screensaverHost = null
        screensaver = null
        log.info("Screensaver closed")
        if (exitAfterScreensaver) exitApp()
    }

    // -- now playing ------------------------------------------------------------------------------

    /** Pages that use the music, by address (a page doesn't change while it is shown). */
    private val musicPages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** A shader that uses iMusic, or a web wallpaper whose files mention livelyCurrentTrack. */
    private fun usesMusic(source: String): Boolean = musicPages.getOrPut(source) {
        runCatching {
            Regex("""/shader-(\d+)\.html""").find(source)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
                return@runCatching db.getMedia(id)?.let { "iMusic" in shaders.code(it) } ?: false
            }
            // Other shader pages (previews, transitions) never do; web pages are files in their own folder.
            if ("/shader-pages/" in source || !source.startsWith("file:")) return@runCatching false
            val page = Path.of(java.net.URI(source))
            Files.walk(page.parent, 3).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.').lowercase() in setOf("html", "htm", "js") }
                    .limit(200).anyMatch { Files.size(it) < 5_000_000 && "livelyCurrentTrack" in Files.readString(it, Charsets.ISO_8859_1) }
            }
        }.getOrDefault(false)
    }

    private val musicColorsOn: Boolean get() = settings.boolean("music_colors", false)
    private var musicStoppedAt: Long? = null

    /** Watches what is playing only while a visible wallpaper uses it or the music colors are on. */
    private fun updateMusic() {
        val wanted = musicColorsOn || ambience.enabled || widgets.setup.shows(wpm.widgets.WidgetKind.Music) || webEngines().any { it.active && !it.paused && it.source?.let(::usesMusic) == true }
        nowPlaying.setWanted(wanted)
        if (musicColor == null) return
        // Colors go back 10 s after the music stops, so a short pause doesn't flash the desktop.
        val now = System.currentTimeMillis()
        if (musicColorsOn) {
            if (nowPlaying.track?.let { it.playing && it.color != null } == true) {
                musicStoppedAt = null
                return
            }
            if (now - (musicStoppedAt ?: now.also { musicStoppedAt = it }) < 10_000) return
        }
        musicColor = null
        musicStoppedAt = null
        updateAccent(force = true)
    }

    /** Hands the track to the pages on screen and, with music colors on, colors the app and Windows. */
    private fun pushTrack() {
        val script = nowPlaying.script()
        webEngines().filter { it.active }.forEach { it.runScript(script) }
        val color = nowPlaying.track?.takeIf { it.playing }?.color ?: return
        if (!musicColorsOn || color == musicColor) return
        musicColor = color
        musicStoppedAt = null
        if (accent.enabled) scope.launch(Dispatchers.IO) {
            runCatching { accent.applyColor(color) }.onFailure { log.warning("Couldn't set the accent color: $it") }
        }
    }

    fun setMusicColors(on: Boolean) {
        settings.set("music_colors", on)
        scope.launch(Dispatchers.IO) { runCatching { updateMusic(); pushTrack() }.onFailure { log.warning("Now playing: $it") } }
    }

    /** Tells the ambient sound about the weather, the music and whether anyone is listening. */
    private fun updateAmbience() {
        if (!ambience.enabled) return ambience.update(wpm.services.AmbientState())
        val weatherOn = settings.boolean("live_weather", true)
        if (weatherOn && ambience.choice == wpm.services.Ambience.WEATHER && world.refreshWeather()) pushWorld()
        val quiet = screensaver != null || runCatching {
            systemProbe.sessionLocked() || systemProbe.screenOff() ||
                settings.boolean("pause_on_fullscreen", true) && systemProbe.fullscreenDisplay() != null
        }.getOrDefault(false)
        ambience.update(wpm.services.AmbientState(
            weather = world.weather.takeIf { weatherOn }, sunElevation = world.sun()?.elevation,
            musicPlaying = nowPlaying.track?.playing == true, quiet = quiet,
        ))
    }

    /** Whether a shader or scene uses the weather outside (and the weather may be looked up). */
    private fun usesWeather(item: MediaItem): Boolean = settings.boolean("live_weather", true) &&
        (item.isShader && shaders.code(item).let { "iWeather" in it || "iClouds" in it } || item.isWeb && scenes.isScene(WebWallpapers.pageUrl(item)))

    /** Looks the weather up (every 15 minutes at most) while a wallpaper on screen uses it. */
    private suspend fun refreshWeather() {
        val showing = webEngines().mapNotNull { engine ->
            Regex("""/shader-(\d+)\.html""").find(engine.source ?: "")?.groupValues?.get(1)?.toLongOrNull()
        }.mapNotNull { db.getMedia(it) }
        val sceneShown = settings.boolean("live_weather", true) && webEngines().any { scenes.isScene(it.source) }
        if (!sceneShown && showing.none(::usesWeather)) return
        if (withContext(Dispatchers.IO) { world.refreshWeather() }) pushWorld()
    }

    fun showWindow() {
        windowVisible = true
        showRequests++
    }

    fun updateReduceMotion(on: Boolean) {
        reduceMotion = on
        settings.set("reduce_motion", on)
    }

    /** The album art's color while music plays with "colors follow the music" on; it tints the app. */
    var musicColor by mutableStateOf<Int?>(null)
        private set
    /** The accent the app shows right now: the music's, or the one chosen in Settings. */
    val shownThemeColor: Int? get() = musicColor ?: themeColor

    fun updateThemeColor(rgb: Int?) {
        themeColor = rgb
        settings.set("theme_color", wpm.ui.formatAccent(rgb))
    }

    fun updateAppearance(mode: String) {
        appearance = mode
        settings.set("appearance_mode", mode)
    }

    fun shortcut(shortcut: Shortcut) = _shortcuts.tryEmit(shortcut)

    /** Brief confirmation at the bottom of the window. Skipped while the window is hidden. */
    fun toast(text: String, kind: ToastKind = ToastKind.Info) {
        if (windowVisible) toast = ToastMessage(text, kind)
    }

    fun dismissToast(message: ToastMessage) {
        if (toast?.id == message.id) toast = null
    }

    fun libraryChanged() {
        libraryVersion++
    }

    fun wallpaperChanged() {
        wallpaperVersion++
    }

    /**
     * Runs a blocking task off the UI thread. Errors become an error toast;
     * [success] (if given) is shown when it worked.
     */
    fun runTask(success: String? = null, failure: String = "Something went wrong", block: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                if (success != null) toast(success, ToastKind.Success)
            } catch (e: Exception) {
                log.warning("$failure: $e")
                toast("$failure: ${e.message ?: e.javaClass.simpleName}", ToastKind.Error)
            }
        }
    }

    /** Applies a wallpaper change exclusively, then refreshes the pages that show it. */
    suspend fun <T> changeWallpaper(block: () -> T): T = changeLock.withLock {
        withContext(Dispatchers.IO) { block() }.also {
            wallpaperChanged()
            updateAccent()
        }
    }

    private var accentFor: Long? = null

    /** With "accent color from the wallpaper" on, follows the desktop wallpaper (pictures, or a live wallpaper's thumbnail). */
    fun updateAccent(force: Boolean = false) {
        if (!accent.enabled) {
            accentFor = null
            return
        }
        val item = wallpapers.currentItem() ?: return
        if (!force && item.id == accentFor) return
        accentFor = item.id
        scope.launch(Dispatchers.IO) {
            runCatching {
                val file = if (item.isImage) item.localPath else item.thumbnail?.let { paths.thumbDir.resolve(it) }
                file?.let { wpm.media.Imaging.load(it) }?.use { accent.applyFrom(it) }
            }.onFailure { log.warning("Couldn't set the accent color: $it") }
        }
    }

    /** Picking a wallpaper by hand stops the running playlist. */
    fun setDesktop(item: MediaItem) = runTask(tr("Desktop wallpaper set"), tr("Couldn't set the wallpaper")) {
        changeWallpaper {
            playlists.stop()
            wallpapers.set(item)
        }
        refreshWebThumbnail(item)
    }

    /** Gives one display its own wallpaper. */
    fun setOnDisplay(display: wpm.win32.Monitor, item: MediaItem) = runTask(tr("{0} updated", display.label), tr("Couldn't set the wallpaper")) {
        changeWallpaper { wallpapers.setOnDisplay(display, item) }
    }

    fun nextWallpaper() = runCommand(AppCommand.Next)

    fun randomWallpaper() = runCommand(AppCommand.Random)

    /** Runs a command from the UI, tray or a shortcut; the result is shown as a toast. */
    fun runCommand(command: AppCommand) {
        scope.launch {
            try {
                val message = perform(command)
                if (command != AppCommand.Show && command != AppCommand.Screensaver) toast(message, ToastKind.Success)
            } catch (e: Exception) {
                log.info("$command: ${e.message}")
                toast(e.message ?: e.javaClass.simpleName, if (e is IllegalStateException) ToastKind.Info else ToastKind.Error)
            }
        }
    }

    /** A command line handed over by a second launch (blocks until done). */
    fun handleCommandLine(args: List<String>): CommandReply {
        val parsed = parseCommandLine(args)
        if (parsed.error != null) return CommandReply(false, parsed.error)
        val command = parsed.command ?: AppCommand.Show
        return runBlocking {
            ready.await()
            try {
                CommandReply(true, perform(command))
            } catch (e: Exception) {
                CommandReply(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Does what a command asks and describes the result. Throws IllegalStateException when there
     * is nothing to do (e.g. no live wallpaper to pause) and other exceptions when it failed.
     */
    suspend fun perform(command: AppCommand): String = when (command) {
        AppCommand.Show -> {
            showWindow()
            tr("Window opened")
        }
        AppCommand.Next -> {
            val item = changeWallpaper { if (playlists.state != null) playlists.advance() else wallpapers.nextFromLibrary() }
            tr("Showing {0}", item?.filename ?: throw IllegalStateException(tr("Your library is empty")))
        }
        AppCommand.Random -> {
            val item = changeWallpaper { wallpapers.randomFromLibrary() } ?: throw IllegalStateException(tr("Add a few wallpapers first"))
            tr("Showing {0}", item.filename)
        }
        AppCommand.TogglePause -> liveControl { if (wallpapers.videoPaused) resumeLive() else pauseLive() }
        AppCommand.Pause -> liveControl { pauseLive() }
        AppCommand.Resume -> liveControl { resumeLive() }
        AppCommand.Stop -> liveControl {
            wallpapers.stopVideo()
            tr("Live wallpaper stopped")
        }
        AppCommand.StopPlaylist -> {
            if (playlists.state == null) throw IllegalStateException(tr("No playlist is running"))
            playlists.stop()
            wallpaperChanged()
            tr("Playlist stopped")
        }
        is AppCommand.Set -> {
            val item = withContext(Dispatchers.IO) { resolveTarget(command.target) }
            changeWallpaper {
                playlists.stop()
                wallpapers.set(item)
            }
            refreshWebThumbnail(item)
            tr("Showing {0}", item.filename)
        }
        AppCommand.Screensaver -> withContext(Dispatchers.IO) { startScreensaver() }
        AppCommand.ScreensaverStatus -> if (screensaver != null) tr("The screensaver is showing") else throw IllegalStateException(tr("The screensaver has closed"))
        AppCommand.Update -> {
            val version = withContext(Dispatchers.IO) { updates.checkIfDue(force = true) }
                ?: throw IllegalStateException(tr("You have the latest version"))
            if (!wpm.services.Updater.canInstall) throw IllegalStateException(tr("Version {0} is available. Download it from {1}", version, wpm.services.RELEASES_PAGE))
            val release = updates.availableRelease() ?: throw IllegalStateException(tr("Version {0} has no installer", version))
            val installer = withContext(Dispatchers.IO) { wpm.services.Updater.download(release, paths.cacheDir.resolve("updates")) }
            wpm.services.Updater.launch(installer)
            scope.launch { delay(500); javax.swing.SwingUtilities.invokeLater { exitApp() } }
            tr("Installing version {0}; the app restarts in a moment", version)
        }
        is AppCommand.Playlist -> {
            val playlist = db.listPlaylists().firstOrNull { it.name.equals(command.name.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(tr("There is no playlist called “{0}”", command.name))
            val item = changeWallpaper { playlists.start(playlist.id) }
            tr("Playing “{0}”: {1}", playlist.name, item.filename)
        }
    }

    private fun pauseLive(): String {
        wallpapers.pauseVideo()
        return tr("Live wallpaper paused")
    }

    private fun resumeLive(): String {
        wallpapers.resumeVideo()
        return tr("Live wallpaper playing")
    }

    private suspend fun liveControl(block: () -> String): String {
        if (!wallpapers.videoActive) throw IllegalStateException(tr("No live wallpaper is playing"))
        return changeWallpaper(block)
    }

    /**
     * The library item a command line means: a file or folder on this PC (added to the library
     * first if it isn't in it yet), or the name of a library item.
     */
    internal fun resolveTarget(target: String): MediaItem {
        val text = target.trim().trim('"')
        val path = runCatching { Path.of(text) }.getOrNull()?.takeIf { it.isAbsolute && it.exists() }?.normalize()
        if (path != null) {
            db.mediaByPath(path.toString())?.let { return it }
            val id: Long? = when {
                path.extension.lowercase() in SHADER_EXTENSIONS -> shaders.import(path)
                WebWallpapers.looksLikeWebWallpaper(path) ->
                    web.import(path) ?: db.mediaByPath((if (path.isDirectory()) path else path.parent).toString())?.id
                mediaTypeFor(path.toString()) != null -> {
                    val result = library.importFiles(listOf(path))
                    result.failed.firstOrNull()?.let { throw IllegalArgumentException(tr("Couldn't add {0}: {1}", path.fileName, it.second)) }
                    result.added.firstOrNull()
                }
                path.isDirectory() -> throw IllegalArgumentException(tr("Choose a picture, video, web wallpaper or shader, not a folder of files"))
                else -> throw IllegalArgumentException(tr("{0} isn't a supported wallpaper file", path.fileName))
            }
            libraryChanged()
            return id?.let(db::getMedia) ?: throw IllegalStateException(tr("Couldn't add {0} to the library", path.fileName))
        }
        val items = db.listMedia()
        fun String.base() = substringBeforeLast('.')
        items.firstOrNull { it.filename.equals(text, ignoreCase = true) }?.let { return it }
        items.filter { it.filename.base().equals(text.base(), ignoreCase = true) }.singleOrNull()?.let { return it }
        val matches = items.filter { it.filename.contains(text, ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(tr("Nothing called “{0}” in your library, and no such file", text))
            else -> throw IllegalArgumentException(
                "“$text” matches ${matches.size} wallpapers: " + matches.take(3).joinToString { it.filename } + if (matches.size > 3) ", …" else "",
            )
        }
    }

    // -- global keyboard shortcuts ------------------------------------------------------

    private val shortcutSerializer = MapSerializer(String.serializer(), String.serializer())
    private var hotkeys: GlobalHotkeys? = null
    /** Shortcuts Windows refused (action id -> why), for the Settings page. */
    var shortcutProblems by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    val shortcutsEnabled: Boolean get() = settings.boolean("shortcuts_enabled", false)

    /** The keys for each action ("" = none). */
    fun shortcutKeys(): Map<String, String> {
        val saved = settings.obj("shortcuts", shortcutSerializer) ?: emptyMap()
        return SHORTCUT_ACTIONS.associate { it.id to (saved[it.id] ?: it.defaultKeys) }
    }

    fun updateShortcuts(enabled: Boolean, keys: Map<String, String>) {
        settings.set("shortcuts_enabled", enabled)
        settings.set("shortcuts", shortcutSerializer, keys)
        applyShortcuts()
    }

    /** Registers the saved shortcuts with Windows (or removes them when switched off). Blocks briefly. */
    @Synchronized
    fun applyShortcuts() {
        val bindings = if (shortcutsEnabled) shortcutKeys().mapNotNull { (id, keys) -> Hotkey.parse(keys)?.let { id to it } }.toMap() else emptyMap()
        if (bindings.isEmpty() && hotkeys == null) {
            shortcutProblems = emptyMap()
            return
        }
        val engine = hotkeys ?: GlobalHotkeys { id -> SHORTCUT_ACTIONS.firstOrNull { it.id == id }?.let { runCommand(it.command) } }
            .also { hotkeys = it }
        shortcutProblems = runCatching { engine.register(bindings) }.getOrElse { mapOf("" to "Couldn't register shortcuts: ${it.message}") }
    }

    /** Downloads an online wallpaper into Pictures\Wallpapers and adds it to the library (once). */
    suspend fun downloadOnline(wallpaper: wpm.media.OnlineWallpaper): MediaItem = withContext(Dispatchers.IO) {
        db.listMedia().firstOrNull { it.sourceUrl == wallpaper.pageUrl }?.takeIf { it.available() }?.let { return@withContext it }
        val file = wpm.media.downloadWallpaper(online, wallpaper, picturesFolder())
        val item = db.mediaByPath(file.toString()) ?: run {
            val result = library.importFiles(listOf(file))
            result.failed.firstOrNull()?.let { throw IllegalStateException(tr("Couldn't add it: {0}", it.second)) }
            db.getMedia(result.added.single())!!
        }
        runCatching { db.execute("UPDATE media SET source_url = ? WHERE id = ?", wallpaper.pageUrl, item.id) }
        libraryChanged()
        db.getMedia(item.id)!!
    }

    /** Imports web wallpapers (folders with a page, .html or .zip files). */
    fun importWeb(paths: List<Path>) = runTask(failure = tr("Couldn't add the web wallpaper")) {
        var added = 0
        paths.forEach { if (web.import(it) != null) added++ }
        libraryChanged()
        toast(if (added > 0) trCount(added, "Added {0} web wallpaper", "Added {0} web wallpapers") else tr("Already in your library"), ToastKind.Success)
    }

    /**
     * After a web wallpaper has been showing for a few seconds, replaces its generated title-card
     * thumbnail with a snapshot of the real page.
     */
    private fun refreshWebThumbnail(item: MediaItem) {
        if (!item.isLive || item.isVideo || item.thumbnail?.startsWith(WebWallpapers.CARD_PREFIX) == false) return
        scope.launch(Dispatchers.IO) {
            delay(6_000)
            if (wallpapers.state.mediaId != item.id || wallpapers.videoPaused) return@launch
            val engine = (displays?.primaryPlayer() as? LiveRouter)?.web as? WebLivePlayer ?: return@launch
            val shot = engine.snapshot() ?: return@launch
            runCatching {
                val name = web.saveSnapshot(shot, item.id)
                db.setThumbnail(item.id, name)
                item.thumbnail?.let { old -> java.nio.file.Files.deleteIfExists(paths.thumbDir.resolve(old)) }
                libraryChanged()
            }.onFailure { log.info("No snapshot for ${item.filename}: $it") }
        }
    }

    /** Imports files and folders (folders recursively), showing progress. Web wallpapers are recognised and added as such. */
    fun import(paths: List<Path>) {
        val (shaderFiles, rest) = paths.partition { it.toString().substringAfterLast('.').lowercase() in SHADER_EXTENSIONS }
        if (shaderFiles.isNotEmpty()) runTask(tr("Shader added"), tr("Couldn't add the shader")) {
            shaderFiles.forEach { shaders.import(it) }
            libraryChanged()
        }
        val (webItems, media) = rest.partition(WebWallpapers::looksLikeWebWallpaper)
        if (webItems.isNotEmpty()) importWeb(webItems)
        if (media.isEmpty()) return
        importMedia(media)
    }

    private fun importMedia(paths: List<Path>) {
        if (importProgress != null) {
            toast(tr("An import is already running"))
            return
        }
        importProgress = ImportProgress(0, 0, "Looking for files…")
        scope.launch(Dispatchers.IO) {
            try {
                val files = paths.flatMap { if (it.isDirectory()) findMediaFiles(it) else listOf(it) }
                    .filter { mediaTypeFor(it.toString()) != null }
                if (files.isEmpty()) {
                    toast(tr("No supported images or videos found"))
                    return@launch
                }
                val result = library.importFiles(files) { i, total, path -> importProgress = ImportProgress(i, total, path.fileName.toString()) }
                libraryChanged()
                val parts = buildList {
                    add("${result.added.size} added")
                    if (result.skipped > 0) add("${result.skipped} already in library")
                    if (result.failed.isNotEmpty()) add("${result.failed.size} failed (${result.failed.first().second})")
                }
                toast(parts.joinToString(", "), if (result.failed.isEmpty()) ToastKind.Success else ToastKind.Error)
            } catch (e: Exception) {
                log.warning("Import failed: $e")
                toast(tr("Import failed: {0}", e.message), ToastKind.Error)
            } finally {
                importProgress = null
            }
        }
    }

    /** What the live engines play for an item: videos through mpv, pages and shaders through Chromium. */
    private fun liveSourceFor(item: MediaItem, style: String): LiveSource = when {
        item.isVideo -> LiveSource(LiveKind.Video, item.source, fill = style == "fill", muted = wallpapers.muted)
        else -> LiveSource(
            LiveKind.Web, if (item.isShader) shaders.pageFor(item, settings.string("shader_fps", "60").toIntOrNull() ?: 60) else WebWallpapers.pageUrl(item),
            muted = settings.boolean("web_muted", true),
            interactive = item.interactive && settings.boolean("web_interaction", true),
            audio = item.audio && settings.boolean("audio_reactive", true),
            onLoadScript = listOfNotNull(
                settings.string("web_fps", "full").toIntOrNull()?.takeIf { !item.isShader }?.let(WebWallpapers::frameRateLimit),
                propertyScript(item),
                world.script().takeIf { item.isShader },
                sceneScript().takeIf { !item.isShader && scenes.isScene(WebWallpapers.pageUrl(item)) },
                texturesScript().takeIf { !item.isShader && scenes.sceneOf(WebWallpapers.pageUrl(item)) == wpm.media.Scenes.EARTH },
                nowPlaying.script().takeIf { nowPlaying.running },
            ).joinToString("").ifEmpty { null },
        ).also {
            // A weather wallpaper gets the weather as soon as it is known.
            if (usesWeather(item)) scope.launch(Dispatchers.IO) { if (world.refreshWeather()) pushWorld() }
            if (!item.isShader && scenes.sceneOf(WebWallpapers.pageUrl(item)) == wpm.media.Scenes.EARTH) scope.launch(Dispatchers.IO) {
                delay(3_000) // until the page is on screen
                refreshEarth()
            }
        }
    }

    // -- wallpaper settings (LivelyProperties.json, shader sliders) ------------------------------

    /** The settings a web wallpaper or shader offers. */
    fun propertiesOf(item: MediaItem): List<WallpaperProperty> =
        if (item.isShader) shaders.properties(item) else properties.read(item)

    /** Sends all of an item's setting values to its page once it has loaded. */
    private fun propertyScript(item: MediaItem): String? {
        val list = propertiesOf(item).takeIf { it.isNotEmpty() } ?: return null
        return WallpaperProperties.loadScript(list, properties.values(item.id, list))
    }

    /** Runs a script in every web engine showing [item] right now. */
    private fun sendToPage(item: MediaItem, script: String) {
        if (script.isEmpty()) return
        val page = if (item.isShader) null else WebWallpapers.pageUrl(item)
        webEngines().filter { engine ->
            val shown = engine.source ?: return@filter false
            if (item.isShader) shown.contains("shader-${item.id}.html") else shown == page
        }.forEach { it.runScript(script) }
    }

    /**
     * Changes one setting; a showing wallpaper gets the new value straight away (buttons are only
     * sent). A shader's picture needs the page to be written again: then the wallpaper restarts.
     */
    suspend fun setProperty(item: MediaItem, property: WallpaperProperty, value: JsonPrimitive) {
        val normalized = if (property.type == wpm.media.PropertyType.BUTTON) JsonPrimitive(true) else property.normalize(value)
        if (property.type != wpm.media.PropertyType.BUTTON) properties.set(item.id, property.name, normalized)
        if (property.type == wpm.media.PropertyType.IMAGE) restartIfShowing(item)
        else sendToPage(item, WallpaperProperties.script(listOf(property.name to WallpaperProperties.pageValue(property, normalized)), property.engine))
    }

    /** Imports a WinDynamicDesktop theme (.ddw) as a playlist that follows the sun. Returns its id. */
    suspend fun importDynamicTheme(file: Path): Long {
        val id = withContext(Dispatchers.IO) { wpm.services.DynamicThemes.import(file, paths.dataDir.resolve("dynamic"), db, library) }
        libraryChanged()
        return id
    }

    /**
     * Turns a picture into a live wallpaper with one of the [wpm.media.PICTURE_EFFECTS]
     * (a shader that shows it), adds that to the library and shows it.
     */
    suspend fun makeAlive(picture: MediaItem, effect: String): MediaItem {
        val path = picture.localPath ?: throw IllegalArgumentException(tr("Only pictures on this PC can be used"))
        val sample = shaders.effects().firstOrNull { it.name == effect } ?: throw IllegalArgumentException(tr("Unknown effect: {0}", effect))
        if (wpm.media.needsDepth(sample.code)) withContext(Dispatchers.IO) {
            if (!depthMaps.installed) {
                toast(tr("Downloading the 3D model once ({0} MB)…", wpm.media.DEPTH_MODEL_MB))
                depthMaps.install()
            }
            if (depthMaps.cached(path) == null) {
                toast(tr("Finding what is near and far…"))
                depthMaps.of(path)
            }
        }
        val item = withContext(Dispatchers.IO) {
            val title = "${picture.filename.substringBeforeLast('.')} · $effect"
            val id = shaders.create(title, sample.code, interactive = effect == "Parallax" || wpm.media.needsDepth(sample.code), audio = "iChannel0" in sample.code, preview = path)
            properties.set(id, "photo", JsonPrimitive(path.toString()))
            db.getMedia(id)!!
        }
        libraryChanged()
        changeWallpaper {
            playlists.stop()
            wallpapers.set(item)
        }
        return item
    }

    /** Back to the values the wallpaper comes with. */
    suspend fun resetProperties(item: MediaItem) {
        val hadPicture = propertiesOf(item).any { it.type == wpm.media.PropertyType.IMAGE && properties.saved(item.id).containsKey(it.name) }
        properties.reset(item.id)
        if (hadPicture) restartIfShowing(item) else propertyScript(item)?.let { sendToPage(item, it) }
    }

    /** Plays [item] again wherever it is showing (after its page changed). */
    suspend fun restartIfShowing(item: MediaItem) {
        if (wallpapers.state.mediaId == item.id && wallpapers.videoActive) changeWallpaper { wallpapers.restartLive() }
        wallpapers.displayChoices().filterValues { it.mediaId == item.id }.keys.forEach { id ->
            Monitors.all().firstOrNull { it.id == id }?.let { display -> changeWallpaper { wallpapers.setOnDisplay(display, item) } }
        }
    }

    /** Listens to system audio only while a visible wallpaper wants it. */
    private fun updateAudio() {
        val wanted = webEngines().any { it.active && it.wantsAudio && !it.paused }
        if (wanted && !audio.active) audio.start() else if (!wanted && audio.active) audio.stop()
    }

    /** Starts the background jobs (restore, watchdog, auto-pause, audio). */
    fun startAutomation() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1_000)
                runCatching { updateAudio() }
                runCatching { updateMusic() }.onFailure { log.warning("Now playing: $it") }
                runCatching { updateAmbience() }.onFailure { log.warning("Ambient sound: $it") }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { widgets.update() }.onFailure { log.warning("Widgets: $it") }
                delay(1_000)
            }
        }
        Automation(
            scope, wallpapers, playlists, scheduler, updates, settings, systemProbe,
            exclusive = { block -> changeWallpaper(block) },
            notify = { notify(it) },
            liveDisplays = { displays?.players()?.filterValues { it.active }?.keys ?: emptySet() },
            onStarted = { ready.complete(Unit) },
            lockSlideshow = lockSlideshow,
            screensaverShowing = { screensaver != null },
        ).start()
        watched.start()
        scope.launch(Dispatchers.IO) { screensaverSetup.refresh() }
        scope.launch(Dispatchers.IO) { if (scenes.install().isNotEmpty()) libraryChanged() }
        // The ambient sound fades in small steps.
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(100)
                ambience.step(0.1)
            }
        }
        // The space station moves 7.7 km a second: Live Earth gets its position every 10 seconds.
        scope.launch(Dispatchers.IO) {
            while (true) {
                val shown = webEngines().filter { scenes.sceneOf(it.source) == wpm.media.Scenes.EARTH && !it.paused }
                if (shown.isNotEmpty()) runCatching { wpm.services.IssTracker.script(iss.update()) }
                    .onSuccess { script -> shown.forEach { it.runScript(script) } }
                    .onFailure { log.info("No space station position: $it") }
                delay(10_000)
            }
        }
        scope.launch(Dispatchers.IO) { runCatching { applyShortcuts() }.onFailure { log.warning("Shortcuts: $it") } }
    }

    fun videoAction(success: String? = null, block: () -> Unit) =
        runTask(success, "Couldn't control the video") { changeWallpaper(block) }

    /** The app is exiting: stop the live wallpaper (it comes back on next start). */
    fun shutdown() {
        scope.cancel()
        hotkeys?.close()
        watched.close()
        audio.stop()
        runCatching { nowPlaying.setWanted(false) }
        runCatching { widgets.close() }
        runCatching { ambience.close() }
        runCatching { wallpapers.shutdown() }.onFailure { log.warning("Shutdown: $it") }
        wpm.engine.Chromium.shutdown()
    }

    fun close() {
        scope.cancel()
    }
}

/** Downloaded pictures go to Pictures\Wallpapers. */
private fun picturesFolder(): Path {
    val pictures = runCatching {
        com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(com.sun.jna.platform.win32.KnownFolders.FOLDERID_Pictures)
    }.getOrNull() ?: Path.of(System.getProperty("user.home"), "Pictures").toString()
    return Path.of(pictures, "Wallpapers")
}

/** Offline copies of online videos go where people look for videos: Videos\Wallpapers. */
private fun downloadFolder(): Path {
    val videos = runCatching {
        com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(com.sun.jna.platform.win32.KnownFolders.FOLDERID_Videos)
    }.getOrNull() ?: Path.of(System.getProperty("user.home"), "Videos").toString()
    return Path.of(videos, "Wallpapers")
}

private fun systemUsesDarkTheme(): Boolean = try {
    Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    ) == 0
} catch (e: Exception) {
    true
}
