package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.ToastKind
import wpm.media.EngineProject
import wpm.media.WallpaperEngine
import wpm.ui.Theme

/**
 * Lists Wallpaper Engine projects (Steam workshop subscriptions and its own projects) and adds
 * the video and web ones to the library. Scenes and programs are shown greyed out.
 */
@Composable
fun EngineImportDialog(app: AppState, onDismiss: () -> Unit) {
    var projects by remember { mutableStateOf<List<EngineProject>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<java.nio.file.Path>()) }
    var extraFolder by remember { mutableStateOf<java.nio.file.Path?>(null) }
    LaunchedEffect(extraFolder) {
        val found = withContext(Dispatchers.IO) {
            val fromSteam = runCatching { WallpaperEngine.findProjects() }.getOrDefault(emptyList())
            // A chosen folder may be one project, or a folder of projects (e.g. a copied workshop folder).
            val fromFolder = extraFolder?.let { folder ->
                WallpaperEngine.read(folder)?.let(::listOf)
                    ?: runCatching { java.nio.file.Files.list(folder).use { s -> s.toList() } }.getOrDefault(emptyList()).mapNotNull(WallpaperEngine::read)
            }.orEmpty()
            (fromFolder + fromSteam).distinctBy { it.folder }
        }
        projects = found
        chosen = chosen + found.filter { it.supported }.map { it.folder }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Import from Wallpaper Engine")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(tr("Video and web wallpapers from your Steam workshop subscriptions play here too. Scene wallpapers need Wallpaper Engine itself."))
                val list = projects
                when {
                    list == null -> CircularProgressIndicator(color = Theme.colors.accent)
                    list.isEmpty() -> MutedText(tr("No Wallpaper Engine projects found. If they are somewhere else, choose the folder."))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(list, key = { it.folder.toString() }) { project ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.title, color = if (project.supported) Theme.colors.text else Theme.colors.textSubtle,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(project.reason ?: project.type.replaceFirstChar { it.uppercase() }, color = Theme.colors.textMuted, fontSize = 12.sp)
                                }
                                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                                Switch(project.folder in chosen, { on -> chosen = if (on) chosen + project.folder else chosen - setOf(project.folder) },
                                    enabled = project.supported)
                            }
                        }
                    }
                }
                TextButton(onClick = { pickFolder(app.window, tr("Choose a Wallpaper Engine project folder"))?.let { extraFolder = it } }) {
                    Text(tr("Choose a folder…"))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val picked = projects.orEmpty().filter { it.folder in chosen && it.supported }
                onDismiss()
                app.runTask(failure = tr("Couldn't import from Wallpaper Engine")) {
                    var added = 0
                    val failed = mutableListOf<String>()
                    for (project in picked) {
                        runCatching { WallpaperEngine.import(project, app.db, app.library, app.web) }
                            .onSuccess { if (it != null) added++ }
                            .onFailure { failed += project.title }
                    }
                    app.libraryChanged()
                    app.toast(
                        tr("Added {0}", added) + (if (picked.size - added - failed.size > 0) tr(", {0} already in your library", picked.size - added - failed.size) else "") +
                            (if (failed.isNotEmpty()) tr(", couldn't add {0}", failed.joinToString()) else ""),
                        if (failed.isEmpty()) ToastKind.Success else ToastKind.Error,
                    )
                }
            }, enabled = chosen.isNotEmpty()) { Text(tr("Import {0}", chosen.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
