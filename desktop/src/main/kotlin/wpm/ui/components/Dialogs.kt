package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import wpm.media.IMAGE_EXTENSIONS
import wpm.media.VIDEO_EXTENSIONS
import wpm.ui.Theme
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                danger = danger,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String = "", confirm: String = tr("OK"), onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(); onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Native Windows "open files" dialog filtered to supported media. */
fun pickMediaFiles(owner: Window?): List<Path> {
    val dialog = FileDialog(owner as? Frame, tr("Import wallpapers"), FileDialog.LOAD).apply {
        isMultipleMode = true
        // Windows shows this pattern as the file type filter.
        file = (IMAGE_EXTENSIONS + VIDEO_EXTENSIONS).joinToString(";") { "*.$it" }
    }
    dialog.isVisible = true
    return dialog.files.map { it.toPath() }
}

/** Folder picker (FileDialog can't choose folders on Windows). */
fun pickFolder(owner: Window?, title: String = tr("Import a folder")): Path? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
