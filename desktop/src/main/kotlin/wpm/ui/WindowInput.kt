package wpm.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import wpm.AppState
import wpm.Page
import wpm.Shortcut
import wpm.logger
import wpm.ui.components.pickMediaFiles
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Path

private val log = logger("input")

private val pageKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven)

/**
 * Window-wide shortcuts: Ctrl+1…7 switch pages, Ctrl+F searches, Ctrl+I imports, and
 * Ctrl+A / Delete / Esc act on the library. Runs after focused text fields, so typing isn't affected.
 */
fun handleShortcut(app: AppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        val index = pageKeys.indexOf(event.key)
        if (index >= 0) {
            app.page = Page.entries[index]
            return true
        }
        when (event.key) {
            Key.F -> { app.page = Page.Library; app.shortcut(Shortcut.Find); return true }
            Key.I -> { pickMediaFiles(app.window).takeIf { it.isNotEmpty() }?.let(app::import); return true }
            Key.A -> if (app.page == Page.Library) { app.shortcut(Shortcut.SelectAll); return true }
        }
        return false
    }
    if (app.page != Page.Library) return false
    return when (event.key) {
        Key.Delete -> app.shortcut(Shortcut.Delete)
        Key.Escape -> app.shortcut(Shortcut.Escape)
        else -> false
    }
}

/** Accepts files and folders dragged from Explorer onto the window. */
fun installFileDrop(window: Window, onDrop: (List<Path>) -> Unit) {
    window.dropTarget = object : DropTarget() {
        override fun dragEnter(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY)
            else event.rejectDrag()
        }

        override fun drop(event: DropTargetDropEvent) {
            try {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                @Suppress("UNCHECKED_CAST")
                val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                event.dropComplete(true)
                if (files.isNotEmpty()) onDrop(files.map { it.toPath() })
            } catch (e: Exception) {
                log.warning("Drop failed: $e")
                event.dropComplete(false)
            }
        }
    }
}
