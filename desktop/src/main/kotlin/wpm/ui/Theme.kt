package wpm.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

/** The Windows 11 style palette from version 1.x (same light/dark pairs). */
@Immutable
data class AppColors(
    val windowBg: Color,
    val sidebarBg: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val text: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val accent: Color,
    val accentText: Color,
    val navHover: Color,
    val navSelected: Color,
    val danger: Color,
    val star: Color = Color(0xFFF5B400),
    val previewBg: Color = Color(0xFF111111),
)

val LightColors = AppColors(
    windowBg = Color(0xFFF3F3F3), sidebarBg = Color(0xFFEAEAEA), cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE3E3E3), text = Color(0xFF1B1B1B), textMuted = Color(0xFF616161),
    textSubtle = Color(0xFF8A8A8A), accent = Color(0xFF005FB8), accentText = Color(0xFFFFFFFF),
    navHover = Color(0xFFE0E0E0), navSelected = Color(0xFFD9D9D9), danger = Color(0xFFC42B1C),
)

val DarkColors = AppColors(
    windowBg = Color(0xFF202020), sidebarBg = Color(0xFF1A1A1A), cardBg = Color(0xFF2B2B2B),
    cardBorder = Color(0xFF363636), text = Color(0xFFF5F5F5), textMuted = Color(0xFFA3A3A3),
    textSubtle = Color(0xFF7A7A7A), accent = Color(0xFF4CC2FF), accentText = Color(0xFF002A40),
    navHover = Color(0xFF262626), navSelected = Color(0xFF2D2D2D), danger = Color(0xFFFF6B5E),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

private fun AppColors.toMaterial(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = accentText, background = windowBg, onBackground = text,
        surface = cardBg, onSurface = text, onSurfaceVariant = textMuted, outline = cardBorder,
        surfaceVariant = cardBg, surfaceContainer = cardBg, surfaceContainerHigh = cardBg,
        error = danger,
    )
}

private val segoe = FontFamily.Default

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = segoe, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleMedium = TextStyle(fontFamily = segoe, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = segoe, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = segoe, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = segoe, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(dark), typography = AppTypography, content = content)
    }
}

object Theme {
    val colors: AppColors @Composable get() = LocalAppColors.current
}
