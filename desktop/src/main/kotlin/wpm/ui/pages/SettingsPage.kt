package wpm.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import wpm.ui.components.OrigamiMark
import wpm.ui.components.ButtonIcon
import androidx.compose.foundation.layout.Arrangement
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
import wpm.ui.components.Notice
import wpm.ui.components.ShortcutField
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
    PageScaffold("Settings") {
        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Appearance")
            Spacer(Modifier.height(6.dp))
            MutedText("Washi paper by day, indigo ink by night.")
            Spacer(Modifier.height(14.dp))
            val modes = listOf("light" to "Light", "dark" to "Dark", "system" to "System")
            SingleChoiceSegmentedButtonRow {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = app.appearance == key,
                        onClick = { app.updateAppearance(key) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Window")
            var closeToTray by remember { mutableStateOf(app.settings.boolean("close_to_tray", true)) }
            SettingSwitch("Keep running in the tray when the window is closed", closeToTray) {
                closeToTray = it
                app.settings.set("close_to_tray", it)
            }
            var startup by remember { mutableStateOf(runCatching { Autostart.isEnabled() }.getOrDefault(false)) }
            SettingSwitch(
                "Start with Windows (hidden in the tray)",
                startup,
                enabled = Autostart.available,
                hint = if (Autostart.available) null else "Available in the installed app.",
            ) { wanted ->
                try {
                    Autostart.setEnabled(wanted)
                    startup = wanted
                } catch (e: Exception) {
                    log.warning("Changing start with Windows failed: ${e.message}")
                    startup = runCatching { Autostart.isEnabled() }.getOrDefault(false)
                    app.toast("Couldn't change start with Windows: ${e.message}", ToastKind.Error)
                }
            }
        }

        ShortcutsCard(app)

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Live wallpapers")
            Spacer(Modifier.height(4.dp))
            MutedText(
                if (app.videoSupport) "Video engine: libmpv found in ${NativeBin.dir.toAbsolutePath()}"
                else "Video engine missing: put $LIBMPV_DLL into ${NativeBin.dir.toAbsolutePath()}",
            )
            MutedText(if (app.ytDlp.available) "Video links: yt-dlp found" else "Video links need yt-dlp.exe in ${NativeBin.dir.toAbsolutePath()}")
            if (app.ytDlp.available) {
                var updating by remember { mutableStateOf(false) }
                TextButton(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp), onClick = {
                    updating = true
                    app.runTask(failure = "Couldn't update yt-dlp") {
                        try {
                            app.toast(app.ytDlp.update(), ToastKind.Success)
                        } finally {
                            updating = false
                        }
                    }
                }, enabled = !updating) { Text(if (updating) "Updating yt-dlp…" else "Update yt-dlp (if YouTube links stop working)") }
            }
            BooleanSetting(app, "resume_live_wallpaper", true, "Start the live wallpaper again when the app starts")
            var allDisplays by remember { mutableStateOf(app.settings.boolean("live_all_displays", true)) }
            SettingSwitch("Show live wallpapers on every display (otherwise only the main one)", allDisplays) { on ->
                allDisplays = on
                app.runTask { app.changeWallpaper { app.wallpapers.setAllDisplays(on) } }
            }
            BooleanSetting(app, "web_interaction", true, "Web wallpapers react to the mouse on the empty desktop")
            BooleanSetting(app, "web_muted", true, "Mute web wallpapers (applies after restarting the app)")
            BooleanSetting(app, "audio_reactive", true, "Audio-reactive wallpapers may listen to what your PC plays (analysed live, never recorded)")
            var fps by remember { mutableStateOf(app.settings.string("shader_fps", "60")) }
            Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Shader frame rate (lower saves power)", color = Theme.colors.text, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf("30", "60").forEachIndexed { i, value ->
                        SegmentedButton(fps == value, {
                            fps = value
                            app.settings.set("shader_fps", value)
                            app.runTask { app.changeWallpaper { if (app.wallpapers.state.kind == "shader") app.wallpapers.restartLive() } }
                        }, SegmentedButtonDefaults.itemShape(i, 2)) { Text("$value fps") }
                    }
                }
            }
            MutedText(
                when {
                    wpm.engine.Chromium.version() != null -> "Web engine: Chromium ${wpm.engine.Chromium.version()}"
                    wpm.engine.Chromium.installed(app.chromiumDir) -> "Web engine: installed"
                    else -> "Web engine: downloaded the first time you use a web wallpaper (about 150 MB)"
                },
            )
            BooleanSetting(app, "pause_when_hidden", true, "Pause while the PC is locked or the screen is off")
            BooleanSetting(app, "pause_on_fullscreen", true, "Pause while a game or video runs full screen")
            BooleanSetting(app, "pause_when_covered", true, "Pause while maximized windows cover the desktop")
            BooleanSetting(app, "pause_on_battery", false, "Pause while running on battery")
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Storage")
            Spacer(Modifier.height(6.dp))
            MutedText("Library, thumbnails and logs: ${app.paths.dataDir}")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openFolder(app, app.paths.dataDir) }) { Text("Open data folder") }
                OutlinedButton(onClick = { openFolder(app, app.paths.logDir) }) { Text("Open log folder") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Updates")
            BooleanSetting(app, "check_for_updates", true, "Check for new versions once a day")
            var available by remember { mutableStateOf(app.updates.availableVersion()) }
            var checking by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MutedText(available?.let { "Version $it is available (you have $APP_VERSION)." } ?: "You have version $APP_VERSION.")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    checking = true
                    app.runTask(failure = "Couldn't check for updates") {
                        try {
                            available = app.updates.checkIfDue(force = true)
                            app.toast(available?.let { "Version $it is available" } ?: "You have the latest version", ToastKind.Success)
                        } finally {
                            checking = false
                        }
                    }
                }, enabled = !checking) { Text(if (checking) "Checking…" else "Check now") }
                if (available != null) {
                    Button(onClick = { openLink(app, RELEASES_PAGE) }) { Text("Download") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Diagnostics")
            Spacer(Modifier.height(6.dp))
            MutedText("If something doesn't work, save a diagnostics file and attach it to a bug report. It contains logs and settings, no pictures.")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                val dialog = FileDialog(app.window as? Frame, "Save diagnostics", FileDialog.SAVE).apply {
                    file = "WallpaperManager-diagnostics-${LocalDate.now()}.zip"
                }
                dialog.isVisible = true
                val name = dialog.file ?: return@OutlinedButton
                val target = Path.of(dialog.directory, if (name.endsWith(".zip")) name else "$name.zip")
                app.runTask("Diagnostics saved", "Couldn't save diagnostics") {
                    saveDiagnostics(target, app.db, app.paths, app.wallpapers)
                }
            }) { Text("Save diagnostics…") }
        }

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("About")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrigamiMark(34.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(APP_NAME, color = Theme.colors.text, style = MaterialTheme.typography.titleMedium)
                    MutedText("Version $APP_VERSION")
                }
                TextButton(onClick = { openLink(app, "https://github.com/$REPO") }) { Text("Project page"); ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew) }
            }
        }
    }
}

@Composable
private fun ShortcutsCard(app: AppState) {
    Card(Modifier.fillMaxWidth()) {
        SectionTitle("Keyboard shortcuts")
        Spacer(Modifier.height(6.dp))
        MutedText("They work everywhere, even while another app is in front.")
        var enabled by remember { mutableStateOf(app.shortcutsEnabled) }
        var keys by remember { mutableStateOf(app.shortcutKeys()) }
        fun save() = app.runTask { app.updateShortcuts(enabled, keys) }
        SettingSwitch("Use global keyboard shortcuts", enabled) {
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
        MutedText("Click a shortcut and press the new keys (Backspace removes it). Scripts can use the command line too, e.g. WallpaperManager.exe --next; --help lists every option.")
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
    Row(Modifier.fillMaxWidth().settingDivider().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = if (enabled) Theme.colors.text else Theme.colors.textSubtle)
            if (hint != null) MutedText(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun openLink(app: AppState, url: String) {
    try {
        Desktop.getDesktop().browse(java.net.URI(url))
    } catch (e: Exception) {
        app.toast("Couldn't open the browser: ${e.message}", ToastKind.Error)
    }
}

private fun openFolder(app: AppState, folder: Path) {
    try {
        Desktop.getDesktop().open(folder.toFile())
    } catch (e: Exception) {
        app.toast("Couldn't open the folder: ${e.message}", ToastKind.Error)
    }
}

/** A hairline above each setting row. */
@Composable
private fun Modifier.settingDivider(): Modifier {
    val color = Theme.colors.cardBorder
    return drawBehind { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
}
