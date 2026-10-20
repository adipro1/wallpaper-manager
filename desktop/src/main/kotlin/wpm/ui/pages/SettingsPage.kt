package wpm.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.mpv.LIBMPV_DLL
import wpm.mpv.NativeBin
import wpm.win32.Autostart
import java.awt.Desktop
import java.nio.file.Path

private val log = logger("settings-page")

@Composable
fun SettingsPage(app: AppState) {
    PageScaffold("Settings") {
        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Appearance")
            Spacer(Modifier.height(12.dp))
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

        Card(Modifier.fillMaxWidth()) {
            SectionTitle("Live wallpapers")
            Spacer(Modifier.height(4.dp))
            MutedText(
                if (app.videoSupport) "Video engine: libmpv found in ${NativeBin.dir.toAbsolutePath()}"
                else "Video engine missing: put $LIBMPV_DLL into ${NativeBin.dir.toAbsolutePath()}",
            )
            BooleanSetting(app, "resume_live_wallpaper", true, "Start the live wallpaper again when the app starts")
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
            SectionTitle("About")
            Spacer(Modifier.height(6.dp))
            MutedText("$APP_NAME $APP_VERSION")
        }
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
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Theme.colors.text else Theme.colors.textSubtle)
            if (hint != null) MutedText(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun openFolder(app: AppState, folder: Path) {
    try {
        Desktop.getDesktop().open(folder.toFile())
    } catch (e: Exception) {
        app.toast("Couldn't open the folder: ${e.message}", ToastKind.Error)
    }
}
