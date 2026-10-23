package wpm.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import wpm.media.Imaging
import wpm.widgets.WidgetData
import wpm.widgets.WidgetKind
import wpm.widgets.WidgetPainter
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JWindow
import javax.swing.SwingUtilities

/** What the screensaver shows: a slideshow of [pictures], or (when empty) the live wallpaper lent to it. */
data class ScreensaverShow(val pictures: List<Path>, val accent: Int)

/**
 * Black windows over every display, always on top, without a mouse pointer. The main display's
 * window shows the slideshow, or hosts the live wallpaper's own window ([primary]).
 */
class ScreensaverHost(show: ScreensaverShow) {
    private val windows: List<JWindow>
    /** The main display's window, for the live wallpaper. */
    val primary: HWND

    init {
        var created = emptyList<JWindow>()
        var main: JWindow? = null
        SwingUtilities.invokeAndWait {
            val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val blank = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
            created = environment.screenDevices.map { device ->
                JWindow().apply {
                    background = java.awt.Color.BLACK
                    contentPane.background = java.awt.Color.BLACK
                    bounds = device.defaultConfiguration.bounds
                    isAlwaysOnTop = true
                    cursor = blank
                    if (device == environment.defaultScreenDevice) {
                        main = this
                        if (show.pictures.isNotEmpty()) {
                            contentPane.add(ComposePanel().apply {
                                cursor = blank
                                setContent { Slideshow(show) }
                            })
                        }
                    }
                    isVisible = true
                    toFront()
                }
            }
        }
        windows = created
        primary = HWND(Native.getComponentPointer(main ?: created.first()))
    }

    fun close() = SwingUtilities.invokeLater { windows.forEach { it.dispose() } }
}

/** The pictures one after another, each slowly zooming, cross-fading, with the time in a corner. */
@Composable
private fun Slideshow(show: ScreensaverShow) {
    var shown by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    LaunchedEffect(show) {
        var index = 0
        var count = 0
        while (true) {
            val path = show.pictures[index % show.pictures.size]
            val picture = withContext(Dispatchers.IO) {
                runCatching { Imaging.load(path).use { image -> Imaging.contain(image, 3840, 2160).use { it.toComposeImageBitmap() } } }.getOrNull()
            }
            if (picture != null) shown = count++ to picture
            index++
            delay(if (show.pictures.size == 1) Long.MAX_VALUE else 12_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(shown, animationSpec = tween(2_000)) { current ->
            if (current != null) {
                val zoom = remember { Animatable(1f) }
                LaunchedEffect(current.first) {
                    // A single picture zooms in and out forever; a slideshow zooms in or out per picture.
                    var inward = current.first % 2 == 0
                    do {
                        zoom.animateTo(if (inward) 1.12f else 1f, tween(if (show.pictures.size == 1) 40_000 else 16_000, easing = LinearEasing))
                        inward = !inward
                    } while (show.pictures.size == 1)
                }
                Image(current.second, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                    contentScale = ContentScale.Crop)
            }
        }
        var clock by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                clock = withContext(Dispatchers.IO) {
                    WidgetPainter.paint(WidgetKind.Clock, WidgetData(accent = show.accent), 1.0, background = false)?.let { p ->
                        org.jetbrains.skia.Image.makeRaster(ImageInfo(p.width, p.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), p.pixels, p.width * 4)
                            .use { it.toComposeImageBitmap() }
                    }
                }
                delay(60_000 - System.currentTimeMillis() % 60_000 + 50) // just after the minute changes
            }
        }
        clock?.let { Image(it, null, Modifier.align(Alignment.BottomStart).padding(48.dp)) }
    }
}
