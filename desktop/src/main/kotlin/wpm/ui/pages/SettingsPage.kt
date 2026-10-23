package wpm.ui.pages

import wpm.tr
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import wpm.ui.components.OrigamiMark
import wpm.ui.components.ButtonIcon
import wpm.ui.components.FilterChip
import wpm.ui.components.NumberField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import wpm.ui.components.OutlinedButton
import wpm.ui.components.SegmentedButton
import wpm.ui.components.SegmentedButtonDefaults
import wpm.ui.components.SingleChoiceSegmentedButtonRow
import wpm.ui.components.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import wpm.APP_NAME
import wpm.APP_VERSION
import wpm.AppState
import wpm.ToastKind
import wpm.logger
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.AccentColorSetting
import wpm.ui.components.Notice
import wpm.ui.components.ShortcutField
import wpm.ui.components.AppPauseRules
import wpm.SHORTCUT_ACTIONS
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.mpv.LIBMPV_DLL
import wpm.mpv.NativeBin
import wpm.services.RELEASES_PAGE
import wpm.services.REPO
import wpm.services.saveDiagnostics
import wpm.win32.Autostart
import wpm.ui.components.Button
import wpm.ui.components.TextButton
import java.awt.FileDialog
import java.awt.Frame
import java.time.LocalDate
import java.awt.Desktop
import java.nio.file.Path

private val log = logger("settings-page")

@Composable
fun SettingsPage(app: AppState) {
    PageScaffold(tr("Settings")) {
        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Appearance"))
            Spacer(Modifier.height(6.dp))
            MutedText(tr("Washi paper by day, indigo ink by night."))
            Spacer(Modifier.height(14.dp))
            val modes = listOf("light" to tr("Light"), "dark" to tr("Dark"), "system" to tr("System"))
            SingleChoiceSegmentedButtonRow {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = app.appearance == key,
                        onClick = { app.updateAppearance(key) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(6.dp))
            AccentColorSetting(app, Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp))
            LanguageSetting(app)
            SettingSwitch(tr("Reduce motion (no page animations)"), app.reduceMotion) { app.updateReduceMotion(it) }
            var accentOn by remember { mutableStateOf(app.accent.enabled) }
            var taskbarOn by remember { mutableStateOf(app.taskbarTint.enabled) }
            SettingSwitch(
                tr("Match the Windows accent color to the wallpaper"), accentOn,
                hint = tr("Taskbar, Start and window borders take the wallpaper's most striking color. Switching it off puts your own color back."),
            ) { on ->
                accentOn = on
                app.runTask {
                    app.accent.setEnabled(on)
                    app.updateAccent(force = true)
                    if (!on) app.taskbarTint.setEnabled(false)
                }
                if (!on) taskbarOn = false
            }
            SettingSwitch(
                tr("Tint the taskbar and Start with the wallpaper's color"), taskbarOn,
                hint = if (app.taskbarTint.api.darkTaskbar()) tr("Switches on the setting above too. Switching it off puts your own taskbar back.")
                else tr("Windows shows the color only when its mode is dark (Settings > Personalization > Colors > Choose your mode)."),
            ) { on ->
                taskbarOn = on
                if (on) accentOn = true
                app.runTask {
                    app.taskbarTint.setEnabled(on)
                    if (on && !app.accent.enabled) {
                        app.accent.setEnabled(true)
                        app.updateAccent(force = true)
                    }
                }
            }
            var musicOn by remember { mutableStateOf(app.settings.boolean("music_colors", false)) }
            SettingSwitch(
                tr("Colors follow the music"), musicOn,
                hint = tr("While a song plays (Spotify, a browser, any player), the app takes the color of its album art, and so does Windows when the setting above is on."),
            ) { on ->
                musicOn = on
                app.setMusicColors(on)
            }
            app.currentTrack?.takeIf { musicOn }?.let { track ->
                MutedText(tr("Now playing: {0}", listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" · ")))
            }
        }

        WatchedFoldersCard(app)

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Window"))
            var closeToTray by remember { mutableStateOf(app.settings.boolean("close_to_tray", true)) }
            SettingSwitch(tr("Keep running in the tray when the window is closed"), closeToTray) {
                closeToTray = it
                app.settings.set("close_to_tray", it)
            }
            var startup by remember { mutableStateOf(runCatching { Autostart.isEnabled() }.getOrDefault(false)) }
            SettingSwitch(
                tr("Start with Windows (hidden in the tray)"),
                startup,
                enabled = Autostart.available,
                hint = if (Autostart.available) null else tr("Available in the installed app."),
            ) { wanted ->
                try {
                    Autostart.setEnabled(wanted)
                    startup = wanted
                } catch (e: Exception) {
                    log.warning("Changing start with Windows failed: ${e.message}")
                    startup = runCatching { Autostart.isEnabled() }.getOrDefault(false)
                    app.toast(tr("Couldn't change start with Windows: {0}", e.message), ToastKind.Error)
                }
            }
        }

        ShortcutsCard(app)

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Live wallpapers"))
            Spacer(Modifier.height(4.dp))
            MutedText(
                if (app.videoSupport) tr("Video engine: libmpv found in {0}", NativeBin.dir.toAbsolutePath())
                else tr("Video engine missing: put {0} into {1}", LIBMPV_DLL, NativeBin.dir.toAbsolutePath()),
            )
            MutedText(if (app.ytDlp.available) tr("Video links: yt-dlp found") else tr("Video links need yt-dlp.exe in {0}", NativeBin.dir.toAbsolutePath()))
            if (app.ytDlp.available) {
                var updating by remember { mutableStateOf(false) }
                TextButton(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp), onClick = {
                    updating = true
                    app.runTask(failure = tr("Couldn't update yt-dlp")) {
                        try {
                            app.toast(app.ytDlp.update(), ToastKind.Success)
                        } finally {
                            updating = false
                        }
                    }
                }, enabled = !updating) { Text(if (updating) tr("Updating yt-dlp…") else tr("Update yt-dlp (if YouTube links stop working)")) }
            }
            TransitionSetting(app)
            BooleanSetting(app, "resume_live_wallpaper", true, tr("Start the live wallpaper again when the app starts"))
            var allDisplays by remember { mutableStateOf(app.settings.boolean("live_all_displays", true)) }
            SettingSwitch(tr("Show live wallpapers on every display (otherwise only the main one)"), allDisplays) { on ->
                allDisplays = on
                app.runTask { app.changeWallpaper { app.wallpapers.setAllDisplays(on) } }
            }
            BooleanSetting(app, "web_interaction", true, tr("Web wallpapers react to the mouse on the empty desktop"))
            BooleanSetting(app, "web_muted", true, tr("Mute web wallpapers (applies after restarting the app)"))
            BooleanSetting(app, "audio_reactive", true, tr("Audio-reactive wallpapers may listen to what your PC plays (analysed live, never recorded)"))
            BooleanSetting(app, "live_weather", true, tr("Living weather wallpapers may look up the weather for your city (at open-meteo.com, every 15 minutes)"))
            FrameRateSetting(app, "shader_fps", "60", tr("Shader frame rate (lower saves power)"), listOf("15", "30", "60"), restartKind = "shader")
            FrameRateSetting(app, "web_fps", "full", tr("Web wallpaper frame rate"), listOf("30", "full"), restartKind = "web")
            MutedText(
                when {
                    wpm.engine.Chromium.version() != null -> tr("Web engine: Chromium {0}", wpm.engine.Chromium.version())
                    wpm.engine.Chromium.installed(app.chromiumDir) -> tr("Web engine: installed")
                    else -> tr("Web engine: downloaded the first time you use a web wallpaper (about 150 MB)")
                },
            )
            BooleanSetting(app, "pause_when_hidden", true, tr("Pause while the PC is locked or the screen is off"))
            BooleanSetting(app, "pause_on_fullscreen", true, tr("Pause while a game or video runs full screen"))
            BooleanSetting(app, "pause_when_covered", true, tr("Pause while maximized windows cover the desktop"))
            BooleanSetting(app, "pause_on_battery", false, tr("Pause while running on battery"))
            BooleanSetting(app, "pause_when_app_focused", false, tr("Pause while any other app is in front (resumes when you click the desktop)"))
            AppPauseRules(app, Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp))
        }

        ScreensaverCard(app)

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Storage"))
            Spacer(Modifier.height(6.dp))
            MutedText(tr("Library, thumbnails and logs: {0}", app.paths.dataDir))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openFolder(app, app.paths.dataDir) }) { Text(tr("Open data folder")) }
                OutlinedButton(onClick = { openFolder(app, app.paths.logDir) }) { Text(tr("Open log folder")) }
            }
        }

        PerformanceCard(app)

        BackupCard(app)

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Updates"))
            BooleanSetting(app, "check_for_updates", true, tr("Check for new versions once a day"))
            var available by remember { mutableStateOf(app.updates.availableVersion()) }
            var checking by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MutedText(available?.let { tr("Version {0} is available (you have {1}).", it, APP_VERSION) } ?: tr("You have version {0}.", APP_VERSION))
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    checking = true
                    app.runTask(failure = tr("Couldn't check for updates")) {
                        try {
                            available = app.updates.checkIfDue(force = true)
                            app.toast(available?.let { tr("Version {0} is available", it) } ?: tr("You have the latest version"), ToastKind.Success)
                        } finally {
                            checking = false
                        }
                    }
                }, enabled = !checking) { Text(if (checking) tr("Checking…") else tr("Check now")) }
                if (available != null && !wpm.services.Updater.canInstall) {
                    Button(onClick = { openLink(app, RELEASES_PAGE) }) { Text(tr("Download")) }
                }
            }
            if (available != null && wpm.services.Updater.canInstall) {
                Spacer(Modifier.height(10.dp))
                OneClickUpdate(app)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("Diagnostics"))
            Spacer(Modifier.height(6.dp))
            MutedText(tr("If something doesn't work, save a diagnostics file and attach it to a bug report. It contains logs and settings, no pictures."))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                val dialog = FileDialog(app.window as? Frame, tr("Save diagnostics"), FileDialog.SAVE).apply {
                    file = tr("WallpaperManager-diagnostics-{0}.zip", LocalDate.now())
                }
                dialog.isVisible = true
                val name = dialog.file ?: return@OutlinedButton
                val target = Path.of(dialog.directory, if (name.endsWith(".zip")) name else "$name.zip")
                app.runTask(tr("Diagnostics saved"), tr("Couldn't save diagnostics")) {
                    saveDiagnostics(target, app.db, app.paths, app.wallpapers)
                }
            }) { Text(tr("Save diagnostics…")) }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle(tr("About"))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrigamiMark(34.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(APP_NAME, color = Theme.colors.text, style = MaterialTheme.typography.titleMedium)
                    MutedText(tr("Version {0}", APP_VERSION))
                }
                TextButton(onClick = { openLink(app, "https://github.com/$REPO") }) { Text(tr("Project page")); ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew) }
            }
        }
    }
}

/** System / English / Deutsch; takes effect after a restart (offered right away). */
@Composable
private fun LanguageSetting(app: AppState) {
    val started = remember { wpm.I18n.language }
    var choice by remember { mutableStateOf(app.settings.string("language", "system")) }
    val options = listOf("system" to tr("Windows language")) + wpm.LANGUAGES.entries.map { it.key to it.value }
    Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Language"), color = Theme.colors.text)
            if (wpm.I18n.resolve(choice) != started) MutedText(tr("Restart the app to switch languages."))
        }
        if (wpm.I18n.resolve(choice) != started) {
            TextButton(onClick = { app.restart() }) { Text(tr("Restart now")) }
        }
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, (code, label) ->
                SegmentedButton(choice == code, {
                    choice = code
                    app.settings.set("language", code)
                }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
    }
}

/** Downloads the new version's installer and runs it; the app closes and comes back updated. */
@Composable
private fun OneClickUpdate(app: AppState) {
    var progress by remember { mutableStateOf<Double?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val p = progress
        if (p != null) {
            androidx.compose.material3.LinearProgressIndicator({ p.toFloat() }, Modifier.weight(1f))
            MutedText("Downloading… ${(p * 100).toInt()} %")
        } else {
            MutedText(tr("Installs in the background: the app closes for a moment and comes back updated."), Modifier.weight(1f))
            TextButton(onClick = { openLink(app, RELEASES_PAGE) }) { Text(tr("What's new")) }
            Button(onClick = {
                val release = app.updates.availableRelease() ?: return@Button app.toast(tr("Check for updates again first"))
                progress = 0.0
                app.runTask(failure = tr("Couldn't update")) {
                    try {
                        val installer = wpm.services.Updater.download(release, app.paths.cacheDir.resolve("updates")) { progress = it }
                        wpm.services.Updater.launch(installer)
                        javax.swing.SwingUtilities.invokeLater { app.exitApp() }
                    } finally {
                        progress = null
                    }
                }
            }) { Text(tr("Install now")) }
        }
    }
}

/** A frame rate choice; the live wallpaper of [restartKind] restarts to use it. */
@Composable
private fun FrameRateSetting(app: AppState, key: String, default: String, label: String, options: List<String>, restartKind: String) {
    var value by remember { mutableStateOf(app.settings.string(key, default)) }
    Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Theme.colors.text, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, option ->
                SegmentedButton(value == option, {
                    value = option
                    app.settings.set(key, option)
                    app.runTask { app.changeWallpaper { if (app.wallpapers.state.kind == restartKind) app.wallpapers.restartLive() } }
                }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(if (option == "full") tr("Full") else "$option fps") }
            }
        }
    }
}

/** How one picture changes to the next on the desktop. */
@Composable
private fun TransitionSetting(app: AppState) {
    val options = listOf("off" to tr("Off"), "Fold" to tr("Fold"), "Fade" to tr("Fade"), "Dissolve" to tr("Dissolve"),
        "Ripple" to tr("Ripple"), "random" to tr("Random"))
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Picture changes"), color = Theme.colors.text)
            MutedText(tr("How the desktop goes from one picture to the next (needs the web engine; off with \"reduce motion\")."))
        }
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(app.transitionStyle == key, { app.updateTransitionStyle(key) },
                    SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
    }
}

/** What the app and its engines use right now, refreshed every two seconds while visible. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScreensaverCard(app: AppState) {
    val setup = app.screensaverSetup
    var on by remember { mutableStateOf(setup.enabled) }
    var minutes by remember { mutableStateOf(setup.minutes.toString()) }
    var source by remember { mutableStateOf(setup.source) }
    val playlists = remember(app.libraryVersion) { app.db.listPlaylists() }
    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Screensaver"))
        SettingSwitch(
            tr("Use Wallpaper Manager as the Windows screensaver"), on,
            hint = tr("Shows your wallpaper (live ones keep playing) or a playlist's pictures when the PC is idle. Switching it off puts your previous screensaver back."),
        ) { value ->
            app.runTask(failure = tr("Couldn't change the screensaver")) {
                withContext(Dispatchers.IO) { setup.setEnabled(value) }
                on = value
            }
        }
        Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Start after this many minutes without input"), color = Theme.colors.text, modifier = Modifier.weight(1f))
            NumberField(minutes, { text ->
                minutes = text.filter(Char::isDigit).take(3)
                minutes.toIntOrNull()?.let { m -> app.runTask { withContext(Dispatchers.IO) { setup.setMinutes(m) } } }
            })
        }
        Column(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("It shows"), color = Theme.colors.text)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf("wallpaper" to tr("The desktop wallpaper")) + playlists.map { "playlist:${it.id}" to tr("Playlist: {0}", it.name) }
                options.forEach { (value, label) ->
                    FilterChip(source == value, {
                        source = value
                        setup.setSource(value)
                    }, { Text(label) })
                }
            }
        }
        TextButton(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp), onClick = {
            app.runTask(failure = tr("Couldn't start the screensaver")) { withContext(Dispatchers.IO) { app.startScreensaver() } }
        }) { Text(tr("Try it now (move the mouse to stop)")) }
    }
}

@Composable
private fun PerformanceCard(app: AppState) {
    var usage by remember { mutableStateOf<wpm.win32.ResourceUsage?>(null) }
    LaunchedEffect(Unit) {
        // On a background thread with real time, so the page counts as idle between samples.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            wpm.win32.ResourceMonitor().use { monitor ->
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    usage = runCatching { monitor.sample() }.getOrNull()
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Performance"))
        Spacer(Modifier.height(6.dp))
        MutedText(tr("Wallpaper Manager with its video and web engines, right now. Lower frame rates and the pause options above save the most."))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            UsageFigure(tr("CPU"), usage?.let { "%.1f %%".format(java.util.Locale.ROOT, it.cpuPercent) })
            UsageFigure(tr("Memory"), usage?.let { "${it.memoryMb} MB" })
            UsageFigure(tr("GPU"), usage?.let { u -> u.gpuPercent?.let { "%.0f %%".format(java.util.Locale.ROOT, it) } ?: "n/a" })
            UsageFigure(tr("Processes"), usage?.processes?.toString())
        }
    }
}

@Composable
private fun UsageFigure(label: String, value: String?) {
    Column {
        Text(label.uppercase(), color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall)
        Text(value ?: "…", color = Theme.colors.text, style = MaterialTheme.typography.titleLarge)
    }
}

/** Folders whose new and deleted pictures and videos show up in the library by themselves. */
@Composable
private fun WatchedFoldersCard(app: AppState) {
    var folders by remember { mutableStateOf(app.watched.folders) }
    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Watched folders"))
        Spacer(Modifier.height(6.dp))
        MutedText(tr("New pictures and videos in these folders are added to the library, and deleted ones removed. Moving files around inside them keeps their playlists and tags."))
        Spacer(Modifier.height(8.dp))
        folders.forEach { folder ->
            Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(folder.toString(), color = Theme.colors.text, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = {
                    app.runTask(tr("Stopped watching {0}", folder.fileName)) {
                        app.watched.remove(folder)
                        folders = app.watched.folders
                    }
                }) { Text(tr("Stop watching")) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val folder = wpm.ui.components.pickFolder(app.window, tr("Watch a folder")) ?: return@OutlinedButton
                app.runTask(failure = tr("Couldn't watch the folder")) {
                    val result = app.watched.add(folder)
                    folders = app.watched.folders
                    app.libraryChanged()
                    app.toast(tr("Watching {0}: {1} added", folder.fileName, result.added), ToastKind.Success)
                }
            }) { Text(tr("Watch a folder…")) }
            if (folders.isNotEmpty()) {
                TextButton(onClick = {
                    app.runTask(failure = tr("Couldn't check the folders")) {
                        val result = app.watched.syncAll()
                        app.libraryChanged()
                        app.toast(if (result.changed) tr("{0} added, {1} removed, {2} moved", result.added, result.removed, result.moved) else tr("Everything is up to date"))
                    }
                }) { Text(tr("Check now")) }
            }
        }
    }
}

/** Saves the library, playlists, schedule and settings to one file, and brings them back (e.g. on a new PC). */
@Composable
private fun BackupCard(app: AppState) {
    var restoring by remember { mutableStateOf<Pair<Path, wpm.services.BackupInfo>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Backup"))
        Spacer(Modifier.height(6.dp))
        MutedText(tr("One file with your library, playlists, schedule, settings, shaders and thumbnails. The wallpaper files themselves ") +
            tr("aren't included; on another PC, Library > Missing files finds them again."))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dialog = FileDialog(app.window as? Frame, tr("Save a backup"), FileDialog.SAVE).apply {
                    file = tr("WallpaperManager-backup-{0}.{1}", LocalDate.now(), wpm.services.Backup.EXTENSION)
                }
                dialog.isVisible = true
                val name = dialog.file ?: return@OutlinedButton
                val target = Path.of(dialog.directory, if (name.endsWith(".${wpm.services.Backup.EXTENSION}")) name else "$name.${wpm.services.Backup.EXTENSION}")
                app.runTask(failure = tr("Couldn't save the backup")) {
                    val info = wpm.services.Backup.export(target, app.db, app.paths)
                    app.toast(tr("Backup saved ({0} wallpapers)", info.items), ToastKind.Success)
                }
            }) { Text(tr("Back up…")) }
            OutlinedButton(onClick = {
                val dialog = FileDialog(app.window as? Frame, tr("Restore a backup"), FileDialog.LOAD).apply {
                    file = "*.${wpm.services.Backup.EXTENSION}"
                }
                dialog.isVisible = true
                val name = dialog.file ?: return@OutlinedButton
                val file = Path.of(dialog.directory, name)
                app.runTask(failure = tr("Couldn't read the backup")) { restoring = file to wpm.services.Backup.inspect(file) }
            }) { Text(tr("Restore…")) }
        }
    }
    restoring?.let { (file, info) ->
        wpm.ui.components.ConfirmDialog(
            tr("Restore this backup?"),
            tr("From {0} (version {1}, {2} wallpapers). It replaces your current library, ", info.created.take(16).replace('T', ' '), info.version, info.items) +
                tr("playlists, schedule and settings; they are kept in a before-restore folder in the data folder. The app restarts."),
            tr("Restore and restart"),
            onDismiss = { restoring = null },
            onConfirm = {
                restoring = null
                app.runTask(failure = tr("Couldn't restore the backup")) {
                    wpm.services.Backup.stage(file, app.paths)
                    javax.swing.SwingUtilities.invokeLater { app.restart() }
                }
            },
        )
    }
}

@Composable
private fun ShortcutsCard(app: AppState) {
    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Keyboard shortcuts"))
        Spacer(Modifier.height(6.dp))
        MutedText(tr("They work everywhere, even while another app is in front."))
        var enabled by remember { mutableStateOf(app.shortcutsEnabled) }
        var keys by remember { mutableStateOf(app.shortcutKeys()) }
        fun save() = app.runTask { app.updateShortcuts(enabled, keys) }
        SettingSwitch(tr("Use global keyboard shortcuts"), enabled) {
            enabled = it
            save()
        }
        app.shortcutProblems[""]?.let { Notice(it) }
        SHORTCUT_ACTIONS.forEach { action ->
            Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(action.label, color = if (enabled) Theme.colors.text else Theme.colors.textSubtle)
                    if (enabled) app.shortcutProblems[action.id]?.let { Text(it, color = Theme.colors.danger, style = MaterialTheme.typography.bodySmall) }
                }
                ShortcutField(
                    keys[action.id].orEmpty(),
                    onChange = {
                        keys = keys + (action.id to it)
                        save()
                    },
                    onHint = { app.toast(it) },
                    enabled = enabled,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MutedText(tr("Click a shortcut and press the new keys (Backspace removes it). Scripts can use the command line too, e.g. WallpaperManager.exe --next; --help lists every option."))
    }
}

@Composable
private fun BooleanSetting(app: AppState, key: String, default: Boolean, label: String) {
    var value by remember { mutableStateOf(app.settings.boolean(key, default)) }
    SettingSwitch(label, value) {
        value = it
        app.settings.set(key, it)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, hint: String? = null,
                          onChange: (Boolean) -> Unit) {
    // The whole row is the switch: clicking the label toggles it, and screen readers read label and state together.
    Row(
        Modifier.fillMaxWidth().settingDivider()
            .toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = if (enabled) Theme.colors.text else Theme.colors.textSubtle)
            if (hint != null) MutedText(hint)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun openLink(app: AppState, url: String) {
    try {
        Desktop.getDesktop().browse(java.net.URI(url))
    } catch (e: Exception) {
        app.toast(tr("Couldn't open the browser: {0}", e.message), ToastKind.Error)
    }
}

private fun openFolder(app: AppState, folder: Path) {
    try {
        Desktop.getDesktop().open(folder.toFile())
    } catch (e: Exception) {
        app.toast(tr("Couldn't open the folder: {0}", e.message), ToastKind.Error)
    }
}

/** A hairline above each setting row. */
@Composable
private fun Modifier.settingDivider(): Modifier {
    val color = Theme.colors.cardBorder
    return drawBehind { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
}
