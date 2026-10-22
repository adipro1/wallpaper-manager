package wpm.services

import wpm.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.APP_VERSION
import wpm.data.Database
import wpm.data.Settings
import wpm.logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = logger("updates")

const val REPO = "adipro1/wallpaper-manager"
const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
private const val CHECK_INTERVAL_SECONDS = 24 * 3600

/** "v1.10.2" -> [1, 10, 2]; anything unparsable counts as 0. */
fun parseVersion(text: String): List<Int> =
    text.trim().trimStart('v', 'V').split('.').map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }

fun isNewer(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate)
    val b = parseVersion(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** A published release: its version and, if it has one, the installer. */
@kotlinx.serialization.Serializable
data class ReleaseInfo(
    val version: String,
    val setupUrl: String? = null,
    val setupSize: Long = 0,
    /** "sha256:<hex>", as GitHub reports it. */
    val digest: String? = null,
)

/** The latest published release, or null if there is none yet (404). */
fun fetchLatestRelease(): ReleaseInfo? {
    val request = HttpRequest.newBuilder(URI(LATEST_RELEASE_API))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "WallpaperManager/$APP_VERSION")
        .timeout(Duration.ofSeconds(8)).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 404) return null
    if (response.statusCode() != 200) throw IOException(tr("GitHub answered {0}", response.statusCode()))
    return parseRelease(response.body())
}

internal fun parseRelease(body: String): ReleaseInfo? {
    val json = Json.parseToJsonElement(body).jsonObject
    val version = json["tag_name"]?.jsonPrimitive?.contentOrNull?.trimStart('v', 'V') ?: return null
    val setup = (json["assets"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("-setup.exe", ignoreCase = true) == true }
    return ReleaseInfo(
        version,
        setupUrl = setup?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
        setupSize = setup?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
        digest = setup?.get("digest")?.jsonPrimitive?.contentOrNull,
    )
}

/**
 * Checks GitHub once a day for a newer release. Only reads public release information:
 * nothing is sent, downloaded or installed automatically.
 */
class UpdateChecker(
    private val db: Database,
    private val fetch: () -> ReleaseInfo? = ::fetchLatestRelease,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val current: String = APP_VERSION,
) {
    private val settings = Settings(db)

    val enabled: Boolean get() = settings.boolean("check_for_updates", true)

    /** Newer version found by the last check, if any (no network access). */
    fun availableVersion(): String? {
        val latest = runCatching { db.getSetting("latest_version")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return latest?.takeIf { isNewer(it, current) }
    }

    /** The newer release found by the last check, with its installer (no network access). */
    fun availableRelease(): ReleaseInfo? =
        settings.obj("latest_release", ReleaseInfo.serializer())?.takeIf { availableVersion() != null && it.version == availableVersion() }

    /** Asks GitHub at most once a day (or now with [force]). Returns the newer version, or null. */
    fun checkIfDue(force: Boolean = false): String? {
        if (!enabled && !force) return null
        val last = runCatching { db.getSetting("last_update_check")?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: 0.0
        if (force || clock() - last >= CHECK_INTERVAL_SECONDS) {
            val latest = try {
                fetch()
            } catch (e: Exception) { // offline etc.: automatic checks retry next time, a manual check reports it
                log.info("Update check failed: $e")
                if (force) throw e
                return availableVersion()
            }
            db.setSetting("last_update_check", JsonPrimitive(clock()))
            db.setSetting("latest_version", latest?.version?.let(::JsonPrimitive) ?: JsonNull)
            if (latest != null) settings.set("latest_release", ReleaseInfo.serializer(), latest)
            log.info("Update check: latest release is ${latest?.version} (running $current)")
        }
        return availableVersion()
    }
}
