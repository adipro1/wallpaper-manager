package wpm.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wpm.data.Database
import wpm.logger
import wpm.media.MediaLibrary
import wpm.tr
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipFile
import kotlin.io.path.nameWithoutExtension

private val log = logger("dynamic")

/** Which picture of a "follow the sun" playlist to show, and until when. */
data class SunSlot(val index: Int, val until: Instant)

/**
 * Spreads [count] pictures over the real day: the first [dayCount] evenly from sunrise to sunset,
 * the others from sunset to the next sunrise. Without a [place] the day is 07:00-19:00; during polar
 * day or night the whole day is one or the other.
 */
fun sunSlot(now: Instant, place: Place?, zone: ZoneId, count: Int, dayCount: Int): SunSlot {
    require(count > 0) { "No pictures" }
    val days = dayCount.coerceIn(0, count)
    val today = now.atZone(zone).toLocalDate()
    fun rise(offset: Long) = place?.let { Sun.sunrise(today.plusDays(offset), it, zone)?.toInstant() }
        ?: today.plusDays(offset).atTime(LocalTime.of(7, 0)).atZone(zone).toInstant().takeIf { place == null }
    fun set(offset: Long) = place?.let { Sun.sunset(today.plusDays(offset), it, zone)?.toInstant() }
        ?: today.plusDays(offset).atTime(LocalTime.of(19, 0)).atZone(zone).toInstant().takeIf { place == null }

    val sunrise = rise(0)
    val sunset = set(0)
    // The part of the day we are in: its start, end, and whether it is daytime.
    val (start, end, daytime) = if (sunrise != null && sunset != null) {
        when {
            now < sunrise -> Triple(set(-1) ?: sunrise.minus(Duration.ofHours(12)), sunrise, false)
            now < sunset -> Triple(sunrise, sunset, true)
            else -> Triple(sunset, rise(1) ?: sunset.plus(Duration.ofHours(12)), false)
        }
    } else {
        // Polar day or night: the sun stays up (or down) all day.
        val midnight = today.atStartOfDay(zone).toInstant()
        val up = Sun.position(midnight.plus(Duration.ofHours(12)), place!!).elevation > 0
        Triple(midnight, today.plusDays(1).atStartOfDay(zone).toInstant(), up)
    }
    // Pictures for this part; if there are none (all day or all night), keep the nearest one.
    val offset = if (daytime) 0 else days
    val n = if (daytime) days else count - days
    if (n == 0) return SunSlot(if (daytime) days else days - 1, end)
    val length = Duration.between(start, end).toMillis().coerceAtLeast(1)
    val slot = (Duration.between(start, now).toMillis() * n / length).toInt().coerceIn(0, n - 1)
    val until = start.plusMillis(length * (slot + 1) / n)
    return SunSlot(offset + slot, if (until > now) until else now.plusSeconds(60))
}

/** How many of a "follow the sun" playlist's pictures are for the day (the rest are for the night). */
fun sunDayCountKey(playlistId: Long) = "sun_day_count_$playlistId"

/** A dynamic theme read from a WinDynamicDesktop .ddw file. */
data class DdwTheme(val name: String, val dayImages: List<String>, val nightImages: List<String>)

/**
 * WinDynamicDesktop themes (.ddw): a zip with a theme.json and the pictures. The theme names the
 * pictures with a pattern ("Mojave_*.jpg") and lists them by number for sunrise, day, sunset and night.
 */
object DynamicThemes {
    fun readTheme(json: String, fallbackName: String): DdwTheme {
        val o = Json.parseToJsonElement(json).jsonObject
        val pattern = o["imageFilename"]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("This theme names no pictures"))
        fun list(key: String) = (o[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.map { pattern.replace("*", it.toString()) } ?: emptyList()
        val day = (list("sunriseImageList") + list("dayImageList") + list("sunsetImageList")).distinct()
        val night = list("nightImageList").distinct() - day.toSet()
        if (day.isEmpty() && night.isEmpty()) throw IOException(tr("This theme has no pictures"))
        val name = o["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackName
        return DdwTheme(name, day, night)
    }

    /**
     * Unpacks a .ddw into [folder], adds its pictures to the library and creates a playlist that
     * follows the sun. Returns the playlist's id.
     */
    fun import(file: Path, folder: Path, db: Database, library: MediaLibrary): Long {
        val (theme, pictures) = ZipFile(file.toFile()).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            // Only file names count: pictures are written into our folder whatever paths the zip uses.
            fun entry(name: String) = entries.firstOrNull { it.name.substringAfterLast('/').equals(name, ignoreCase = true) }
            val json = entry("theme.json") ?: throw IOException(tr("{0} is not a dynamic theme (no theme.json)", file.fileName))
            val theme = readTheme(zip.getInputStream(json).use { it.readBytes().decodeToString() }, file.nameWithoutExtension)
            val target = folder.resolve(theme.name.replace(Regex("[^\\w -]"), "_").trim().ifEmpty { "theme" })
            Files.createDirectories(target)
            val pictures = (theme.dayImages + theme.nightImages).mapNotNull { name ->
                val found = entry(name) ?: return@mapNotNull null.also { log.warning("${file.fileName}: $name is missing") }
                val out = target.resolve(name.substringAfterLast('/'))
                zip.getInputStream(found).use { Files.copy(it, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                name to out
            }.toMap()
            theme to pictures
        }
        if (pictures.isEmpty()) throw IOException(tr("{0} has none of its pictures", file.fileName))
        library.importFiles(pictures.values.toList())
        val byPath = db.listMedia().associateBy { it.path }
        fun ids(names: List<String>) = names.mapNotNull { pictures[it]?.toAbsolutePath()?.normalize()?.toString()?.let(byPath::get)?.id }
        val day = ids(theme.dayImages)
        val night = ids(theme.nightImages)
        val playlist = db.createPlaylist(theme.name, "sun", 3600)
        db.addToPlaylist(playlist, day + night)
        wpm.data.Settings(db).set(sunDayCountKey(playlist), day.size.toString())
        log.info("Imported dynamic theme ${theme.name}: ${day.size} day and ${night.size} night pictures")
        return playlist
    }
}
