package wpm.services

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where the user is, for sunrise and sunset (degrees; north and east positive). */
@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Where the sun is in the sky: degrees above the horizon (negative at night) and compass direction (0 = north, 90 = east). */
data class SunPosition(val elevation: Double, val azimuth: Double)

/**
 * Sunrise, sunset and the sun's position from NOAA's solar equations (accurate to a minute or two, no network).
 * Returns null when the sun doesn't rise or set that day (polar day or night).
 */
object Sun {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    fun sunrise(date: LocalDate, place: Place, zone: ZoneId): ZonedDateTime? = event(date, place, zone, rising = true)

    fun sunset(date: LocalDate, place: Place, zone: ZoneId): ZonedDateTime? = event(date, place, zone, rising = false)

    /** The equation of time (minutes) and the sun's declination (radians) for a fractional year [gamma]. */
    private fun orbit(gamma: Double): Pair<Double, Double> {
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        return eqTime to decl
    }

    /** The sun's position at [instant] as seen from [place]. */
    fun position(instant: Instant, place: Place): SunPosition {
        val utc = instant.atZone(ZoneOffset.UTC)
        val hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val (eqTime, decl) = orbit(2 * PI / 365 * (utc.dayOfYear - 1 + (hours - 12) / 24))
        val trueSolarMinutes = hours * 60 + eqTime + 4 * place.longitude
        val hourAngle = rad(trueSolarMinutes / 4 - 180)
        val lat = rad(place.latitude)
        val sinElevation = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)
        val elevation = deg(asin(sinElevation.coerceIn(-1.0, 1.0)))
        val azimuth = (deg(atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(decl) * cos(lat))) + 180) % 360
        return SunPosition(elevation, azimuth)
    }

    private fun event(date: LocalDate, place: Place, zone: ZoneId, rising: Boolean): ZonedDateTime? {
        val (eqTime, decl) = orbit(2 * PI / 365 * (date.dayOfYear - 1)) // fractional year, at noon
        val lat = rad(place.latitude)
        // 90.833°: the sun's upper edge, with refraction.
        val cosHa = cos(rad(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = deg(acos(cosHa))
        val utcMinutes = 720 - 4 * (place.longitude + if (rising) ha else -ha) - eqTime
        return date.atStartOfDay(ZoneOffset.UTC).plusSeconds(Math.round(utcMinutes * 60)).withZoneSameInstant(zone)
    }
}
