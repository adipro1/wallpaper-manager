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
    val properties = WallpaperProperties(db)
    val missing = wpm.media.MissingFiles(db)
    val shaders = Shaders(db, web, paths.dataDir.resolve("shaders"), paths.cacheDir.resolve("shader-pages"), properties).also { it.cleanPreviews() }
    val lockScreen = LockScreenService(db, paths, lockScreenApi, videoReader)
    val ytDlp = YtDlp(NativeBin.dir.resolve("yt-dlp.exe"))
    val urls = UrlImporter(db, library, ytDlp, downloadFolder())
    val playlists = PlaylistPlayer(db, wallpapers)
    val lockSlideshow = wpm.services.LockScreenSlideshow(db, lockScreen)
    val accent = wpm.services.AccentFromWallpaper(settings)
    /** Watched folders; new and deleted files show up in the library by themselves. */
    val watched = wpm.services.WatchedFolders(db, library) { change ->
        libraryChanged()
        val parts = buildList {
            if (change.added > 0) add("${change.added} new")
            if (change.removed > 0) add("${change.removed} removed")
            if (change.moved > 0) add("${change.moved} moved")
        }
        toast("Watched folders: ${parts.joinToString(", ")}")
    }
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
    }

    fun showWindow() {
        windowVisible = true
        showRequests++
    }

    fun updateReduceMotion(on: Boolean) {
        reduceMotion = on
        settings.set("reduce_motion", on)
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

    fun nextWallpaper() = runCommand(AppCommand.Next)

    fun randomWallpaper() = runCommand(AppCommand.Random)

    /** Runs a command from the UI, tray or a shortcut; the result is shown as a toast. */
    fun runCommand(command: AppCommand) {
        scope.launch {
            try {
                val message = perform(command)
                if (command != AppCommand.Show) toast(message, ToastKind.Success)
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
            "Window opened"
        }
        AppCommand.Next -> {
            val item = changeWallpaper { if (playlists.state != null) playlists.advance() else wallpapers.nextFromLibrary() }
            "Showing ${item?.filename ?: throw IllegalStateException("Your library is empty")}"
        }
        AppCommand.Random -> {
            val item = changeWallpaper { wallpapers.randomFromLibrary() } ?: throw IllegalStateException("Add a few wallpapers first")
            "Showing ${item.filename}"
        }
        AppCommand.TogglePause -> liveControl { if (wallpapers.videoPaused) resumeLive() else pauseLive() }
        AppCommand.Pause -> liveControl { pauseLive() }
        AppCommand.Resume -> liveControl { resumeLive() }
        AppCommand.Stop -> liveControl {
            wallpapers.stopVideo()
            "Live wallpaper stopped"
        }
        AppCommand.StopPlaylist -> {
            if (playlists.state == null) throw IllegalStateException("No playlist is running")
            playlists.stop()
            wallpaperChanged()
            "Playlist stopped"
        }
        is AppCommand.Set -> {
            val item = withContext(Dispatchers.IO) { resolveTarget(command.target) }
            changeWallpaper {
                playlists.stop()
                wallpapers.set(item)
            }
            refreshWebThumbnail(item)
            "Showing ${item.filename}"
        }
        is AppCommand.Playlist -> {
            val playlist = db.listPlaylists().firstOrNull { it.name.equals(command.name.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("There is no playlist called “${command.name}”")
            val item = changeWallpaper { playlists.start(playlist.id) }
            "Playing “${playlist.name}”: ${item.filename}"
        }
    }

    private fun pauseLive(): String {
        wallpapers.pauseVideo()
        return "Live wallpaper paused"
    }

    private fun resumeLive(): String {
        wallpapers.resumeVideo()
        return "Live wallpaper playing"
    }

    private suspend fun liveControl(block: () -> String): String {
        if (!wallpapers.videoActive) throw IllegalStateException("No live wallpaper is playing")
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
                    result.failed.firstOrNull()?.let { throw IllegalArgumentException("Couldn't add ${path.fileName}: ${it.second}") }
                    result.added.firstOrNull()
                }
                path.isDirectory() -> throw IllegalArgumentException("Choose a picture, video, web wallpaper or shader, not a folder of files")
                else -> throw IllegalArgumentException("${path.fileName} isn't a supported wallpaper file")
            }
            libraryChanged()
            return id?.let(db::getMedia) ?: throw IllegalStateException("Couldn't add ${path.fileName} to the library")
        }
        val items = db.listMedia()
        fun String.base() = substringBeforeLast('.')
        items.firstOrNull { it.filename.equals(text, ignoreCase = true) }?.let { return it }
        items.filter { it.filename.base().equals(text.base(), ignoreCase = true) }.singleOrNull()?.let { return it }
        val matches = items.filter { it.filename.contains(text, ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException("Nothing called “$text” in your library, and no such file")
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
            result.failed.firstOrNull()?.let { throw IllegalStateException("Couldn't add it: ${it.second}") }
            db.getMedia(result.added.single())!!
        }
        runCatching { db.execute("UPDATE media SET source_url = ? WHERE id = ?", wallpaper.pageUrl, item.id) }
        libraryChanged()
        db.getMedia(item.id)!!
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
            onLoadScript = propertyScript(item),
        )
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

    /**
     * Turns a picture into a live wallpaper with one of the [wpm.media.PICTURE_EFFECTS]
     * (a shader that shows it), adds that to the library and shows it.
     */
    suspend fun makeAlive(picture: MediaItem, effect: String): MediaItem {
        val path = picture.localPath ?: throw IllegalArgumentException("Only pictures on this PC can be used")
        val sample = shaders.effects().firstOrNull { it.name == effect } ?: throw IllegalArgumentException("Unknown effect: $effect")
        val item = withContext(Dispatchers.IO) {
            val title = "${picture.filename.substringBeforeLast('.')} · $effect"
            val id = shaders.create(title, sample.code, interactive = effect == "Parallax", preview = path)
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
            }
        }
        Automation(
            scope, wallpapers, playlists, scheduler, updates, settings, systemProbe,
            exclusive = { block -> changeWallpaper(block) },
            notify = { notify(it) },
            liveDisplays = { displays?.players()?.filterValues { it.active }?.keys ?: emptySet() },
            onStarted = { ready.complete(Unit) },
            lockSlideshow = lockSlideshow,
        ).start()
        watched.start()
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
