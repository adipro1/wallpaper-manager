package wpm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    @AfterTest
    fun backToEnglish() = I18n.use("en")

    @Test
    fun translatesAndFillsPlaceholders() {
        I18n.use("de")
        assertEquals("de", I18n.language)
        assertEquals("Bibliothek", tr("Library"))
        assertEquals("„Abend“ läuft: city.mp4", tr("Playing “{0}”: {1}", "Abend", "city.mp4"))
        assertEquals("1 Hintergrundbild", trCount(1, "{0} wallpaper", "{0} wallpapers"))
        assertEquals("3 Hintergrundbilder", trCount(3, "{0} wallpaper", "{0} wallpapers"))
        assertEquals("Not translated {0}".replace("{0}", "x"), tr("Not translated {0}", "x")) // falls back to English
        I18n.use("en")
        assertEquals("Library", tr("Library"))
        assertEquals("en", I18n.resolve("xx"))
        assertEquals("de", I18n.resolve("de"))
    }

    /** Each translation keeps the placeholders of its English text. */
    @Test
    fun translationsKeepTheirPlaceholders() {
        for (code in LANGUAGES.keys - "en") {
            val table = I18n.load(code)
            assertTrue(table.size > 500, "$code has ${table.size} entries")
            for ((english, translated) in table) {
                val wanted = Regex("\\{\\d+}").findAll(english).map { it.value }.toSet()
                val found = Regex("\\{\\d+}").findAll(translated).map { it.value }.toSet()
                assertEquals(wanted, found, "$code: \"$english\" -> \"$translated\"")
            }
        }
    }

    /** Every tr("...") text in the code has a German translation (apart from technical texts). */
    @Test
    fun everyTextIsTranslated() {
        val english = setOf(
            "CPU", "GPU", "GIF", "GLSL", "MANAGER", "Wallpaper Engine", "{0}\n\n{1}", "{0} failed (0x{1})",
            "WallpaperManager-backup-{0}.{1}", "WallpaperManager-diagnostics-{0}.zip",
        )
        val developer = Regex("^(CreateWindowEx|RegisterClassEx|IDesktopWallpaper|Timed out creating|Not a Wallhaven address|Unknown effect|Use set|Pick a frame with setVideoFrame)")
        val table = I18n.load("de")
        val literal = Regex("""\btr\(\s*"((?:[^"\\$]|\\.)*)"""")
        val missing = mutableListOf<String>()
        Files.walk(Path.of("src/main/kotlin")).use { files ->
            files.filter { it.extension == "kt" }.forEach { file ->
                for (match in literal.findAll(file.readText())) {
                    val key = match.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                    if (key !in table && key !in english && !developer.containsMatchIn(key)) missing += "${file.fileName}: $key"
                }
            }
        }
        assertTrue(missing.isEmpty(), "Without a German translation:\n" + missing.joinToString("\n"))
    }
}
