package wpm.widgets

import kotlinx.serialization.Serializable
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import wpm.media.Imaging
import wpm.services.Track
import wpm.services.Weather
import wpm.tr
import wpm.win32.OverlayPicture
import wpm.win32.SystemUsage
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The widgets that can sit on the desktop, behind the icons. */
enum class WidgetKind { Clock, Weather, Music, System, Calendar }

val WidgetKind.label: String
    get() = when (this) {
        WidgetKind.Clock -> tr("Clock")
        WidgetKind.Weather -> tr("Weather")
        WidgetKind.Music -> tr("Now playing")
        WidgetKind.System -> tr("System")
        WidgetKind.Calendar -> tr("Calendar")
    }

/** A widget and where it is: its top-left corner as a fraction of the display (0..1). */
@Serializable
data class PlacedWidget(val kind: WidgetKind, val x: Double, val y: Double)

/** Which widgets are on, where, how big ([size] 1 = normal) and whether they sit on a dark card. */
@Serializable
data class WidgetSetup(
    val widgets: List<PlacedWidget> = emptyList(),
    val size: Double = 1.0,
    val background: Boolean = true,
) {
    fun shows(kind: WidgetKind): Boolean = widgets.any { it.kind == kind }

    /** Turns a widget on (at its usual spot) or off. */
    fun with(kind: WidgetKind, on: Boolean): WidgetSetup = when {
        on && !shows(kind) -> copy(widgets = widgets + DEFAULT_SPOTS.getValue(kind))
        !on -> copy(widgets = widgets.filter { it.kind != kind })
        else -> this
    }

    fun moved(kind: WidgetKind, x: Double, y: Double): WidgetSetup =
        copy(widgets = widgets.map { if (it.kind == kind) it.copy(x = x.coerceIn(0.0, 1.0), y = y.coerceIn(0.0, 1.0)) else it })

    companion object {
        val DEFAULT_SPOTS = mapOf(
            WidgetKind.Clock to PlacedWidget(WidgetKind.Clock, 0.04, 0.06),
            WidgetKind.Weather to PlacedWidget(WidgetKind.Weather, 0.04, 0.27),
            WidgetKind.Music to PlacedWidget(WidgetKind.Music, 0.04, 0.80),
            WidgetKind.System to PlacedWidget(WidgetKind.System, 0.80, 0.06),
            WidgetKind.Calendar to PlacedWidget(WidgetKind.Calendar, 0.80, 0.30),
        )
    }
}

/** What the widgets show right now. Null parts are unknown (their widget shows nothing or a dash). */
data class WidgetData(
    val now: LocalDateTime = LocalDateTime.now(),
    val weather: Weather? = null,
    val place: String? = null,
    val track: Track? = null,
    val system: SystemUsage? = null,
    /** 0xRRGGBB, e.g. today's circle in the calendar. */
    val accent: Int = 0xE4572E,
    val locale: Locale = Locale.getDefault(),
)

/** A short description of a WMO weather code, as Open-Meteo reports it. */
fun weatherText(code: Int): String = when (code) {
    0 -> tr("Clear")
    1 -> tr("Mostly clear")
    2 -> tr("Partly cloudy")
    3 -> tr("Cloudy")
    45, 48 -> tr("Fog")
    51, 53, 55, 56, 57 -> tr("Drizzle")
    61, 63, 65, 66, 67 -> tr("Rain")
    71, 73, 75, 77 -> tr("Snow")
    80, 81, 82 -> tr("Showers")
    85, 86 -> tr("Snow showers")
    95, 96, 99 -> tr("Thunderstorm")
    else -> ""
}

/** Fahrenheit where people use it (the US and a few others), Celsius elsewhere. */
fun temperatureText(celsius: Double, locale: Locale): String =
    if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "${(celsius * 9 / 5 + 32).roundToInt()}°F"
    else "${celsius.roundToInt()}°C"

/**
 * Draws widgets with Skia into see-through pictures for [wpm.win32.DesktopOverlay] (and the
 * editor's preview). White text with a soft shadow, optionally on a dark rounded card.
 */
object WidgetPainter {
    private val fonts = FontMgr.default
    private fun typeface(weight: Int): Typeface? =
        listOf("Segoe UI Variable Display", "Segoe UI", "Arial").firstNotNullOfOrNull { fonts.matchFamilyStyle(it, FontStyle(weight, 5, org.jetbrains.skia.FontSlant.UPRIGHT)) }
    private val light by lazy { typeface(300) }
    private val regular by lazy { typeface(400) }
    private val semibold by lazy { typeface(600) }

    /** What [kind] shows, as text: the picture is drawn again only when this changes. */
    fun key(kind: WidgetKind, data: WidgetData): String = when (kind) {
        WidgetKind.Clock -> time(data) + "|" + date(data)
        WidgetKind.Weather -> "${data.weather?.temperature?.roundToInt()}|${data.weather?.code}|${data.place}"
        WidgetKind.Music -> data.track?.let { "${it.title}|${it.artist}|${it.art}|${it.playing}" } ?: ""
        WidgetKind.System -> data.system?.let { "${it.cpuPercent.roundToInt()}|${(it.memoryUsedGb * 10).roundToInt()}|${it.gpuPercent?.roundToInt()}" } ?: ""
        WidgetKind.Calendar -> data.now.toLocalDate().toString() + "|" + data.accent
    } + "|" + data.locale

    /**
     * The widget's picture; null when it has nothing to show (no music playing). [scale] 1 suits
     * a 1080p display.
     */
    fun paint(kind: WidgetKind, data: WidgetData, scale: Double, background: Boolean): OverlayPicture? {
        val s = scale.toFloat()
        val content: Content = when (kind) {
            WidgetKind.Clock -> clock(data, s)
            WidgetKind.Weather -> weather(data, s) ?: return null
            WidgetKind.Music -> music(data, s) ?: return null
            WidgetKind.System -> system(data, s) ?: return null
            WidgetKind.Calendar -> calendar(data, s)
        }
        val margin = 12 * s // room for the shadow
        val pad = 20 * s
        val width = (content.width + 2 * pad + 2 * margin).roundToInt()
        val height = (content.height + 2 * pad + 2 * margin).roundToInt()
        Surface.makeRasterN32Premul(width, height).use { surface ->
            val canvas = surface.canvas
            if (background) {
                val card = RRect.makeXYWH(margin, margin, width - 2 * margin, height - 2 * margin, 18 * s)
                canvas.drawRRect(card, Paint().apply {
                    color = 0x66000000
                    isAntiAlias = true
                    imageFilter = ImageFilter.makeDropShadow(0f, 2 * s, 8 * s, 8 * s, 0x33000000)
                })
                canvas.drawRRect(card, Paint().apply {
                    color = 0x26FFFFFF
                    mode = PaintMode.STROKE
                    strokeWidth = maxOf(1f, s)
                    isAntiAlias = true
                })
            }
            canvas.save()
            canvas.translate(margin + pad, margin + pad)
            content.draw(Painter(canvas, s, shadow = !background))
            canvas.restore()
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    val pixels = bitmap.readPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), width * 4, 0, 0)
                        ?: return null
                    return OverlayPicture(width, height, pixels)
                }
            }
        }
    }

    private class Content(val width: Float, val height: Float, val draw: Painter.() -> Unit)

    /** Drawing helpers: white text (with a shadow when there is no card). */
    private class Painter(val canvas: Canvas, val s: Float, val shadow: Boolean) {
        fun paint(color: Int = WHITE) = Paint().apply {
            this.color = color
            isAntiAlias = true
            if (shadow) imageFilter = ImageFilter.makeDropShadow(0f, 1.5f * s, 3 * s, 3 * s, 0xAA000000.toInt())
        }

        /** Draws [text] with its top at [top]. */
        fun text(text: String, x: Float, top: Float, font: Font, color: Int = WHITE) =
            canvas.drawString(text, x, top - font.metrics.ascent, font, paint(color))
    }

    private fun font(typeface: Typeface?, size: Float) = Font(typeface, size)
    private fun lineHeight(font: Font) = font.metrics.descent - font.metrics.ascent

    private fun time(data: WidgetData) = data.now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(data.locale))
    private fun date(data: WidgetData) =
        data.now.format(DateTimeFormatter.ofPattern(if (data.locale.country == "US") "EEEE, MMMM d" else "EEEE, d MMMM", data.locale))

    private fun clock(data: WidgetData, s: Float): Content {
        val big = font(light, 88 * s)
        val small = font(regular, 22 * s)
        val time = time(data)
        val date = date(data)
        val width = maxOf(big.measureTextWidth(time), small.measureTextWidth(date))
        val bigHeight = lineHeight(big) * 0.86f // the digits, without most of the space for descenders
        return Content(width, bigHeight + 4 * s + lineHeight(small)) {
            text(time, 0f, -lineHeight(big) * 0.07f, big)
            text(date, 2 * s, bigHeight + 4 * s, small, MUTED)
        }
    }

    private fun weather(data: WidgetData, s: Float): Content? {
        val w = data.weather ?: return null
        val celsius = w.temperature ?: return null
        val big = font(light, 56 * s)
        val small = font(regular, 18 * s)
        val temperature = temperatureText(celsius, data.locale)
        val line = listOfNotNull(w.code?.let(::weatherText)?.takeIf { it.isNotEmpty() }, data.place).joinToString(" · ")
        val icon = 64 * s
        val textWidth = maxOf(big.measureTextWidth(temperature), small.measureTextWidth(line))
        val height = maxOf(icon, lineHeight(big) + lineHeight(small))
        return Content(icon + 16 * s + textWidth, height) {
            weatherIcon(w, 0f, (height - icon) / 2, icon)
            val top = (height - lineHeight(big) - lineHeight(small)) / 2
            text(temperature, icon + 16 * s, top, big)
            text(line, icon + 18 * s, top + lineHeight(big), small, MUTED)
        }
    }

    /** A simple drawn icon: sun, cloud, rain, snow, fog or lightning. */
    private fun Painter.weatherIcon(w: Weather, x: Float, y: Float, size: Float) {
        val u = size / 64
        val line = paint().apply { mode = PaintMode.STROKE; strokeWidth = 3.5f * u; strokeCap = PaintStrokeCap.ROUND }
        val fill = paint()
        val code = w.code ?: 0
        val sunny = code <= 2
        val cloudy = code >= 2
        if (sunny) {
            val cx = x + (if (cloudy) 24 else 32) * u
            val cy = y + (if (cloudy) 24 else 32) * u
            val r = (if (cloudy) 10 else 12) * u
            canvas.drawCircle(cx, cy, r, paint(SUN))
            for (i in 0 until 8) {
                val a = i * PI / 4
                canvas.drawLine(cx + cos(a).toFloat() * (r + 5 * u), cy + sin(a).toFloat() * (r + 5 * u),
                    cx + cos(a).toFloat() * (r + 10 * u), cy + sin(a).toFloat() * (r + 10 * u),
                    paint(SUN).apply { mode = PaintMode.STROKE; strokeWidth = 3 * u; strokeCap = PaintStrokeCap.ROUND })
            }
        }
        if (cloudy) {
            val top = if (w.rain > 0 || w.snow > 0 || w.storm > 0) 10f else 18f
            val cloud = SkPath().apply {
                addCircle(x + 22 * u, y + (top + 20) * u, 11 * u)
                addCircle(x + 36 * u, y + (top + 14) * u, 15 * u)
                addCircle(x + 48 * u, y + (top + 22) * u, 9 * u)
                addRRect(RRect.makeXYWH(x + 11 * u, y + (top + 18) * u, 46 * u, 13 * u, 6.5f * u))
            }
            canvas.drawPath(cloud, fill)
            val below = y + (top + 36) * u
            when {
                w.storm > 0 -> canvas.drawPath(SkPath().apply {
                    moveTo(x + 36 * u, below); lineTo(x + 28 * u, below + 10 * u); lineTo(x + 34 * u, below + 10 * u)
                    lineTo(x + 28 * u, below + 18 * u); lineTo(x + 42 * u, below + 7 * u); lineTo(x + 36 * u, below + 7 * u); closePath()
                }, paint(SUN))
                w.snow > 0 -> for (i in 0 until 3) canvas.drawCircle(x + (22 + i * 10) * u, below + (6 + (i % 2) * 6) * u, 2.6f * u, fill)
                w.rain > 0 -> for (i in 0 until 3) canvas.drawLine(x + (24 + i * 10) * u, below + 3 * u, x + (20 + i * 10) * u, below + 13 * u, line)
                w.fog > 0 -> for (i in 0 until 2) canvas.drawLine(x + (14 + i * 6) * u, below + (5 + i * 7) * u, x + (50 + i * 4) * u, below + (5 + i * 7) * u, line)
            }
        }
    }

    private fun music(data: WidgetData, s: Float): Content? {
        val track = data.track ?: return null
        val art = track.art?.let { runCatching { Imaging.load(Path.of(it)) }.getOrNull() }
        val title = font(semibold, 20 * s)
        val artist = font(regular, 16 * s)
        val side = 72 * s
        val maxText = 320 * s
        val titleText = fit(track.title.ifBlank { track.artist }, title, maxText)
        val artistText = fit(if (track.title.isBlank()) "" else track.artist, artist, maxText)
        val textWidth = maxOf(title.measureTextWidth(titleText), artist.measureTextWidth(artistText))
        val textLeft = if (art != null) side + 16 * s else 0f
        return Content(textLeft + textWidth, maxOf(if (art != null) side else 0f, lineHeight(title) + lineHeight(artist) + 4 * s)) {
            if (art != null) art.use {
                val box = RRect.makeXYWH(0f, 0f, side, side, 10 * s)
                canvas.save()
                canvas.clipRRect(box, true)
                val crop = minOf(it.width, it.height).toFloat()
                canvas.drawImageRect(it, Rect.makeXYWH((it.width - crop) / 2, (it.height - crop) / 2, crop, crop), Rect.makeWH(side, side))
                canvas.restore()
            }
            val top = ((if (art != null) side else 0f) - lineHeight(title) - lineHeight(artist) - 4 * s).coerceAtLeast(0f) / 2
            text(titleText, textLeft, top, title, if (track.playing) WHITE else MUTED)
            text(artistText, textLeft, top + lineHeight(title) + 4 * s, artist, MUTED)
        }
    }

    /** [text] shortened with "…" to fit [width]. */
    private fun fit(text: String, font: Font, width: Float): String {
        if (font.measureTextWidth(text) <= width) return text
        var end = text.length
        while (end > 1 && font.measureTextWidth(text.take(end) + "…") > width) end--
        return text.take(end).trimEnd() + "…"
    }

    private fun system(data: WidgetData, s: Float): Content? {
        val usage = data.system ?: return null
        val label = font(regular, 15 * s)
        val value = font(semibold, 15 * s)
        val rows = listOfNotNull(
            Triple(tr("CPU"), usage.cpuPercent / 100, "${usage.cpuPercent.roundToInt()}%"),
            Triple(tr("Memory"), usage.memoryUsedGb / usage.memoryTotalGb.coerceAtLeast(0.1),
                String.format(data.locale, "%.1f / %.0f GB", usage.memoryUsedGb, usage.memoryTotalGb)),
            usage.gpuPercent?.let { Triple(tr("GPU"), it / 100, "${it.roundToInt()}%") },
        )
        val labelWidth = rows.maxOf { label.measureTextWidth(it.first) } + 14 * s
        val bar = 150 * s
        val valueWidth = rows.maxOf { value.measureTextWidth(it.third) }
        val rowHeight = lineHeight(label) + 10 * s
        return Content(labelWidth + bar + 14 * s + valueWidth, rows.size * rowHeight - 10 * s) {
            rows.forEachIndexed { i, (name, fraction, text) ->
                val top = i * rowHeight
                text(name, 0f, top, label, MUTED)
                val mid = top + lineHeight(label) / 2
                canvas.drawRRect(RRect.makeXYWH(labelWidth, mid - 3 * s, bar, 6 * s, 3 * s), paint(TRACK))
                val filled = (bar * fraction.toFloat().coerceIn(0f, 1f)).coerceAtLeast(6 * s)
                canvas.drawRRect(RRect.makeXYWH(labelWidth, mid - 3 * s, filled, 6 * s, 3 * s), paint(0xFF000000.toInt() or data.accent))
                text(text, labelWidth + bar + 14 * s, top, value)
            }
        }
    }

    private fun calendar(data: WidgetData, s: Float): Content {
        val header = font(semibold, 18 * s)
        val day = font(regular, 14 * s)
        val cell = 34 * s
        val today = data.now.toLocalDate()
        val month = YearMonth.from(today)
        val firstDay = WeekFields.of(data.locale).firstDayOfWeek
        val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        val title = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", data.locale))
            .replaceFirstChar { it.titlecase(data.locale) }
        val headerHeight = lineHeight(header) + 10 * s
        return Content(7 * cell, headerHeight + (weeks + 1) * cell) {
            text(title, 8 * s, 0f, header)
            fun centered(label: String, column: Int, row: Int, font: Font, color: Int) {
                val x = column * cell + (cell - font.measureTextWidth(label)) / 2
                val y = headerHeight + row * cell + (cell - lineHeight(font)) / 2
                text(label, x, y, font, color)
            }
            for (i in 0 until 7) {
                val name = firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, data.locale)
                centered(name, i, 0, day, MUTED)
            }
            for (d in 1..month.lengthOfMonth()) {
                val index = offset + d - 1
                val column = index % 7
                val row = index / 7 + 1
                if (d == today.dayOfMonth) {
                    canvas.drawCircle(column * cell + cell / 2, headerHeight + row * cell + cell / 2, cell * 0.42f,
                        paint(0xFF000000.toInt() or data.accent))
                }
                centered(d.toString(), column, row, day, WHITE)
            }
        }
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val MUTED = 0xCCFFFFFF.toInt()
    private const val TRACK = 0x40FFFFFF
    private const val SUN = 0xFFFFC94D.toInt()
}
