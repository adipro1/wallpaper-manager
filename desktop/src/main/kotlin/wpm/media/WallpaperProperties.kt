package wpm.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.data.Database
import wpm.data.MediaItem
import wpm.logger
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private val log = logger("properties")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json = Json { isLenient = true; ignoreUnknownKeys = true; allowTrailingComma = true; allowComments = true }

/** The control types of Lively's LivelyProperties.json. */
object PropertyType {
    const val SLIDER = "slider"
    const val COLOR = "color"
    const val CHECKBOX = "checkbox"
    const val DROPDOWN = "dropdown"
    const val FOLDER_DROPDOWN = "folderDropdown"
    const val TEXTBOX = "textbox"
    const val BUTTON = "button"
    const val LABEL = "label"
    /** Shaders only: a picture on this PC (goes into the page when it is written, not by the listener). */
    const val IMAGE = "image"
    /** The types LivelyProperties.json can contain. */
    val ALL = setOf(SLIDER, COLOR, CHECKBOX, DROPDOWN, FOLDER_DROPDOWN, TEXTBOX, BUTTON, LABEL)
}

/**
 * One setting a wallpaper offers, e.g. a "Speed" slider. Web wallpapers declare them in
 * Lively's LivelyProperties.json; shaders with comments such as `// @slider speed 0 2 1`.
 * The page receives values through `livelyPropertyListener(name, value)`, as in Lively.
 */
data class WallpaperProperty(
    val name: String,
    val type: String,
    val label: String,
    /** The value the wallpaper comes with: a number, "#rrggbb", true/false, an index or a text. */
    val default: JsonPrimitive,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val step: Double = 1.0,
    /** Dropdown choices. */
    val items: List<String> = emptyList(),
    /** folderDropdown: the folder (inside the wallpaper) to choose a file from, and a filter like "*.jpg|*.png". */
    val folder: String? = null,
    val filter: String? = null,
    /** From a Wallpaper Engine project.json: sent through wallpaperPropertyListener.applyUserProperties. */
    val engine: Boolean = false,
    /** Wallpaper Engine combos: the value each dropdown item stands for. */
    val optionValues: List<JsonPrimitive> = emptyList(),
) {
    /** Sent to the page when it loads (buttons only when clicked, labels never). */
    val sentOnLoad: Boolean get() = type != PropertyType.BUTTON && type != PropertyType.LABEL && type != PropertyType.IMAGE

    /** [value] made valid for this control (wrong types and out-of-range values fall back). */
    fun normalize(value: JsonElement?): JsonPrimitive {
        val p = value as? JsonPrimitive ?: return default
        return when (type) {
            PropertyType.SLIDER -> p.doubleOrNull?.coerceIn(minOf(min, max), maxOf(min, max))?.let(::number) ?: default
            PropertyType.CHECKBOX -> p.booleanOrNull?.let(::JsonPrimitive) ?: default
            PropertyType.DROPDOWN -> p.intOrNull?.takeIf { it in items.indices }?.let(::JsonPrimitive) ?: default
            PropertyType.COLOR -> p.contentOrNull?.let(::parseColor)?.let { JsonPrimitive(formatColor(it)) } ?: default
            else -> if (p.isString) p else default
        }
    }

    /** Whole numbers stay whole, so pages that compare with === or use them as indexes work. */
    private fun number(v: Double): JsonPrimitive {
        val stepped = if (step > 0) Math.round((v - min) / step) * step + min else v
        val clean = stepped.coerceIn(minOf(min, max), maxOf(min, max))
        return if (clean == Math.rint(clean) && step == Math.rint(step)) JsonPrimitive(clean.toLong()) else JsonPrimitive(Math.round(clean * 1e6) / 1e6)
    }

    companion object {
        /** "#RRGGBB" / "#RGB" / "#AARRGGBB" -> 0xRRGGBB, or null. */
        fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            val full = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                8 -> hex.substring(2) // Lively writes #AARRGGBB sometimes
                else -> return null
            }
            return full.toIntOrNull(16)
        }

        fun formatColor(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)
    }
}

/** Reads wallpaper properties and keeps the values the user picked (per library item). */
class WallpaperProperties(private val db: Database) {

    /** The folder a web wallpaper's files are in, or null for websites. */
    fun folderOf(item: MediaItem): Path? {
        val local = item.localPath ?: return null
        return if (local.isDirectory()) local else local.parent
    }

    /** The settings a web wallpaper offers (empty if it has none or the file can't be read). */
    fun read(item: MediaItem): List<WallpaperProperty> {
        if (!item.isWeb) return emptyList()
        val folder = folderOf(item) ?: return emptyList()
        val lively = folder.resolve(LIVELY_FILE).takeIf { it.isRegularFile() }
        val engine = folder.resolve(ENGINE_FILE).takeIf { it.isRegularFile() }
        val file = lively ?: engine ?: return emptyList()
        return try {
            if (file == lively) parseLively(file.readText()) else parseEngine(file.readText())
        } catch (e: Exception) {
            log.warning("Ignoring unreadable ${file}: $e")
            emptyList()
        }
    }

    /** Files a folderDropdown can choose from (names only, sorted). */
    fun folderFiles(item: MediaItem, property: WallpaperProperty): List<String> {
        val dir = folderOf(item)?.resolve(property.folder ?: return emptyList())?.normalize() ?: return emptyList()
        if (!dir.isDirectory() || folderOf(item)?.let { dir.startsWith(it) } != true) return emptyList()
        val patterns = (property.filter ?: "*").split('|', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Regex(it.replace(".", "\\.").replace("*", ".*").replace("?", "."), RegexOption.IGNORE_CASE) }
        return dir.listDirectoryEntries().filter { f -> f.isRegularFile() && patterns.any { it.matches(f.name) } }.map { it.name }.sorted()
    }

    private fun key(mediaId: Long) = "properties.$mediaId"

    /** Values the user changed (property name -> value). */
    fun saved(mediaId: Long): Map<String, JsonElement> = db.getSetting(key(mediaId)) as? JsonObject ?: emptyMap()

    /** The value of every property: the user's choice, else the wallpaper's own default. */
    fun values(mediaId: Long, properties: List<WallpaperProperty>): Map<String, JsonPrimitive> {
        val saved = saved(mediaId)
        return properties.associate { it.name to it.normalize(saved[it.name]) }
    }

    fun set(mediaId: Long, name: String, value: JsonElement) {
        db.setSetting(key(mediaId), JsonObject(saved(mediaId) + (name to value)))
    }

    fun reset(mediaId: Long) {
        db.execute("DELETE FROM settings WHERE key = ?", key(mediaId))
    }

    companion object {
        const val LIVELY_FILE = "LivelyProperties.json"
        const val ENGINE_FILE = "project.json"

        /** "0.5 0.25 1" (Wallpaper Engine) -> "#8040FF". */
        fun engineColorToHex(text: String): String? {
            val parts = text.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size < 3) return null
            val (r, g, b) = parts.map { Math.round(it.coerceIn(0.0, 1.0) * 255).toInt() }
            return WallpaperProperty.formatColor((r shl 16) or (g shl 8) or b)
        }

        /** "#8040FF" -> "0.50196 0.25098 1" (Wallpaper Engine's own format). */
        fun hexToEngineColor(hex: String): String {
            val rgb = WallpaperProperty.parseColor(hex) ?: 0
            return listOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                .joinToString(" ") { "%.5f".format(java.util.Locale.ROOT, it / 255.0).trimEnd('0').trimEnd('.').ifEmpty { "0" } }
        }

        /** Labels in project.json may contain HTML or untranslated "ui_" keys. */
        private fun engineLabel(text: String?, name: String): String {
            val plain = text?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return plain.takeIf { it.isNotEmpty() && !it.startsWith("ui_") } ?: name
        }

        /** The settings in a Wallpaper Engine project.json (general.properties), in their "order". */
        fun parseEngine(text: String): List<WallpaperProperty> {
            val root = json.parseToJsonElement(text).jsonObject
            val props = ((root["general"] as? JsonObject)?.get("properties") as? JsonObject) ?: return emptyList()
            return props.entries.mapNotNull { (name, element) ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val raw = o["value"] as? JsonPrimitive ?: JsonPrimitive("")
                val label = engineLabel((o["text"] as? JsonPrimitive)?.contentOrNull, name)
                fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
                val order = num("order") ?: 0.0
                val property = when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                    "slider" -> WallpaperProperty(name, PropertyType.SLIDER, label, JsonPrimitive(raw.doubleOrNull ?: 0.0),
                        num("min") ?: 0.0, num("max") ?: 100.0, num("step") ?: if (o["fraction"]?.jsonPrimitive?.booleanOrNull == true) 0.01 else 1.0, engine = true)
                    "bool" -> WallpaperProperty(name, PropertyType.CHECKBOX, label, JsonPrimitive(raw.booleanOrNull ?: false), engine = true)
                    "color" -> WallpaperProperty(name, PropertyType.COLOR, label, JsonPrimitive(engineColorToHex(raw.content) ?: "#FFFFFF"), engine = true)
                    "textinput" -> WallpaperProperty(name, PropertyType.TEXTBOX, label, JsonPrimitive(raw.content), engine = true)
                    "text" -> WallpaperProperty(name, PropertyType.LABEL, label, JsonPrimitive(label), engine = true)
                    "combo" -> {
                        val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        val values = options.mapNotNull { it["value"] as? JsonPrimitive }
                        WallpaperProperty(name, PropertyType.DROPDOWN, label, JsonPrimitive(values.indexOfFirst { it.content == raw.content }.coerceAtLeast(0)),
                            items = options.map { engineLabel((it["label"] as? JsonPrimitive)?.contentOrNull, (it["value"] as? JsonPrimitive)?.content ?: "") },
                            engine = true, optionValues = values)
                    }
                    else -> null // file / directory pickers aren't supported
                } ?: return@mapNotNull null
                order to property.copy(default = property.normalize(property.default))
            }.sortedBy { it.first }.map { it.second }
        }

        /** Parses LivelyProperties.json (unknown control types are skipped). */
        fun parseLively(text: String): List<WallpaperProperty> =
            json.parseToJsonElement(text).jsonObject.mapNotNull { (name, element) ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val type = o["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it in PropertyType.ALL } ?: return@mapNotNull null
                val raw = o["value"] as? JsonPrimitive ?: JsonPrimitive("")
                val items = (o["items"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
                val default = when (type) {
                    PropertyType.SLIDER -> JsonPrimitive(raw.doubleOrNull ?: 0.0)
                    PropertyType.CHECKBOX -> JsonPrimitive(raw.booleanOrNull ?: false)
                    PropertyType.DROPDOWN -> JsonPrimitive(raw.intOrNull ?: 0)
                    else -> JsonPrimitive(raw.contentOrNull ?: "")
                }
                val property = WallpaperProperty(
                    name = name, type = type,
                    label = (o["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: name,
                    default = default, min = num("min") ?: 0.0, max = num("max") ?: 100.0, step = num("step") ?: 1.0,
                    items = items,
                    folder = (o["folder"] as? JsonPrimitive)?.contentOrNull,
                    filter = (o["filter"] as? JsonPrimitive)?.contentOrNull,
                )
                property.copy(default = property.normalize(default)) // e.g. integral slider values
            }

        /** The value as the page gets it (a folderDropdown sends "folder\\file", like Lively). */
        fun pageValue(property: WallpaperProperty, value: JsonPrimitive): JsonPrimitive = when {
            property.engine && property.type == PropertyType.COLOR -> JsonPrimitive(hexToEngineColor(value.content))
            property.engine && property.type == PropertyType.DROPDOWN -> property.optionValues.getOrNull(value.intOrNull ?: 0) ?: value
            else -> pageValueLively(property, value)
        }

        private fun pageValueLively(property: WallpaperProperty, value: JsonPrimitive): JsonPrimitive = when (property.type) {
            PropertyType.FOLDER_DROPDOWN -> property.folder?.takeIf { value.content.isNotEmpty() }
                ?.let { JsonPrimitive(it.trimEnd('/', '\\') + "\\" + value.content) } ?: value
            else -> value
        }

        /**
         * JavaScript that hands values to the page: Lively's livelyPropertyListener(name, value) once
         * per value, or for Wallpaper Engine projects wallpaperPropertyListener.applyUserProperties({name: {value}}).
         */
        fun script(calls: List<Pair<String, JsonPrimitive>>, engine: Boolean = false): String {
            if (calls.isEmpty()) return ""
            if (engine) {
                val props = calls.joinToString(",") { (name, value) -> "${JsonPrimitive(name)}:{\"value\":$value}" }
                return "(function(){var l=window.wallpaperPropertyListener;if(!l||typeof l.applyUserProperties!=='function')return;" +
                    "try{l.applyUserProperties({$props})}catch(e){console.error(e)}})();"
            }
            val body = calls.joinToString("") { (name, value) -> "try{f(${JsonPrimitive(name)},$value)}catch(e){console.error(e)}" }
            return "(function(){var f=window.livelyPropertyListener;if(typeof f!=='function')return;$body})();"
        }

        /** The script that sends every value when the page has loaded. */
        fun loadScript(properties: List<WallpaperProperty>, values: Map<String, JsonPrimitive>): String =
            script(properties.filter { it.sentOnLoad }.map { it.name to pageValue(it, values[it.name] ?: it.default) }, properties.any { it.engine })
    }
}
