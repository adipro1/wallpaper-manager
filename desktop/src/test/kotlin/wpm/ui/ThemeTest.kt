package wpm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import wpm.win32.colorRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun accentSettingRoundTrips() {
        assertEquals(0x1F9AA3, parseAccent("#1F9AA3"))
        assertEquals(0x1F9AA3, parseAccent("1f9aa3"))
        assertNull(parseAccent(""))
        assertNull(parseAccent("#12345"))
        assertNull(parseAccent("#GGGGGG"))
        assertEquals("#1F9AA3", formatAccent(0x1F9AA3))
        assertEquals("", formatAccent(null))
    }

    @Test
    fun builtInAccentIsUnchanged() {
        assertSame(LightColors, LightColors.withAccent(null))
        assertSame(DarkColors, DarkColors.withAccent(null))
    }

    @Test
    fun everyAccentHasReadableText() {
        for (preset in AccentPresets) for (base in listOf(LightColors, DarkColors)) {
            val colors = base.withAccent(preset.rgb)
            assertTrue(contrast(colors.accent, colors.accentText) >= 2.5, "${preset.name} (dark=${base.dark})")
        }
        // A very light custom color gets dark text.
        assertNotEquals(Color.White, LightColors.withAccent(0xFFF59D).accentText)
        assertEquals(Color.White, LightColors.withAccent(0x1A237E).accentText)
    }

    @Test
    fun darkThemeUsesALighterShade() {
        val light = LightColors.withAccent(0x2F7FD6).accent
        val dark = DarkColors.withAccent(0x2F7FD6).accent
        assertEquals(Color(0xFF2F7FD6), light)
        assertTrue(dark.luminance() > light.luminance())
    }

    @Test
    fun colorRefSwapsRedAndBlue() {
        assertEquals(0x00CCBBAA, colorRef(0xFFAABBCC.toInt()))
        assertEquals(0x000000FF, colorRef(0xFFFF0000.toInt()))
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
