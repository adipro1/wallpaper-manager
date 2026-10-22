package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wpm.AppState
import wpm.services.AppPauseRule
import wpm.services.appPauseRules
import wpm.services.appRulesSerializer
import wpm.services.normalizeExe
import wpm.ui.Theme
import wpm.win32.SystemState
import java.awt.FileDialog
import java.awt.Frame

/** "Pause while chrome.exe is in front / running": the list, and adding apps that are open now or by browsing. */
@Composable
fun AppPauseRules(app: AppState, modifier: Modifier = Modifier) {
    var rules by remember { mutableStateOf(appPauseRules(app.settings)) }
    fun save(new: List<AppPauseRule>) {
        rules = new.distinctBy { it.exe }
        app.settings.set("pause_apps", appRulesSerializer, rules)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Pause for these apps"), color = Theme.colors.text)
        if (rules.isEmpty()) MutedText(tr("None yet. Add a game, video player or anything else the wallpaper shouldn't run beside."))
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(rule.exe, color = Theme.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf(false to tr("While in front"), true to tr("While running")).forEachIndexed { i, (running, label) ->
                        SegmentedButton(rule.whileRunning == running, {
                            save(rules.map { if (it.exe == rule.exe) it.copy(whileRunning = running) else it })
                        }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                IconButton(onClick = { save(rules - rule) }) { Icon(Icons.Outlined.Close, tr("Remove {0}", rule.exe)) }
            }
        }
        var open by remember { mutableStateOf(false) }
        var running by remember { mutableStateOf(emptyList<String>()) }
        Box {
            TextButton(onClick = {
                running = runCatching { SystemState.windowedApps() }.getOrDefault(emptyList()).filter { exe -> rules.none { it.exe == exe } }
                open = true
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                ButtonIcon(Icons.Outlined.Add)
                Text(tr("Add an app"))
            }
            DropdownMenu(open, { open = false }) {
                running.forEach { exe ->
                    DropdownMenuItem(text = { Text(exe) }, onClick = {
                        open = false
                        save(rules + AppPauseRule(exe))
                    })
                }
                if (running.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text(tr("Browse for a program…")) }, onClick = {
                    open = false
                    val dialog = FileDialog(app.window as? Frame, tr("Choose a program"), FileDialog.LOAD).apply {
                        file = "*.exe"
                        setFilenameFilter { _, name -> name.endsWith(".exe", ignoreCase = true) }
                    }
                    dialog.isVisible = true
                    dialog.file?.let { normalizeExe(it) }?.takeIf { it.isNotEmpty() }?.let { save(rules + AppPauseRule(it)) }
                })
            }
        }
    }
}
