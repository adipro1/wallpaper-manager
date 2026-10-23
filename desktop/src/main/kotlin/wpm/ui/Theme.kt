@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package wpm.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Origami Fold" palette: washi paper surfaces, indigo ink and a vermilion accent.
 * Every surface has a lit side and a shaded side so folds read as depth.
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val windowBg: Color,
    /** The facets drawn into the page background. */
    val facet: Color,
    val sidebarBg: Color,
    val sidebarBgShade: Color,
    val sidebarText: Color,
    val sidebarTextMuted: Color,
    val cardBg: Color,
    /** The lit side of a card's gradient. */
    val cardLit: Color,
    val cardBorder: Color,
    /** The underside of a folded corner. */
    val flap: Color,
    val inset: Color,
    val text: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentText: Color,
    val accentSoft: Color,
    /** The accent's warmer neighbor, for the logo's second facet (gold next to the built-in vermilion). */
    val accentPair: Color,
    val indigo: Color,
    val gold: Color,
    val teal: Color,
    val navHover: Color,
    val navSelected: Color,
    val danger: Color,
    val shadow: Color,
    val star: Color = Color(0xFFF2B33D),
    val previewBg: Color = Color(0xFF0F1118),
)

val LightColors = AppColors(
    dark = false,
    windowBg = Color(0xFFF4EFE7), facet = Color(0xFFE9E1D4),
    sidebarBg = Color(0xFF232847), sidebarBgShade = Color(0xFF1A1E38),
    sidebarText = Color(0xFFF3EFE8), sidebarTextMuted = Color(0xFF9EA3C2),
    cardBg = Color(0xFFFFFCF7), cardLit = Color(0xFFFFFFFF), cardBorder = Color(0xFFE6DDCF),
    flap = Color(0xFFE8DFD0), inset = Color(0xFFEDE6DA),
    text = Color(0xFF1D1B22), textMuted = Color(0xFF6A6470), textSubtle = Color(0xFF9B95A0),
    accent = Color(0xFFE0533D), accentDeep = Color(0xFFB83A2A), accentText = Color(0xFFFFFFFF), accentSoft = Color(0xFFFBE3DD), accentPair = Color(0xFFC9932E),
    indigo = Color(0xFF3F4A8C), gold = Color(0xFFC9932E), teal = Color(0xFF2E8C83),
    navHover = Color(0x14FFFFFF), navSelected = Color(0xFFE9E1D4), danger = Color(0xFFC0392B),
    shadow = Color(0xFF5A4630),
)

val DarkColors = AppColors(
    dark = true,
    windowBg = Color(0xFF161925), facet = Color(0xFF1B1F2E),
    sidebarBg = Color(0xFF10121C), sidebarBgShade = Color(0xFF0B0D15),
    sidebarText = Color(0xFFEDEAF2), sidebarTextMuted = Color(0xFF7F849E),
    cardBg = Color(0xFF1F2333), cardLit = Color(0xFF262B3E), cardBorder = Color(0xFF2E3348),
    flap = Color(0xFF353B55), inset = Color(0xFF181B28),
    text = Color(0xFFF1EEF6), textMuted = Color(0xFFA19FB4), textSubtle = Color(0xFF6E6C82),
    accent = Color(0xFFFF6B52), accentDeep = Color(0xFFD9472F), accentText = Color(0xFFFFFFFF), accentSoft = Color(0xFF3A2430), accentPair = Color(0xFFE8B550),
    indigo = Color(0xFF8C9BFF), gold = Color(0xFFE8B550), teal = Color(0xFF4FC1B5),
    navHover = Color(0x12FFFFFF), navSelected = Color(0xFF2A2F44), danger = Color(0xFFFF6B5E),
    shadow = Color(0xFF000000),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

/** An accent color offered in Settings. [rgb] is null for the built-in vermilion. */
data class AccentPreset(val name: String, val rgb: Int?)

val AccentPresets = listOf(
    AccentPreset("Vermilion", null),
    AccentPreset("Amber", 0xE8862A),
    AccentPreset("Gold", 0xD4A21C),
    AccentPreset("Jade", 0x2E9E6B),
    AccentPreset("Teal", 0x1F9AA3),
    AccentPreset("Ocean", 0x2F7FD6),
    AccentPreset("Indigo", 0x5A5FD8),
    AccentPreset("Violet", 0x8E4FD1),
    AccentPreset("Rose", 0xD6457A),
)

/** "#RRGGBB" -> 0xRRGGBB; null for an empty or invalid value (the built-in color). */
fun parseAccent(hex: String): Int? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

fun formatAccent(rgb: Int?): String = rgb?.let { "#%06X".format(it and 0xFFFFFF) } ?: ""

/** White text, unless it would be hard to read on [background] (contrast below 3:1); then ink. */
fun readableOn(background: Color): Color =
    if (1.05f / (background.luminance() + 0.05f) >= 3f) Color.White else Color(0xFF1D1B22)

/** These colors with the accent replaced by [rgb] (unchanged for null). Dark themes get a lighter shade. */
fun AppColors.withAccent(rgb: Int?): AppColors {
    if (rgb == null) return this
    val base = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))
    val accent = if (dark) lerp(base, Color.White, 0.15f) else base
    return copy(
        accent = accent,
        accentDeep = lerp(accent, Color.Black, 0.2f),
        accentText = readableOn(accent),
        accentSoft = lerp(cardBg, accent, if (dark) 0.22f else 0.16f),
        accentPair = shiftHue(accent, 31f, 0.9f),
    )
}

/** [color] turned [degrees] around the color wheel, its brightness scaled by [brightness]. */
internal fun shiftHue(color: Color, degrees: Float, brightness: Float): Color {
    val hsb = java.awt.Color.RGBtoHSB((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(), null)
    val rgb = java.awt.Color.HSBtoRGB((hsb[0] + degrees / 360f) % 1f, hsb[1], (hsb[2] * brightness).coerceIn(0f, 1f))
    return Color(rgb or (0xFF shl 24))
}

private fun AppColors.toMaterial(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = accentText, primaryContainer = accentSoft, onPrimaryContainer = text,
        secondary = indigo, secondaryContainer = accentSoft, onSecondaryContainer = text,
        background = windowBg, onBackground = text,
        surface = cardBg, onSurface = text, onSurfaceVariant = textMuted, outline = cardBorder, outlineVariant = cardBorder,
        surfaceVariant = inset, surfaceContainer = cardBg, surfaceContainerHigh = cardBg, surfaceContainerHighest = inset,
        surfaceContainerLow = cardBg, surfaceContainerLowest = cardBg, surfaceTint = Color.Transparent,
        error = danger,
    )
}

/** Bahnschrift's geometric, slightly condensed letters suit the folded shapes; falls back to the default font. */
val DisplayFont = FontFamily(
    SystemFont("Bahnschrift", FontWeight.Normal),
    SystemFont("Bahnschrift", FontWeight.SemiBold),
    SystemFont("Bahnschrift", FontWeight.Bold),
)
val BodyFont = FontFamily(
    SystemFont("Segoe UI Variable Text", FontWeight.Normal),
    SystemFont("Segoe UI Variable Text", FontWeight.Medium),
    SystemFont("Segoe UI Variable Text", FontWeight.SemiBold),
    SystemFont("Segoe UI Variable Text", FontWeight.Bold),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.2.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.6.sp),
)

/** Folded (cut) corners for the Material parts that are still used: text fields, menus, dialogs. */
private val AppShapes = Shapes(
    extraSmall = CutCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 8.dp),
    small = CutCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 8.dp),
    medium = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp),
    large = CutCornerShape(topStart = 0.dp, topEnd = 18.dp, bottomEnd = 0.dp, bottomStart = 18.dp),
    extraLarge = CutCornerShape(topStart = 0.dp, topEnd = 22.dp, bottomEnd = 0.dp, bottomStart = 22.dp),
)

@Composable
fun AppTheme(dark: Boolean, accent: Int? = null, content: @Composable () -> Unit) {
    val colors = remember(dark, accent) { (if (dark) DarkColors else LightColors).withAccent(accent) }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Gives [window]'s Windows title bar the sidebar's colors instead of plain white (Windows 11; dark mode only on 10). */
@Composable
fun TitleBarColors(window: java.awt.Window) {
    val c = Theme.colors
    LaunchedEffect(window, c) {
        // The native window may not exist yet on the first frame.
        repeat(20) {
            if (wpm.win32.colorTitleBar(window, c.sidebarBg.toArgb(), c.sidebarText.toArgb(), dark = c.sidebarBg.luminance() < 0.5f)) return@LaunchedEffect
            delay(100)
        }
    }
}

object Theme {
    val colors: AppColors @Composable get() = LocalAppColors.current
}
