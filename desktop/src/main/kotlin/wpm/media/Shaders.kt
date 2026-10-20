package wpm.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wpm.data.Database
import wpm.data.MediaItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

val SHADER_EXTENSIONS = setOf("glsl", "frag", "fs")

/** A shader that ships with the app. */
data class SampleShader(val name: String, val code: String)

/**
 * Shader wallpapers: Shadertoy-style GLSL (a mainImage function) played by the web engine
 * through the bundled runner page. The code is kept as a .glsl file in the app's folder.
 */
class Shaders(
    private val db: Database,
    private val web: WebWallpapers,
    private val folder: Path,
    private val pagesDir: Path,
) {
    private val runner: String by lazy {
        javaClass.getResourceAsStream("/shader/runner.html")!!.use { it.readBytes().decodeToString() }
    }

    fun samples(): List<SampleShader> = listOf("Plasma", "Aurora", "Ripples", "Equalizer").map { name ->
        SampleShader(name, javaClass.getResourceAsStream("/shader/samples/$name.glsl")!!.use { it.readBytes().decodeToString() })
    }

    /** Adds a new shader to the library. Returns its id. */
    fun create(name: String, code: String, interactive: Boolean = true, audio: Boolean = false): Long {
        Files.createDirectories(folder)
        val slug = name.replace(Regex("[^\\w -]"), "_").trim().ifEmpty { "shader" }
        var file = folder.resolve("$slug.glsl")
        var n = 2
        while (file.exists()) file = folder.resolve("$slug ${n++}.glsl")
        file.writeText(code)
        return db.addItem(name, "shader", path = file.toString(), thumbnail = web.thumbnail(null, name, "SHADER"),
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

    /** Writes the page that plays [item] and returns its address. */
    fun pageFor(item: MediaItem, fps: Int): String = writePage("shader-${item.id}.html", code(item), fps)

    /** A page for trying out code that isn't saved yet. */
    fun previewPage(code: String, fps: Int = 60): String = writePage("preview-${System.nanoTime()}.html", code, fps)

    private fun writePage(name: String, code: String, fps: Int): String {
        Files.createDirectories(pagesDir)
        val config = buildJsonObject {
            put("fps", fps)
            put("code", JsonPrimitive(code))
        }
        // "</" would end the <script> block early; JSON allows "\/" for "/".
        val json = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config).replace("</", "<\\/")
        val page = pagesDir.resolve(name)
        page.writeText(runner.replace("/*__CONFIG__*/", json))
        return page.toUri().toString()
    }

    /** Removes old preview pages (they are only needed while the editor is open). */
    fun cleanPreviews() {
        if (!pagesDir.exists()) return
        Files.list(pagesDir).use { files -> files.filter { it.fileName.toString().startsWith("preview-") }.forEach { runCatching { Files.delete(it) } } }
    }
}
