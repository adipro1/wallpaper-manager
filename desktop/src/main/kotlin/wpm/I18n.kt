package wpm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** The languages the app speaks: code to the language's own name. */
val LANGUAGES = linkedMapOf("en" to "English", "de" to "Deutsch")

/**
 * Translations, keyed by the English text (resources/i18n/<code>.json). Text without a
 * translation stays English, so a missing entry never hides anything.
 */
object I18n {
    @Volatile var language: String = "en"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    /** "system" picks the Windows display language if the app speaks it, else English. */
    fun resolve(setting: String): String = when {
        setting in LANGUAGES -> setting
        Locale.getDefault().language in LANGUAGES -> Locale.getDefault().language
        else -> "en"
    }

    fun use(setting: String) {
        val code = resolve(setting)
        language = code
        table = if (code == "en") emptyMap() else load(code)
    }

    fun load(code: String): Map<String, String> {
        val text = I18n::class.java.getResourceAsStream("/i18n/$code.json")?.use { it.readBytes().decodeToString() } ?: return emptyMap()
        val json = Json.parseToJsonElement(text) as JsonObject
        return json.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }.toMap()
    }

    fun lookup(text: String): String = table[text] ?: text
}

/**
 * The text in the user's language. Placeholders {0}, {1}, ... are filled with [args]
 * (after translating, so translations can put them in a different order).
 */
fun tr(text: String, vararg args: Any?): String {
    var result = I18n.lookup(text)
    args.forEachIndexed { i, arg -> result = result.replace("{$i}", arg.toString()) }
    return result
}

/** "1 wallpaper" / "3 wallpapers": [one] and [many] are English texts with {0} for the number. */
fun trCount(count: Int, one: String, many: String): String = tr(if (count == 1) one else many, count)
