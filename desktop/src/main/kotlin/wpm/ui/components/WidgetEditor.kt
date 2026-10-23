package wpm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import wpm.AppState
import wpm.tr
import wpm.ui.Theme
import wpm.widgets.WidgetKind
import wpm.widgets.WidgetPainter
import wpm.widgets.label
import wpm.win32.Monitors
import kotlin.math.roundToInt

/**
 * Desktop widgets: which ones show, how big, and where (dragged around on a small picture of
 * the main display, over [wallpaper]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetEditor(app: AppState, wallpaper: @Composable BoxScope.() -> Unit) {
    var setup by remember { mutableStateOf(app.widgets.setup) }
    fun change(transform: (wpm.widgets.WidgetSetup) -> wpm.widgets.WidgetSetup) {
        app.widgets.change(transform)
        setup = app.widgets.setup
    }
    val display = remember { Monitors.primary().bounds }
    // The widgets as they look now, at their real size (redrawn when the setup changes).
    val pictures = remember { mutableStateMapOf<WidgetKind, ImageBitmap>() }
    LaunchedEffect(setup, app.shownThemeColor) {
        run {
            val drawn = withContext(Dispatchers.IO) {
                val now = runCatching { app.widgetData(setup) }.getOrNull() ?: return@withContext emptyMap<WidgetKind, ImageBitmap?>()
                // Something to place even when no music plays.
                val data = now.copy(track = now.track ?: wpm.services.Track(tr("Nothing playing"), tr("Songs show here")))
                val scale = app.widgets.scale(display)
                setup.widgets.associate { w ->
                    w.kind to WidgetPainter.paint(w.kind, data, scale, setup.background)?.let { p ->
                        org.jetbrains.skia.Image.makeRaster(ImageInfo(p.width, p.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), p.pixels, p.width * 4)
                            .use { it.toComposeImageBitmap() }
                    }
                }
            }
            pictures.keys.retainAll(drawn.keys)
            drawn.forEach { (kind, picture) -> if (picture != null) pictures[kind] = picture else pictures.remove(kind) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        SectionTitle(tr("Widgets"))
        Spacer(Modifier.height(6.dp))
        MutedText(tr("A clock, the weather and more on your main display, behind the desktop icons. Drag them in the picture to move them."))
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetKind.entries.forEach { kind ->
                val on = setup.shows(kind)
                FilterChip(on, { change { it.with(kind, !on) } }, { Text(kind.label) },
                    leadingIcon = if (on) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null)
            }
        }
        if (setup.widgets.isEmpty()) return@Card
        Spacer(Modifier.height(16.dp))
        PreviewFrame(Modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(display.width.toFloat() / display.height)) {
            wallpaper()
            BoxWithConstraints(Modifier.matchParentSize()) {
                val density = LocalDensity.current
                val frameWidth = with(density) { maxWidth.toPx() }
                val frameHeight = with(density) { maxHeight.toPx() }
                val ratio = frameWidth / display.width // preview pixels per screen pixel
                for (widget in setup.widgets) {
                    val picture = pictures[widget.kind] ?: continue
                    val w = picture.width * ratio
                    val h = picture.height * ratio
                    var dragged by remember(widget) { mutableStateOf<Offset?>(null) }
                    val x = dragged?.x ?: (widget.x.toFloat() * frameWidth).coerceIn(0f, (frameWidth - w).coerceAtLeast(0f))
                    val y = dragged?.y ?: (widget.y.toFloat() * frameHeight).coerceIn(0f, (frameHeight - h).coerceAtLeast(0f))
                    Image(
                        picture, widget.kind.label,
                        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                            .pointerHoverIcon(PointerIcon.Hand)
                            .pointerInput(widget, frameWidth, frameHeight) {
                                detectDragGestures(
                                    onDragStart = { dragged = Offset(x, y) },
                                    onDragEnd = {
                                        dragged?.let { p -> change { it.moved(widget.kind, (p.x / frameWidth).toDouble(), (p.y / frameHeight).toDouble()) } }
                                    },
                                    onDragCancel = { dragged = null },
                                ) { change, delta ->
                                    change.consume()
                                    val p = (dragged ?: Offset(x, y)) + delta
                                    dragged = Offset(p.x.coerceIn(0f, (frameWidth - w).coerceAtLeast(0f)), p.y.coerceIn(0f, (frameHeight - h).coerceAtLeast(0f)))
                                }
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.width(260.dp)) {
                var size by remember { mutableFloatStateOf(setup.size.toFloat()) }
                Row {
                    Text(tr("Size"), color = Theme.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${(size * 100).roundToInt()}%", color = Theme.colors.textMuted, fontSize = 12.sp)
                }
                Slider(size, { size = it }, valueRange = 0.6f..1.6f, steps = 4,
                    onValueChangeFinished = { change { it.copy(size = size.toDouble()) } })
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(setup.background, { on -> change { it.copy(background = on) } })
                Text(tr("Dark card behind each widget"), color = Theme.colors.text)
            }
        }
    }
}
