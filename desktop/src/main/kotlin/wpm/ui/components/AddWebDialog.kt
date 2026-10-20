package wpm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import wpm.AppState
import wpm.ToastKind
import wpm.media.parseVideoUrl
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** Add an interactive web wallpaper: a folder or .html/.zip (e.g. from Lively's gallery), or a website. */
@Composable
fun AddWebDialog(app: AppState, onDismiss: () -> Unit) {
    var website by remember { mutableStateOf("") }
    val url = parseVideoUrl(website)

    fun add(path: Path) {
        onDismiss()
        app.importWeb(listOf(path))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a web wallpaper") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText("Web wallpapers are pages with animations or games that react to your mouse. " +
                    "Use a folder with an index.html, a single .html file, or a .zip (Lively wallpapers work too).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder(app.window, "Choose a web wallpaper folder")?.let(::add) }) {
                        Icon(Icons.Outlined.Folder, null); Text("  Folder…")
                    }
                    OutlinedButton(onClick = {
                        val dialog = FileDialog(app.window as? Frame, "Choose a web wallpaper", FileDialog.LOAD).apply { file = "*.html;*.htm;*.zip" }
                        dialog.isVisible = true
                        dialog.files.firstOrNull()?.let { add(it.toPath()) }
                    }) { Icon(Icons.Outlined.UploadFile, null); Text("  .html or .zip file…") }
                }
                MutedText("Or show a website as your wallpaper (it is loaded live and needs a connection):")
                OutlinedTextField(website, { website = it }, singleLine = true, label = { Text("Website address") },
                    leadingIcon = { Icon(Icons.Outlined.Language, null) }, modifier = Modifier.fillMaxWidth(),
                    isError = website.isNotBlank() && url == null)
            }
        },
        confirmButton = {
            Button(onClick = {
                val link = url ?: return@Button
                onDismiss()
                app.runTask(failure = "Couldn't add the website") {
                    val id = app.web.addWebsite(link)
                    app.libraryChanged()
                    app.toast(if (id == null) "That website is already in your library" else "Website added", ToastKind.Success)
                }
            }, enabled = url != null) { Text("Add website") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
