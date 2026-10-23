package wpm.win32

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Point
import java.awt.Robot
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleBarTest {
    /** Colors a real window's title bar and reads the color back. Opt-in: WPM_DESKTOP_TEST=1. */
    @Test
    fun colorsARealTitleBar() {
        assumeTrue(System.getenv("WPM_DESKTOP_TEST") == "1", "desktop test not requested")
        val frame = JFrame("Title bar test")
        try {
            assertFalse(colorTitleBar(frame, 0xFF232847.toInt(), 0xFFF3EFE8.toInt(), dark = true), "no native window yet")
            SwingUtilities.invokeAndWait { frame.setSize(400, 200); frame.isVisible = true }
            assertTrue(colorTitleBar(frame, 0xFF232847.toInt(), 0xFFF3EFE8.toInt(), dark = true))
            // Windows can't read the caption color back, so look at the screen: a pixel in the title bar's middle.
            SwingUtilities.invokeAndWait { frame.toFront() }
            val robot = Robot()
            val at = frame.locationOnScreen.let { Point(it.x + frame.width / 2, it.y + frame.insets.top / 2) }
            val deadline = System.currentTimeMillis() + 3_000
            var pixel = robot.getPixelColor(at.x, at.y)
            while (pixel.rgb and 0xFFFFFF != 0x232847 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                pixel = robot.getPixelColor(at.x, at.y)
            }
            assertEquals(0x232847, pixel.rgb and 0xFFFFFF, "title bar pixel")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
