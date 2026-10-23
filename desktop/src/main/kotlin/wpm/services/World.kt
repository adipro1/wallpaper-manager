package wpm.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.APP_VERSION
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** The weather outside, each 0..1, for shaders (iWeather = rain, snow, fog, storm; iClouds). */
data class Weather(
    val rain: Double = 0.0,
    val snow: Double = 0.0,
    val fog: Double = 0.0,
    val storm: Double = 0.0,
    val clouds: Double = 0.0,
    /** Degrees Celsius, for the weather widget. */
    val temperature: Double? = null,
    /** The WMO weather code it was made from. */
    val code: Int? = null,
)

/**
 * The weather for a WMO weather code (as Open-Meteo reports it), [cloudCover] in percent and the
 * [precipitation] of the last hour in millimeters (heavier rain or snow makes more drops).
 */
fun weatherFromCode(code: Int, cloudCover: Double = 0.0, precipitation: Double = 0.0): Weather {
    val clouds = (cloudCover / 100).coerceIn(0.0, 1.0)
    val heavier = (precipitation / 10).coerceIn(0.0, 0.25)
    fun rain(amount: Double) = Weather(rain = (amount + heavier).coerceAtMost(1.0), clouds = maxOf(clouds, 0.8))
    fun snow(amount: Double) = Weather(snow = (amount + heavier).coerceAtMost(1.0), clouds = maxOf(clouds, 0.8))
    return when (code) {
        45, 48 -> Weather(fog = 0.8, clouds = maxOf(clouds, 0.6))
        51, 53, 55 -> rain(0.15 + (code - 51) / 2 * 0.05)     // drizzle: light, moderate, dense
        56, 57 -> rain(0.3)                                    // freezing drizzle
        61, 63, 65 -> rain(0.4 + (code - 61) / 2 * 0.15)       // rain: slight, moderate, heavy
        66, 67 -> rain(0.55)                                   // freezing rain
        71, 73, 75 -> snow(0.35 + (code - 71) / 2 * 0.15)      // snow: slight, moderate, heavy
        77 -> snow(0.25)                                       // snow grains
        80, 81, 82 -> rain(0.5 + (code - 80) * 0.2)            // showers
        85, 86 -> snow(0.6 + (code - 85) * 0.3)                // snow showers
        95 -> rain(0.75).copy(storm = 0.7)                     // thunderstorm
        96, 99 -> rain(0.9).copy(storm = 1.0)                  // thunderstorm with hail
        else -> Weather(clouds = clouds)                       // clear to overcast
    }
}

/** Where the current weather comes from. An interface so tests don't use the network. */
fun interface WeatherSource {
    fun current(place: Place): Weather
}

/** open-meteo.com: free, no account. Only the city's coordinates are sent. */
class OpenMeteo(private val client: HttpClient = HttpClient.newHttpClient()) : WeatherSource {
    override fun current(place: Place): Weather {
        val url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=weather_code,cloud_cover,precipitation,temperature_2m", place.latitude, place.longitude)
        val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "WallpaperManager/$APP_VERSION")
            .timeout(Duration.ofSeconds(15)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("open-meteo.com answered ${response.statusCode()}")
        return parse(response.body())
    }

    companion object {
        fun parse(json: String): Weather {
            val current = Json.parseToJsonElement(json).jsonObject["current"]?.jsonObject ?: throw IOException("No current weather")
            fun number(key: String) = current[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val code = current["weather_code"]?.jsonPrimitive?.intOrNull ?: throw IOException("No weather code")
            return weatherFromCode(code, number("cloud_cover"), number("precipitation"))
                .copy(temperature = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull, code = code)
        }
    }
}

/**
 * What the sky is doing where the user is: the sun's position (worked out on the PC) and the
 * weather. Shader pages get it through `wpm.setWorld(...)`, which sets their iSun, iWeather
 * and iClouds uniforms.
 */
class World(
    private val place: () -> Place?,
    private val clock: () -> Instant = Instant::now,
    private val source: WeatherSource = OpenMeteo(),
) {
    @Volatile var weather: Weather = Weather()
    @Volatile private var fetched: Instant? = null

    /**
     * Gets the weather again when it is older than 15 minutes (blocking; call it off the UI thread).
     * Returns true when it changed. Failures keep the last weather.
     */
    fun refreshWeather(): Boolean {
        val now = clock()
        if (fetched?.let { Duration.between(it, now) < Duration.ofMinutes(15) } == true) return false
        val where = place() ?: return false
        fetched = now
        val fresh = runCatching { source.current(where) }
            .onFailure { wpm.logger("world").info("No weather for ${where.name}: $it") }
            .getOrNull() ?: return false
        val changed = fresh != weather
        weather = fresh
        return changed
    }

    /** The sun's position now; null without a place (the page then estimates it from the clock). */
    fun sun(): SunPosition? = place()?.let { Sun.position(clock(), it) }

    /** JavaScript that hands the current sky to a shader page (does nothing on other pages). */
    fun script(): String {
        val sun = sun()?.let { "[${n(it.elevation)},${n(it.azimuth)}]" } ?: "null"
        val w = weather
        return "window.wpm&&window.wpm.setWorld&&window.wpm.setWorld({sun:$sun," +
            "weather:[${n(w.rain)},${n(w.snow)},${n(w.fog)},${n(w.storm)}],clouds:${n(w.clouds)}});"
    }

    private fun n(value: Double) = String.format(Locale.ROOT, "%.3f", value)
}
