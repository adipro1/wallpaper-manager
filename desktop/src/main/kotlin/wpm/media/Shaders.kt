package wpm.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wpm.data.Database
import wpm.data.MediaItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

val SHADER_EXTENSIONS = setOf("glsl", "frag", "fs")

/** A shader that ships with the app. */
data class SampleShader(val name: String, val code: String)

/** The "make it alive" effects in resources/shader/effects. */
val PICTURE_EFFECTS = listOf("Ken Burns", "Parallax", "Rain on glass", "Snowfall")

/** Pictures for iChannel1-3 are scaled down to at most this size before they go into the page. */
private const val MAX_PICTURE = 2560

private val ANNOTATION = Regex("""^\s*//\s*@(slider|color|checkbox|image)\s+([A-Za-z_]\w*)\s*(.*)$""")

/**
 * The settings a shader declares in comments, one per line:
 *
 *     // @slider speed 0 2 1 Speed        a float uniform: min, max, default, label
 *     // @color tint #ff8800 Tint         a vec3 uniform (0..1 per channel)
 *     // @checkbox rain on Rain           a bool uniform (on/off or true/false)
 *     // @image photo Picture             a picture; "photo" is iChannel1 (then iChannel2, iChannel3)
 *
 * The runner declares the uniforms, so the code just uses the names.
 */
fun parseShaderProperties(code: String): List<WallpaperProperty> {
    val result = mutableListOf<WallpaperProperty>()
    for (line in code.lineSequence()) {
        val match = ANNOTATION.matchEntire(line) ?: continue
        val (type, name, rest) = match.destructured
        if (result.any { it.name == name } || name.startsWith("i") && name.drop(1).firstOrNull()?.isUpperCase() == true) continue
        val words = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun label(skip: Int) = words.drop(skip).joinToString(" ").ifEmpty { name }
        result += when (type) {
            "slider" -> {
                val numbers = words.take(3).map { it.toDoubleOrNull() }
                if (numbers.size < 3 || numbers.any { it == null }) continue
                val (min, max, default) = numbers.map { it!! }
                if (max <= min) continue
                val step = if (listOf(min, max, default).all { it == Math.rint(it) } && max - min >= 10) 1.0 else (max - min) / 100
                WallpaperProperty(name, PropertyType.SLIDER, label(3), JsonPrimitive(default), min, max, step)
            }
            "color" -> {
                val rgb = words.firstOrNull()?.let(WallpaperProperty::parseColor) ?: continue
                WallpaperProperty(name, PropertyType.COLOR, label(1), JsonPrimitive(WallpaperProperty.formatColor(rgb)))
            }
            "checkbox" -> {
                val on = when (words.firstOrNull()?.lowercase()) {
                    "on", "true", "yes", "1" -> true
                    "off", "false", "no", "0" -> false
                    else -> continue
                }
                WallpaperProperty(name, PropertyType.CHECKBOX, label(1), JsonPrimitive(on))
            }
            else -> {
                if (result.count { it.type == PropertyType.IMAGE } >= 3) continue
                WallpaperProperty(name, PropertyType.IMAGE, label(0), JsonPrimitive(""))
            }
        }.let { it.copy(default = it.normalize(it.default)) }
    }
    return result
}

/**
 * Shader wallpapers: Shadertoy-style GLSL (a mainImage function) played by the web engine
 * through the bundled runner page. The code is kept as a .glsl file in the app's folder.
 */
class Shaders(
    private val db: Database,
    private val web: WebWallpapers,
    private val folder: Path,
    private val pagesDir: Path,
    private val values: WallpaperProperties = WallpaperProperties(db),
) {
    private val runner: String by lazy {
        javaClass.getResourceAsStream("/shader/runner.html")!!.use { it.readBytes().decodeToString() }
    }

    fun samples(): List<SampleShader> = listOf("Plasma", "Aurora", "Ripples", "Equalizer").map { name ->
        SampleShader(name, javaClass.getResourceAsStream("/shader/samples/$name.glsl")!!.use { it.readBytes().decodeToString() })
    }

    /** Effects that bring a picture to life (each uses "// @image photo"). */
    fun effects(): List<SampleShader> = PICTURE_EFFECTS.map { name ->
        SampleShader(name, javaClass.getResourceAsStream("/shader/effects/$name.glsl")!!.use { it.readBytes().decodeToString() })
    }

    /** Adds a new shader to the library (with [preview] as its thumbnail, if given). Returns its id. */
    fun create(name: String, code: String, interactive: Boolean = true, audio: Boolean = false, preview: Path? = null): Long {
        Files.createDirectories(folder)
        val slug = name.replace(Regex("[^\\w -]"), "_").trim().ifEmpty { "shader" }
        var file = folder.resolve("$slug.glsl")
        var n = 2
        while (file.exists()) file = folder.resolve("$slug ${n++}.glsl")
        file.writeText(code)
        return db.addItem(name, "shader", path = file.toString(), thumbnail = web.thumbnail(preview, name, "SHADER"),
            interactive = interactive, audio = audio) ?: error("Couldn't add the shader")
    }

    /** Imports a .glsl/.frag file (copied, so editing it here doesn't change the original). */
    fun import(file: Path): Long {
        require(file.extension.lowercase() in SHADER_EXTENSIONS) { "Choose a .glsl or .frag file" }
        val code = file.readText()
        require("mainImage" in code) { "This doesn't look like a Shadertoy shader (no mainImage function)" }
        return create(file.nameWithoutExtension, code, interactive = "iMouse" in code, audio = "iChannel0" in code)
    }

    fun code(item: MediaItem): String = item.localPath?.takeIf { it.exists() }?.readText() ?: ""

    fun save(item: MediaItem, code: String) {
        Path(requireNotNull(item.path)).writeText(code)
    }

    /** The settings the shader declares (see [parseShaderProperties]). */
    fun properties(item: MediaItem): List<WallpaperProperty> = parseShaderProperties(code(item))

    /** Writes the page that plays [item] and returns its address. */
    fun pageFor(item: MediaItem, fps: Int): String {
        val code = code(item)
        return writePage("shader-${item.id}.html", code, fps, values.values(item.id, parseShaderProperties(code)))
    }

    /** A page for trying out code that isn't saved yet (with [mediaId]'s settings, if it is a library shader). */
    fun previewPage(code: String, fps: Int = 60, mediaId: Long? = null): String {
        val properties = parseShaderProperties(code)
        val chosen = mediaId?.let { values.values(it, properties) } ?: properties.associate { it.name to it.default }
        return writePage("preview-${System.nanoTime()}.html", code, fps, chosen)
    }

    private fun writePage(name: String, code: String, fps: Int, chosen: Map<String, JsonPrimitive>): String {
        Files.createDirectories(pagesDir)
        val properties = parseShaderProperties(code)
        var channel = 0
        val channels = properties.filter { it.type == PropertyType.IMAGE }.associate { it.name to ++channel }
        val config = buildJsonObject {
            put("fps", fps)
            put("code", JsonPrimitive(code))
            putJsonArray("properties") {
                properties.forEach { p ->
                    addJsonObject {
                        put("name", p.name)
                        put("type", p.type)
                        put("value", chosen[p.name] ?: p.default)
                        channels[p.name]?.let { put("channel", it) }
                    }
                }
            }
            putJsonObject("images") {
                for ((propertyName, number) in channels) {
                    val file = chosen[propertyName]?.content?.takeIf { it.isNotEmpty() }?.let(::Path) ?: continue
                    pictureData(file)?.let { put(number.toString(), it) }
                }
            }
        }
        // "</" would end the <script> block early; JSON allows "\/" for "/".
        val json = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config).replace("</", "<\\/")
        val page = pagesDir.resolve(name)
        page.writeText(runner.replace("/*__CONFIG__*/", json))
        return page.toUri().toString()
    }

    /** A picture as a data: URL, scaled down and cached (the page can't load file:// pictures into WebGL). */
    private fun pictureData(file: Path): String? = runCatching {
        if (!file.isRegularFile()) return null
        val key = Integer.toHexString("$file|${file.getLastModifiedTime().toMillis()}".hashCode())
        val cached = pagesDir.resolve("picture-$key.jpg")
        if (!cached.exists()) {
            Imaging.load(file).use { image ->
                val scale = minOf(1.0, MAX_PICTURE.toDouble() / maxOf(image.width, image.height))
                if (scale < 1.0) {
                    Imaging.cover(image, (image.width * scale).toInt(), (image.height * scale).toInt()).use { Imaging.saveJpeg(it, cached, 90) }
                } else {
                    Imaging.saveJpeg(image, cached, 92)
                }
            }
        }
        "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(cached))
    }.onFailure { wpm.logger("shaders").warning("Couldn't use $file in a shader: $it") }.getOrNull()

    /** Removes old preview pages (they are only needed while the editor is open). */
    fun cleanPreviews() {
        if (!pagesDir.exists()) return
        Files.list(pagesDir).use { files -> files.filter { it.fileName.toString().startsWith("preview-") }.forEach { runCatching { Files.delete(it) } } }
    }
}
