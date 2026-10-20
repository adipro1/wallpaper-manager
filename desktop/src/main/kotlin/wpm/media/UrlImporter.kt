package wpm.media

import wpm.data.Database
import wpm.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.fileSize

private val log = logger("url-import")

/** "https://…" links only; returns a normalised URL or null. */
fun parseVideoUrl(text: String): String? {
    val t = text.trim().removeSurrounding("<", ">").removeSurrounding("\"")
    if (!(t.startsWith("http://", true) || t.startsWith("https://", true))) return null
    return runCatching { URI(t).takeIf { !it.host.isNullOrBlank() }?.toString() }.getOrNull()
}

/** Adds online videos: streamed on demand, or downloaded for offline use. */
class UrlImporter(
    private val db: Database,
    private val library: MediaLibrary,
    private val ytDlp: YtDlp,
    val downloadDir: Path,
) {
    val available: Boolean get() = ytDlp.available

    fun lookUp(url: String): RemoteVideo = ytDlp.info(url)

    /** Adds the video as a stream (nothing is downloaded except its thumbnail). Returns the new id, or null if it was already added. */
    fun addStream(video: RemoteVideo): Long? {
        if (db.hasUrl(video.url)) return null
        val thumb = runCatching { saveThumbnail(video) }.onFailure { log.info("No thumbnail for ${video.url}: $it") }.getOrNull()
        return db.addItem(
            video.title, "video", sourceUrl = video.url, width = video.width, height = video.height,
            duration = video.duration, thumbnail = thumb,
        )
    }

    /** Downloads the video (up to [maxHeight] lines) and adds it as a normal library video. */
    fun download(video: RemoteVideo, maxHeight: Int, progress: (Double) -> Unit, cancelled: () -> Boolean): Long? {
        val file = ytDlp.download(video.url, downloadDir, maxHeight, progress, cancelled)
        val info = library.probe(file)
        val thumb = library.createThumbnail(file, "video", info.duration)
        return db.addMedia(file.toString(), "video", info.width, info.height, info.duration, file.fileSize(), thumb,
            sourceUrl = video.url.takeUnless(db::hasUrl))
    }

    private fun saveThumbnail(video: RemoteVideo): String {
        val url = video.thumbnailUrl ?: error("no thumbnail")
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).build()
        val bytes = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(request, HttpResponse.BodyHandlers.ofByteArray()).body()
        val name = library.thumbnailNameFor(video.url)
        Imaging.decode(bytes).use { img ->
            Imaging.cover(img, THUMB_WIDTH, THUMB_HEIGHT).use { Imaging.saveJpeg(it, library.thumbDir.resolve(name), 85) }
        }
        return name
    }
}
