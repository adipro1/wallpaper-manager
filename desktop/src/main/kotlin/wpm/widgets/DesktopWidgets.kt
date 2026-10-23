package wpm.widgets

import wpm.data.Settings
import wpm.logger
import wpm.win32.DesktopOverlay
import wpm.win32.Monitors
import wpm.win32.Overlay
import wpm.win32.ScreenRect
import kotlin.math.roundToInt

/**
 * The widgets on the main display: each one is a [DesktopOverlay] that is drawn again
 * whenever what it shows changes. [update] runs every second.
 */
class DesktopWidgets(
    private val settings: Settings,
    /** What the widgets show now; [WidgetSetup] says which parts are needed. */
    private val data: (WidgetSetup) -> WidgetData,
    private val display: () -> ScreenRect = { Monitors.primary().bounds },
    private val newOverlay: (WidgetKind) -> Overlay = { DesktopOverlay(it.name.lowercase()) },
) {
    private val log = logger("widgets")
    @Volatile var setup: WidgetSetup = settings.obj(KEY, WidgetSetup.serializer()) ?: WidgetSetup()
        private set
    private val overlays = mutableMapOf<WidgetKind, Overlay>()
    /** What each overlay shows, so unchanged widgets aren't drawn again. */
    private val drawn = mutableMapOf<WidgetKind, String>()
    private var failedAt = 0L

    /** Changes which widgets show, where and how; they follow within a second. */
    @Synchronized
    fun change(transform: (WidgetSetup) -> WidgetSetup) {
        setup = transform(setup)
        settings.set(KEY, WidgetSetup.serializer(), setup)
        failedAt = 0L
    }

    /** Widget pictures are this much bigger than at 1080p. */
    fun scale(area: ScreenRect = display()): Double = area.height / 1080.0 * setup.size

    @Synchronized
    fun update() {
        val current = setup
        val wanted = current.widgets.map { it.kind }.toSet()
        (overlays.keys - wanted).forEach { kind ->
            overlays.remove(kind)?.close()
            drawn.remove(kind)
        }
        if (wanted.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - failedAt < 60_000) return // the desktop wasn't there (e.g. Explorer restarting); try again later
        val area = display()
        val info = data(current)
        val scale = scale(area)
        for (widget in current.widgets) {
            var overlay = overlays[widget.kind]
            if (overlay != null && !overlay.isAlive()) {
                overlay.close()
                overlay = null
                drawn.remove(widget.kind)
            }
            if (overlay == null) {
                overlay = try {
                    newOverlay(widget.kind).also { it.start() }
                } catch (e: Exception) {
                    log.warning("Couldn't put the ${widget.kind} widget on the desktop: $e")
                    failedAt = now
                    return
                }
                overlays[widget.kind] = overlay
            }
            val key = "${WidgetPainter.key(widget.kind, info)}|$scale|${current.background}|${widget.x},${widget.y}|$area"
            if (drawn[widget.kind] == key) continue
            val picture = WidgetPainter.paint(widget.kind, info, scale, current.background)
            if (picture == null) {
                overlay.hide()
            } else {
                val (x, y) = position(widget, area, picture.width, picture.height)
                overlay.show(picture, x, y)
            }
            drawn[widget.kind] = key
        }
        overlays.values.forEach { it.raise(overlays.values) }
    }

    @Synchronized
    fun close() {
        overlays.values.forEach { runCatching { it.close() } }
        overlays.clear()
        drawn.clear()
    }

    companion object {
        private const val KEY = "widgets"

        /** Where a widget's picture goes on screen: its spot, kept fully on the display. */
        fun position(widget: PlacedWidget, area: ScreenRect, width: Int, height: Int): Pair<Int, Int> =
            area.x + (widget.x * area.width).roundToInt().coerceIn(0, maxOf(0, area.width - width)) to
                area.y + (widget.y * area.height).roundToInt().coerceIn(0, maxOf(0, area.height - height))
    }
}
