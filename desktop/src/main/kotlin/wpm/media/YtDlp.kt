package wpm.media

import wpm.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

private val log = logger("yt-dlp")

/** What yt-dlp knows about a video page or link. */
data class RemoteVideo(
    val url: String,
    val title: String,
    val duration: Double?,
    val width: Int?,
    val height: Int?,
    val thumbnailUrl: String?,
    val isLive: Boolean,
)

class DownloadCancelledException : IOException(tr("Download cancelled"))

/**
 * Runs yt-dlp.exe (https://github.com/yt-dlp/yt-dlp), which understands YouTube and
 * hundreds of other sites as well as plain video links. Sites change often, so it can
 * update itself ([update]).
 */
class YtDlp(val exe: Path) {
    val available: Boolean get() = exe.exists()

    private fun process(vararg args: String): Process =
        ProcessBuilder(listOf(exe.absolutePathString()) + args).redirectErrorStream(false).start()

    /** Runs to completion and returns stdout; throws with yt-dlp's error message on failure. */
    private fun run(timeoutSeconds: Long, vararg args: String): String {
        val p = process(*args)
        var stderr = ""
        val errReader = thread(isDaemon = true) { stderr = p.errorStream.bufferedReader().readText() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IOException(tr("yt-dlp took too long"))
        }
        errReader.join(2_000)
        if (p.exitValue() != 0) throw IOException(friendlyError(stderr))
        return out
    }

    fun version(): String = run(30, "--version").trim()

    /** Title, length, size and thumbnail of a video page or link. */
    fun info(url: String): RemoteVideo {
        val json = Json.parseToJsonElement(run(90, "-J", "--no-playlist", "--no-warnings", url)).jsonObject
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String) = json[key]?.jsonPrimitive?.intOrNull
        return RemoteVideo(
            url = str("webpage_url") ?: url,
            title = str("title")?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?'),
            duration = json["duration"]?.jsonPrimitive?.doubleOrNull,
            width = int("width") ?: bestFormat(json)?.first,
            height = int("height") ?: bestFormat(json)?.second,
            thumbnailUrl = str("thumbnail"),
            isLive = json["is_live"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun bestFormat(json: JsonObject): Pair<Int, Int>? =
        (json["formats"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { f ->
            val o = f.jsonObject
            val w = o["width"]?.jsonPrimitive?.intOrNull
            val h = o["height"]?.jsonPrimitive?.intOrNull
            if (w != null && h != null) w to h else null
        }?.maxByOrNull { it.second }

    /**
     * Downloads the best video stream up to [maxHeight] into [folder] and returns the file.
     * Video-only streams are preferred: they reach full quality without needing ffmpeg to merge
     * audio (wallpapers are muted anyway). [progress] gets 0..1; [cancelled] is polled.
     */
    fun download(url: String, folder: Path, maxHeight: Int, progress: (Double) -> Unit, cancelled: () -> Boolean): Path {
        Files.createDirectories(folder)
        val format = "bv*[height<=$maxHeight][ext=mp4]/bv*[height<=$maxHeight]/b[height<=$maxHeight]/bv*/b"
        val p = process(
            "-f", format, "--no-playlist", "--no-warnings", "--windows-filenames",
            "-o", folder.resolve("%(title).80B [%(id)s].%(ext)s").toString(),
            "--progress", "--newline", "--print", "after_move:filepath", url,
        )
        var stderr = ""
        val errReader = thread(isDaemon = true) { stderr = p.errorStream.bufferedReader().readText() }
        val percent = Regex("""\[download]\s+([\d.]+)%""")
        var file: String? = null
        val watcher = thread(isDaemon = true) {
            while (p.isAlive) {
                if (cancelled()) p.destroyForcibly()
                Thread.sleep(200)
            }
        }
        p.inputStream.bufferedReader().forEachLine { line ->
            val match = percent.find(line)
            when {
                match != null -> match.groupValues[1].toDoubleOrNull()?.let { progress(it / 100) }
                line.isNotBlank() && !line.startsWith("[") -> file = line.trim()
            }
        }
        p.waitFor()
        watcher.join(1_000)
        errReader.join(2_000)
        if (cancelled()) throw DownloadCancelledException()
        if (p.exitValue() != 0) throw IOException(friendlyError(stderr))
        val path = file?.let(Path::of)?.takeIf { it.exists() } ?: throw IOException(tr("yt-dlp didn't report the downloaded file"))
        log.info("Downloaded $url to $path")
        return path
    }

    /** Updates yt-dlp.exe in place; returns its output (e.g. "Updated yt-dlp to ..."). */
    fun update(): String = run(300, "-U").lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Done"

    private fun friendlyError(stderr: String): String {
        val line = stderr.lines().lastOrNull { it.startsWith("ERROR:") } ?: stderr.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed"
        log.warning("yt-dlp: ${stderr.trim()}")
        return line.removePrefix("ERROR:").trim().let {
            when {
                "Unsupported URL" in it -> "This link isn't a video page yt-dlp understands"
                "Private video" in it || "Sign in" in it -> "This video isn't public"
                "Unable to download webpage" in it || "getaddrinfo" in it -> "Couldn't reach the site - are you online?"
                else -> it
            }
        }
    }
}
