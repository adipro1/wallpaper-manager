package wpm.services

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import wpm.data.Settings
import wpm.logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val log = logger("accent")

/** Reads and writes the Windows accent color. An interface so tests don't change the real one. */
interface AccentApi {
    /** The current values, so they can be put back later. */
    fun read(): AccentBackup
    fun write(backup: AccentBackup)
    /** Applies a new accent (0xRRGGBB) with its palette of shades. */
    fun apply(rgb: Int) = write(accentValues(rgb))
}

/** The registry values that make up the accent color (null = the value didn't exist). */
@Serializable
data class AccentBackup(
    val accentColor: Int? = null, // DWM AccentColor, 0xAABBGGRR
    val colorizationColor: Int? = null, // DWM ColorizationColor, 0xAARRGGBB
    val colorizationAfterglow: Int? = null,
    val accentColorMenu: Int? = null, // Explorer\Accent, 0xAABBGGRR
    val startColorMenu: Int? = null,
    val accentPalette: String? = null, // 8 colors (RGBA) as hex
    val autoColorization: String? = null, // Control Panel\Desktop: "1" = Windows picks from the picture
)

private fun abgr(rgb: Int) = (0xFF shl 24) or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

/** Lighter and darker shades of [rgb], the way Windows lays out AccentPalette (light to dark). */
fun accentPalette(rgb: Int): List<Int> {
    val (h, s, v) = rgbToHsv(rgb)
    val steps = listOf(0.45, 0.30, 0.15, 0.0, -0.15, -0.30, -0.45, -0.60)
    return steps.map { step ->
        if (step >= 0) hsvToRgb(h, s * (1 - step), v + (1 - v) * step) else hsvToRgb(h, s, v * (1 + step))
    }
}

/** Registry values for an accent color. */
fun accentValues(rgb: Int): AccentBackup {
    val palette = accentPalette(rgb)
    val bytes = palette.flatMap { c -> listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, 0) } // RGB + 0, as Windows writes it
    return AccentBackup(
        accentColor = abgr(rgb),
        colorizationColor = (0xC4 shl 24) or (rgb and 0xFFFFFF),
        colorizationAfterglow = (0xC4 shl 24) or (rgb and 0xFFFFFF),
        accentColorMenu = abgr(rgb),
        startColorMenu = abgr(palette[4]),
        accentPalette = bytes.joinToString("") { "%02x".format(it) },
        autoColorization = "0",
    )
}

class WindowsAccent : AccentApi {
    private val dwm = "Software\\Microsoft\\Windows\\DWM"
    private val accent = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Accent"
    private val desktop = "Control Panel\\Desktop"
    private val hkcu = WinReg.HKEY_CURRENT_USER

    private fun int(key: String, name: String): Int? =
        runCatching { if (Advapi32Util.registryValueExists(hkcu, key, name)) Advapi32Util.registryGetIntValue(hkcu, key, name) else null }.getOrNull()

    override fun read() = AccentBackup(
        accentColor = int(dwm, "AccentColor"),
        colorizationColor = int(dwm, "ColorizationColor"),
        colorizationAfterglow = int(dwm, "ColorizationAfterglow"),
        accentColorMenu = int(accent, "AccentColorMenu"),
        startColorMenu = int(accent, "StartColorMenu"),
        accentPalette = runCatching {
            if (Advapi32Util.registryValueExists(hkcu, accent, "AccentPalette"))
                Advapi32Util.registryGetBinaryValue(hkcu, accent, "AccentPalette").joinToString("") { "%02x".format(it) } else null
        }.getOrNull(),
        autoColorization = runCatching {
            if (Advapi32Util.registryValueExists(hkcu, desktop, "AutoColorization")) Advapi32Util.registryGetStringValue(hkcu, desktop, "AutoColorization") else null
        }.getOrNull(),
    )

    override fun write(backup: AccentBackup) {
        fun setInt(key: String, name: String, value: Int?) {
            if (value != null) Advapi32Util.registrySetIntValue(hkcu, key, name, value)
            else if (Advapi32Util.registryValueExists(hkcu, key, name)) Advapi32Util.registryDeleteValue(hkcu, key, name)
        }
        if (!Advapi32Util.registryKeyExists(hkcu, accent)) Advapi32Util.registryCreateKey(hkcu, accent)
        setInt(dwm, "AccentColor", backup.accentColor)
        setInt(dwm, "ColorizationColor", backup.colorizationColor)
        setInt(dwm, "ColorizationAfterglow", backup.colorizationAfterglow)
        setInt(accent, "AccentColorMenu", backup.accentColorMenu)
        setInt(accent, "StartColorMenu", backup.startColorMenu)
        fun delete(key: String, name: String) {
            if (Advapi32Util.registryValueExists(hkcu, key, name)) Advapi32Util.registryDeleteValue(hkcu, key, name)
        }
        backup.accentPalette?.let { hex ->
            Advapi32Util.registrySetBinaryValue(hkcu, accent, "AccentPalette", hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        } ?: delete(accent, "AccentPalette")
        // Values that didn't exist before are removed again, so Windows behaves exactly as before.
        backup.autoColorization?.let { Advapi32Util.registrySetStringValue(hkcu, desktop, "AutoColorization", it) } ?: delete(desktop, "AutoColorization")
        wpm.win32.broadcastSettingChange("ImmersiveColorSet")
    }
}

/**
 * Optionally matches the Windows accent color (taskbar, Start, title bars) to the wallpaper.
 * The user's own accent is saved the first time and put back when the option is switched off.
 */
class AccentFromWallpaper(private val settings: Settings, private val api: AccentApi = WindowsAccent()) {
    val enabled: Boolean get() = settings.boolean("accent_from_wallpaper", false)

    fun setEnabled(on: Boolean) {
        settings.set("accent_from_wallpaper", on)
        if (!on) restore()
    }

    /** Applies the accent for a wallpaper picture (or a live wallpaper's thumbnail). */
    fun applyFrom(image: Image) {
        if (!enabled) return
        applyColor(dominantColor(image) ?: return)
    }

    /** Applies [rgb] as the accent (saving the user's own the first time). */
    fun applyColor(rgb: Int) {
        if (!enabled) return
        if (settings.obj("original_accent", AccentBackup.serializer()) == null) {
            settings.set("original_accent", AccentBackup.serializer(), api.read())
        }
        api.apply(rgb)
        settings.set("accent_color", "%06X".format(rgb))
        log.info("Accent color set to #%06X".format(rgb))
    }

    /** Puts the user's own accent color back. */
    fun restore() {
        val original = settings.obj("original_accent", AccentBackup.serializer()) ?: return
        api.write(original)
        settings.remove("original_accent")
        settings.remove("accent_color")
        log.info("Original accent color restored")
    }
}

// -- picking a color -------------------------------------------------------------------------

internal fun rgbToHsv(rgb: Int): Triple<Double, Double, Double> {
    val r = ((rgb shr 16) and 0xFF) / 255.0
    val g = ((rgb shr 8) and 0xFF) / 255.0
    val b = (rgb and 0xFF) / 255.0
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0.0 -> 0.0
        mx == r -> 60 * (((g - b) / d) % 6)
        mx == g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }.let { if (it < 0) it + 360 else it }
    return Triple(h, if (mx == 0.0) 0.0 else d / mx, mx)
}

internal fun hsvToRgb(h: Double, s: Double, v: Double): Int {
    val c = v * s
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = v - c
    val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    fun ch(f: Double) = ((f + m) * 255).toInt().coerceIn(0, 255)
    return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/**
 * The most striking color of a picture: hues are grouped and weighted by how colorful and
 * bright they are, so a small vivid sunset beats a large grey sky. The result is adjusted to
 * work as an accent (not too dark, not too pale). Null for a picture without any color.
 */
fun dominantColor(image: Image): Int? {
    val bitmap = org.jetbrains.skia.Bitmap()
    val w = 64
    val h = max(1, image.height * 64 / max(1, image.width))
    bitmap.allocN32Pixels(w, h)
    val canvas = org.jetbrains.skia.Canvas(bitmap)
    canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
    val buckets = DoubleArray(36)
    val sums = Array(36) { DoubleArray(3) }
    for (y in 0 until h) for (x in 0 until w) {
        val rgb = bitmap.getColor(x, y) and 0xFFFFFF
        val (hue, sat, value) = rgbToHsv(rgb)
        if (sat < 0.2 || value < 0.15) continue
        val weight = sat * sat * value
        val i = (hue / 10).toInt().coerceIn(0, 35)
        buckets[i] += weight
        sums[i][0] += hue * weight
        sums[i][1] += sat * weight
        sums[i][2] += value * weight
    }
    canvas.close()
    bitmap.close()
    // Neighbouring buckets count together, so a hue split across a boundary isn't penalised.
    val best = (0 until 36).maxByOrNull { buckets[it] + 0.5 * (buckets[(it + 35) % 36] + buckets[(it + 1) % 36]) } ?: return null
    if (buckets[best] < w * h * 0.002) return null
    val total = buckets[best]
    val hue = sums[best][0] / total
    val sat = (sums[best][1] / total).coerceIn(0.45, 0.9)
    val value = (sums[best][2] / total).coerceIn(0.55, 0.85)
    return hsvToRgb(hue, sat, value)
}
