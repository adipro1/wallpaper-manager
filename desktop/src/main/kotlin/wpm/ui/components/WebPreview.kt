package wpm.ui.components

import wpm.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.engine.BrowserWindow
import wpm.engine.fillChildren
import wpm.engine.onEdt
import wpm.engine.openBrowserWindow
import wpm.logger
import wpm.win32.embedWindow
import wpm.win32.fitToParent
import java.awt.Canvas
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

private val log = logger("web-preview")

/**
 * A live web page inside the app (e.g. the shader editor's preview). Chromium's window is
 * placed inside a native canvas; like any native window it is drawn above Compose content.
 */
@Composable
fun WebPreview(app: AppState, url: String?, modifier: Modifier = Modifier, onTitle: (String?) -> Unit = {}) {
    val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
    var window by remember { mutableStateOf<BrowserWindow?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (!canvas.isDisplayable) delay(30)
        val parent = HWND(Native.getComponentPointer(canvas))
        window = withContext(Dispatchers.IO) {
            runCatching {
                openBrowserWindow(app.startChromium(), url ?: "about:blank", onTitle = onTitle).also { w ->
                    onEdt { embedWindow(w.hwnd, parent) }
                    val (width, height) = fitToParent(w.hwnd, parent)
                    fillChildren(w.hwnd, width, height)
                }
            }.onFailure {
                log.warning("Preview failed: $it")
                failed = it.message
            }.getOrNull()
        }
    }
    LaunchedEffect(url, window) {
        val w = window ?: return@LaunchedEffect
        if (url != null) onEdt { w.browser.loadURL(url) }
    }
    DisposableEffect(Unit) {
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = window ?: return
                val (width, height) = fitToParent(w.hwnd, HWND(Native.getComponentPointer(canvas)))
                fillChildren(w.hwnd, width, height)
            }
        }
        canvas.addComponentListener(listener)
        onDispose {
            canvas.removeComponentListener(listener)
            window?.let { w -> Thread { runCatching { w.close() } }.start() }
            window = null
        }
    }
    Box(modifier) {
        SwingPanel(background = Color.Black, factory = { canvas }, modifier = Modifier.matchParentSize())
        when {
            failed != null -> Text(tr("Preview unavailable: {0}", failed), color = Color.White, modifier = Modifier.align(Alignment.Center))
            window == null -> Box(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator()
            }
        }
    }
}
