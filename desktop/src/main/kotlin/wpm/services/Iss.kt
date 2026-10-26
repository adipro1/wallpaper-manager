package wpm.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wpm.APP_VERSION
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Where the International Space Station was at [time]: the point on the Earth below it (degrees). */
data class IssPosition(val time: Instant, val latitude: Double, val longitude: Double)

/**
 * The space station's position, from wheretheiss.at (free, no account), for the Live Earth scene:
 * where it is now and where it was in the last 45 minutes (half an orbit).
 */
class IssTracker(private val get: (String) -> String = ::httpGet, private val clock: () -> Instant = Instant::now) {
    private var trail = listOf<IssPosition>()

    /** The positions to draw (oldest first): the trail, fetched once, then one more each call. Blocking. */
    @Synchronized
    fun update(): List<IssPosition> {
        val now = clock()
        if (trail.isEmpty() || Duration.between(trail.last().time, now) > Duration.ofMinutes(10)) {
            val times = (9 downTo 1).map { now.minusSeconds(it * 300L).epochSecond }
            trail = parse(get("$API/positions?timestamps=${times.joinToString(",")}&units=kilometers"))
        }
        trail = (trail + parse(get(API))).distinctBy { it.time }.sortedBy { it.time }
            .filter { Duration.between(it.time, now) <= Duration.ofMinutes(46) }
        return trail
    }

    companion object {
        private const val API = "https://api.wheretheiss.at/v1/satellites/25544"

        /** One position (an object) or several (an array). */
        fun parse(json: String): List<IssPosition> {
            fun one(e: JsonElement): IssPosition? {
                val o = e as? JsonObject ?: return null
                val time = o["timestamp"]?.jsonPrimitive?.longOrNull ?: return null
                val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
                val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
                return IssPosition(Instant.ofEpochSecond(time), lat, lon)
            }
            return when (val root = Json.parseToJsonElement(json)) {
                is JsonArray -> root.mapNotNull(::one)
                else -> listOfNotNull(one(root))
            }
        }

        /** JavaScript that hands the positions to the Earth page: [[epoch ms, latitude, longitude], ...]. */
        fun script(positions: List<IssPosition>): String =
            "window.wpm&&window.wpm.setIss&&window.wpm.setIss([" + positions.joinToString(",") {
                String.format(Locale.ROOT, "[%d,%.3f,%.3f]", it.time.toEpochMilli(), it.latitude, it.longitude)
            } + "]);"

        private fun httpGet(url: String): String {
            val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "WallpaperManager/$APP_VERSION")
                .timeout(Duration.ofSeconds(15)).build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) throw IOException("wheretheiss.at answered ${response.statusCode()}")
            return response.body()
        }
    }
}
