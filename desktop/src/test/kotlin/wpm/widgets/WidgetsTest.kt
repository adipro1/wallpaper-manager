package wpm.widgets

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import wpm.AppPaths
import wpm.data.Database
import wpm.data.Settings
import wpm.media.Imaging
import wpm.services.Track
import wpm.services.weatherFromCode
import wpm.win32.Overlay
import wpm.win32.OverlayPicture
import wpm.win32.ScreenRect
import wpm.win32.SystemUsage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeOverlay : Overlay {
    var started = false
    var alive = true
    var closed = false
    var shown: Triple<OverlayPicture, Int, Int>? = null
    var draws = 0
    override fun start() { started = true }
    override fun isAlive() = alive && !closed
    override fun raise(ours: Collection<Overlay>) {}
    override fun show(picture: OverlayPicture, x: Int, y: Int) { shown = Triple(picture, x, y); draws++ }
    override fun hide() { shown = null }
    override fun close() { closed = true }
}

class WidgetsTest {
    private val dir: Path = Files.createTempDirectory("wpm-widgets")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val sample = WidgetData(
        now = LocalDateTime.of(2026, 10, 23, 14, 5),
        weather = weatherFromCode(63, 100.0).copy(temperature = 11.4, code = 63),
        place = "Berlin",
        track = Track("Midnight City", "M83", playing = true),
        system = SystemUsage(23.0, 9.4, 16.0, 7.0),
        locale = Locale.UK,
    )

    @Test
    fun turningWidgetsOnAndMovingThem() {
        val setup = WidgetSetup().with(WidgetKind.Clock, true).with(WidgetKind.Calendar, true).with(WidgetKind.Clock, true)
        assertEquals(listOf(WidgetKind.Clock, WidgetKind.Calendar), setup.widgets.map { it.kind })
        val moved = setup.moved(WidgetKind.Clock, 0.5, 1.7)
        assertEquals(PlacedWidget(WidgetKind.Clock, 0.5, 1.0), moved.widgets[0])
        assertTrue(!moved.with(WidgetKind.Clock, false).shows(WidgetKind.Clock))
        // Kept fully on the display, which may not start at 0, 0.
        val area = ScreenRect(-1920, 0, 1920, 1080)
        assertEquals(-1920 + 960 to 1080 - 100, DesktopWidgets.position(PlacedWidget(WidgetKind.Clock, 0.5, 1.0), area, 300, 100))
        assertEquals(-300 to 0, DesktopWidgets.position(PlacedWidget(WidgetKind.Clock, 0.99, 0.0), area, 300, 100))
    }

    @Test
    fun weatherAndTemperatureTexts() {
        assertEquals("Rain", weatherText(63))
        assertEquals("11°C", temperatureText(11.4, Locale.UK))
        assertEquals("53°F", temperatureText(11.4, Locale.US))
    }

    @Test
    fun paintsEveryWidget() {
        val out = Path.of("build/widget-previews").toAbsolutePath().also { Files.createDirectories(it) }
        for (background in listOf(true, false)) for (kind in WidgetKind.entries) {
            val picture = assertNotNull(WidgetPainter.paint(kind, sample, 1.0, background), "$kind")
            assertTrue(picture.width in 100..900 && picture.height in 50..500, "$kind is ${picture.width}x${picture.height}")
            assertTrue(picture.pixels.any { it != 0.toByte() }, "$kind draws something")
            Image.makeRaster(ImageInfo(picture.width, picture.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), picture.pixels, picture.width * 4)
                .use { Imaging.savePng(it, out.resolve("$kind-${if (background) "card" else "plain"}.png")) }
        }
        // Bigger on bigger displays.
        val small = WidgetPainter.paint(WidgetKind.Clock, sample, 1.0, true)!!
        val big = WidgetPainter.paint(WidgetKind.Clock, sample, 2.0, true)!!
        assertTrue(big.width > small.width * 1.8)
        // Nothing to show: no music, no weather yet.
        assertNull(WidgetPainter.paint(WidgetKind.Music, sample.copy(track = null), 1.0, true))
        assertNull(WidgetPainter.paint(WidgetKind.Weather, sample.copy(weather = null), 1.0, true))
    }

    @Test
    fun widgetsAreDrawnOnlyWhenTheyChange() {
        val overlays = mutableMapOf<WidgetKind, FakeOverlay>()
        var data = sample
        val area = ScreenRect(0, 0, 1920, 1080)
        val widgets = DesktopWidgets(Settings(db), { data }, { area }, { kind -> FakeOverlay().also { overlays[kind] = it } })
        widgets.update()
        assertTrue(overlays.isEmpty(), "no widgets: no windows")

        widgets.change { it.with(WidgetKind.Clock, true).with(WidgetKind.Music, true) }
        widgets.update()
        widgets.update()
        val clock = overlays.getValue(WidgetKind.Clock)
        assertTrue(clock.started && clock.draws == 1)
        assertEquals(77 to 65, clock.shown!!.second to clock.shown!!.third)

        data = data.copy(now = data.now.plusMinutes(1), track = null)
        widgets.update()
        assertEquals(2, clock.draws)
        assertNull(overlays.getValue(WidgetKind.Music).shown, "hidden when nothing plays")

        // Saved, and read back by the next run.
        assertEquals(widgets.setup, DesktopWidgets(Settings(db), { data }).setup)

        // Explorer restarted: a new window.
        clock.alive = false
        widgets.update()
        assertTrue(clock.closed && overlays.getValue(WidgetKind.Clock) !== clock && overlays.getValue(WidgetKind.Clock).draws == 1)

        widgets.change { it.with(WidgetKind.Music, false) }
        widgets.update()
        assertTrue(overlays.getValue(WidgetKind.Music).closed)
        widgets.close()
        assertTrue(overlays.getValue(WidgetKind.Clock).closed)
    }

    /** Real widgets behind the icons for a few seconds, with a screenshot. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun widgetsOnTheRealDesktop() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val widgets = DesktopWidgets(Settings(db), { sample.copy(now = LocalDateTime.now()) })
        var liveHost: wpm.win32.DesktopHost? = null
        try {
            widgets.change { setup -> WidgetKind.entries.fold(setup) { s, kind -> s.with(kind, true) } }
            widgets.update()
            // A live wallpaper window started afterwards (black here) goes on top; the next update moves the widgets back up.
            val live = wpm.win32.DesktopHost(wpm.win32.Monitors.primary().bounds)
            live.start()
            liveHost = live
            widgets.update()
            Thread.sleep(1_500)
            // Only the wallpaper layer (with the widgets), not the windows on top of it.
            val layer = wpm.win32.findWallpaperLayer().let { it.workerW ?: it.parent }
            val screen = assertNotNull(wpm.win32.WindowCapture.capture(layer))
            javax.imageio.ImageIO.write(screen, "png", Path.of("build/widget-previews/desktop.png").toAbsolutePath().also { Files.createDirectories(it.parent) }.toFile())
            // The live window is black, so white text can only come from widgets above it.
            val clock = (0 until 400).flatMap { x -> (0 until 300).map { y -> screen.getRGB(x, y) and 0xFF } }
            assertTrue(clock.count { it > 200 } > 500, "the clock is above the live wallpaper")
        } finally {
            widgets.close()
            liveHost?.close()
        }
    }
}
