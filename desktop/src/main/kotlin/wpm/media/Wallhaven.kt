package wpm.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wpm.APP_VERSION
import wpm.logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

private val log = logger("wallhaven")

/** One wallpaper found on wallhaven.cc. */
data class OnlineWallpaper(
    val id: String,
    /** Its page on the site. */
    val pageUrl: String,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
) {
    val resolutionText: String get() = "$width × $height"
}

data class OnlineResults(val items: List<OnlineWallpaper>, val page: Int, val lastPage: Int)

/** Where wallpapers can be found online. An interface so tests (and the UI test) don't use the network. */
interface WallpaperSource {
    /** [sort]: "toplist", "date_added", "random" or "relevance". */
    fun search(query: String, sort: String, page: Int, atLeast: String? = null): OnlineResults
    fun bytes(url: String): ByteArray
}

/**
 * wallhaven.cc's public API: only "safe for work" pictures, general and anime categories,
 * no account. Downloads only come from the site's own image servers.
 */
class Wallhaven(private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) : WallpaperSource {
    private fun get(url: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", "WallpaperManager/$APP_VERSION")
            .timeout(Duration.ofSeconds(20)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        when (response.statusCode()) {
            200 -> return response
            429 -> throw IOException("Wallhaven is busy (too many requests); try again in a minute")
            else -> throw IOException("Wallhaven answered ${response.statusCode()}")
        }
    }

    override fun search(query: String, sort: String, page: Int, atLeast: String?): OnlineResults {
        val params = buildList {
            if (query.isNotBlank()) add("q=" + URLEncoder.encode(query.trim(), Charsets.UTF_8))
            add("categories=110") // general + anime, no people
            add("purity=100") // safe for work only
            add("sorting=" + (if (query.isNotBlank() && sort == "toplist") "relevance" else sort))
            if (sort == "toplist") add("topRange=1M")
            atLeast?.let { add("atleast=$it") }
            add("page=$page")
        }
        val json = Json.parseToJsonElement(get("https://wallhaven.cc/api/v1/search?" + params.joinToString("&")).body().decodeToString()).jsonObject
        val items = json["data"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
        val meta = json["meta"] as? JsonObject
        return OnlineResults(items, meta?.get("current_page")?.jsonPrimitive?.intOrNull ?: page, meta?.get("last_page")?.jsonPrimitive?.intOrNull ?: page)
    }

    private fun parse(o: JsonObject): OnlineWallpaper? {
        fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        val path = str("path") ?: return null
        val thumb = (o["thumbs"] as? JsonObject)?.get("small")?.jsonPrimitive?.contentOrNull ?: return null
        if (!isWallhavenHost(path) || !isWallhavenHost(thumb)) return null
        return OnlineWallpaper(
            id = str("id") ?: return null, pageUrl = str("url") ?: "https://wallhaven.cc",
            imageUrl = path, thumbUrl = thumb,
            width = o["dimension_x"]?.jsonPrimitive?.intOrNull ?: 0, height = o["dimension_y"]?.jsonPrimitive?.intOrNull ?: 0,
            fileSize = o["file_size"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    override fun bytes(url: String): ByteArray {
        require(isWallhavenHost(url)) { "Not a Wallhaven address: $url" }
        return get(url).body()
    }

    companion object {
        /** Only https and the site's own servers (wallhaven.cc, w.wallhaven.cc, th.wallhaven.cc). */
        fun isWallhavenHost(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && (uri.host == "wallhaven.cc" || uri.host.endsWith(".wallhaven.cc"))
        }.getOrDefault(false)
    }
}

/** Saves an online wallpaper to [folder] (Pictures\Wallpapers) and returns the file. */
fun downloadWallpaper(source: WallpaperSource, wallpaper: OnlineWallpaper, folder: Path): Path {
    val ext = wallpaper.imageUrl.substringAfterLast('.', "jpg").lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
    val target = folder.resolve("wallhaven-${wallpaper.id.filter(Char::isLetterOrDigit)}.$ext")
    if (Files.exists(target)) return target
    val bytes = source.bytes(wallpaper.imageUrl)
    require(bytes.size in 1..80_000_000) { "The download is empty or too big" }
    Imaging.decode(bytes).close() // must be a real picture
    Files.createDirectories(folder)
    val partial = target.resolveSibling(target.fileName.toString() + ".part")
    Files.write(partial, bytes)
    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    log.info("Downloaded ${wallpaper.pageUrl} to $target")
    return target
}
