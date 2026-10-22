package wpm.services

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where the user is, for sunrise and sunset (degrees; north and east positive). */
@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double)

/**
 * Sunrise and sunset from NOAA's solar equations (accurate to a minute or two, no network).
 * Returns null when the sun doesn't rise or set that day (polar day or night).
 */
object Sun {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    fun sunrise(date: LocalDate, place: Place, zone: ZoneId): ZonedDateTime? = event(date, place, zone, rising = true)

    fun sunset(date: LocalDate, place: Place, zone: ZoneId): ZonedDateTime? = event(date, place, zone, rising = false)

    private fun event(date: LocalDate, place: Place, zone: ZoneId, rising: Boolean): ZonedDateTime? {
        val gamma = 2 * PI / 365 * (date.dayOfYear - 1) // fractional year, at noon
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = rad(place.latitude)
        // 90.833°: the sun's upper edge, with refraction.
        val cosHa = cos(rad(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = deg(acos(cosHa))
        val utcMinutes = 720 - 4 * (place.longitude + if (rising) ha else -ha) - eqTime
        return date.atStartOfDay(ZoneOffset.UTC).plusSeconds(Math.round(utcMinutes * 60)).withZoneSameInstant(zone)
    }
}
