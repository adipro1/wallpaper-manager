package wpm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
import wpm.win32.DesktopWallpaperApi
import wpm.win32.WindowsDesktop
import java.awt.Window
import java.nio.file.Path
import kotlin.io.path.isDirectory

enum class Page(val label: String) {
    Home("Home"),
    Desktop("Desktop"),
    LockScreen("Lock Screen"),
    Library("Library"),
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
    val shaders = Shaders(db, web, paths.dataDir.resolve("shaders"), paths.cacheDir.resolve("shader-pages")).also { it.cleanPreviews() }
    val lockScreen = LockScreenService(db, paths, lockScreenApi, videoReader)
    val ytDlp = YtDlp(NativeBin.dir.resolve("yt-dlp.exe"))
    val urls = UrlImporter(db, library, ytDlp, downloadFolder())
    val playlists = PlaylistPlayer(db, wallpapers)
    val scheduler = Scheduler(db, wallpapers, playlists)
    val updates = UpdateChecker(db)
    /** Shows a tray notification (set by the window code). */
    var notify: (String) -> Unit = {}
    /** False when libmpv is missing: videos can't be imported or played. */
    val videoSupport: Boolean = videoReader != null
    val thumbs = ThumbnailCache(library)

    /** The main window, for file dialogs. */
    var window: Window? = null

    var page by mutableStateOf(Page.Home)
    var windowVisible by mutableStateOf(!startMinimized)
    var appearance by mutableStateOf(settings.string("appearance_mode", "dark"))
        private set
    var systemDark by mutableStateOf(systemUsesDarkTheme())
        private set
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
        withContext(Dispatchers.IO) { block() }.also { wallpaperChanged() }
    }

    /** Picking a wallpaper by hand stops the running playlist. */
    fun setDesktop(item: MediaItem) = runTask("Desktop wallpaper set", "Couldn't set the wallpaper") {
        changeWallpaper {
            playlists.stop()
            wallpapers.set(item)
        }
        refreshWebThumbnail(item)
    }

    /** Gives one display its own wallpaper. */
    fun setOnDisplay(display: wpm.win32.Monitor, item: MediaItem) = runTask("${display.label} updated", "Couldn't set the wallpaper") {
        changeWallpaper { wallpapers.setOnDisplay(display, item) }
    }

    fun nextWallpaper() = runTask(failure = "Couldn't change the wallpaper") {
        val item = changeWallpaper { wallpapers.nextFromLibrary() }
        toast(item?.let { "Showing ${it.filename}" } ?: "Your library is empty", if (item != null) ToastKind.Success else ToastKind.Info)
    }

    fun randomWallpaper() = runTask(failure = "Couldn't change the wallpaper") {
        val item = changeWallpaper { wallpapers.randomFromLibrary() }
        toast(item?.let { "Showing ${it.filename}" } ?: "Add a few wallpapers first", if (item != null) ToastKind.Success else ToastKind.Info)
    }

    /** Imports web wallpapers (folders with a page, .html or .zip files). */
    fun importWeb(paths: List<Path>) = runTask(failure = "Couldn't add the web wallpaper") {
        var added = 0
        paths.forEach { if (web.import(it) != null) added++ }
        libraryChanged()
        toast(if (added > 0) "Added $added web wallpaper${if (added == 1) "" else "s"}" else "Already in your library", ToastKind.Success)
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
        if (shaderFiles.isNotEmpty()) runTask("Shader added", "Couldn't add the shader") {
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
            toast("An import is already running")
            return
        }
        importProgress = ImportProgress(0, 0, "Looking for files…")
        scope.launch(Dispatchers.IO) {
            try {
                val files = paths.flatMap { if (it.isDirectory()) findMediaFiles(it) else listOf(it) }
                    .filter { mediaTypeFor(it.toString()) != null }
                if (files.isEmpty()) {
                    toast("No supported images or videos found")
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
                toast("Import failed: ${e.message}", ToastKind.Error)
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
        )
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
            }
        }
        Automation(
            scope, wallpapers, playlists, scheduler, updates, settings, systemProbe,
            exclusive = { block -> changeWallpaper(block) },
            notify = { notify(it) },
            liveDisplays = { displays?.players()?.filterValues { it.active }?.keys ?: emptySet() },
        ).start()
    }

    fun videoAction(success: String? = null, block: () -> Unit) =
        runTask(success, "Couldn't control the video") { changeWallpaper(block) }

    /** The app is exiting: stop the live wallpaper (it comes back on next start). */
    fun shutdown() {
        scope.cancel()
        audio.stop()
        runCatching { wallpapers.shutdown() }.onFailure { log.warning("Shutdown: $it") }
        wpm.engine.Chromium.shutdown()
    }

    fun close() {
        scope.cancel()
    }
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
