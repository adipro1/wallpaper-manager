package wpm.services

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** A body in the sky: right ascension and declination in degrees (of date), distance and brightness. */
data class SkyBody(
    val name: String,
    val ra: Double,
    val dec: Double,
    /** AU; Earth radii for the moon. */
    val distance: Double,
    /** Visual magnitude (smaller = brighter). */
    val magnitude: Double,
)

/** The moon and the planets one can see without a telescope, plus the sun. */
data class SkyBodies(
    val sun: SkyBody,
    val moon: SkyBody,
    /** 0 = new moon, 1 = full moon: the part of the disc that is lit. */
    val moonLit: Double,
    /** Days since the new moon (0..29.5). */
    val moonAge: Double,
    val planets: List<SkyBody>,
)

/**
 * Where the sun, moon and planets are, from mean orbital elements with the main perturbations
 * (Paul Schlyter, "How to compute planetary positions"): about a minute of arc for the moon and
 * the planets, plenty for a wallpaper, offline. Positions are geocentric; the page adds the moon's
 * parallax for the viewer's place.
 */
object Sky {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm(d: Double) = ((d % 360) + 360) % 360

    /** Days since 2000 Jan 0.0 UT, the epoch of the elements. */
    fun day(instant: Instant): Double = instant.toEpochMilli() / 86_400_000.0 + 2440587.5 - 2451543.5

    /** Greenwich mean sidereal time in degrees. */
    fun siderealTime(instant: Instant): Double = norm(280.46061837 + 360.98564736629 * (instant.toEpochMilli() / 86_400_000.0 + 2440587.5 - 2451545.0))

    /** Altitude and azimuth (degrees; azimuth 0 = north, 90 = east) of [ra]/[dec] from [place] at [instant]. */
    fun altAz(ra: Double, dec: Double, place: Place, instant: Instant): Pair<Double, Double> {
        val ha = rad(siderealTime(instant) + place.longitude - ra)
        val lat = rad(place.latitude)
        val d = rad(dec)
        val alt = asin((sin(lat) * sin(d) + cos(lat) * cos(d) * cos(ha)).coerceIn(-1.0, 1.0))
        val az = atan2(-cos(d) * sin(ha), sin(d) * cos(lat) - cos(d) * cos(ha) * sin(lat))
        return deg(alt) to norm(deg(az))
    }

    private class Elements(val n: Double, val i: Double, val w: Double, val a: Double, val e: Double, val m: Double)

    private class Planet(val name: String, val elements: (Double) -> Elements, val magnitude: (r: Double, distance: Double, phase: Double) -> Double)

    private val PLANETS = listOf(
        Planet("Mercury", { d -> Elements(48.3313 + 3.24587E-5 * d, 7.0047 + 5.00E-8 * d, 29.1241 + 1.01444E-5 * d, 0.387098, 0.205635 + 5.59E-10 * d, 168.6562 + 4.0923344368 * d) },
            { r, s, fv -> -0.36 + 5 * log10(r * s) + 0.027 * fv + 2.2E-13 * Math.pow(fv, 6.0) }),
        Planet("Venus", { d -> Elements(76.6799 + 2.46590E-5 * d, 3.3946 + 2.75E-8 * d, 54.8910 + 1.38374E-5 * d, 0.723330, 0.006773 - 1.302E-9 * d, 48.0052 + 1.6021302244 * d) },
            { r, s, fv -> -4.34 + 5 * log10(r * s) + 0.013 * fv + 4.2E-7 * fv * fv * fv }),
        Planet("Mars", { d -> Elements(49.5574 + 2.11081E-5 * d, 1.8497 - 1.78E-8 * d, 286.5016 + 2.92961E-5 * d, 1.523688, 0.093405 + 2.516E-9 * d, 18.6021 + 0.5240207766 * d) },
            { r, s, fv -> -1.51 + 5 * log10(r * s) + 0.016 * fv }),
        Planet("Jupiter", { d -> Elements(100.4542 + 2.76854E-5 * d, 1.3030 - 1.557E-7 * d, 273.8777 + 1.64505E-5 * d, 5.20256, 0.048498 + 4.469E-9 * d, 19.8950 + 0.0830853001 * d) },
            { r, s, fv -> -9.25 + 5 * log10(r * s) + 0.014 * fv }),
        Planet("Saturn", { d -> Elements(113.6634 + 2.38980E-5 * d, 2.4886 - 1.081E-7 * d, 339.3939 + 2.97661E-5 * d, 9.55475, 0.055546 - 9.499E-9 * d, 316.9670 + 0.0334442282 * d) },
            { r, s, fv -> -9.0 + 5 * log10(r * s) + 0.044 * fv }),
    )

    /** Solves Kepler's equation; returns the position in the orbit's plane (x towards perihelion) and the distance. */
    private fun orbit(e: Elements): Triple<Double, Double, Double> {
        val m = rad(norm(e.m))
        var ea = m + e.e * sin(m) * (1 + e.e * cos(m))
        repeat(8) { ea -= (ea - e.e * sin(ea) - m) / (1 - e.e * cos(ea)) }
        val x = e.a * (cos(ea) - e.e)
        val y = e.a * sqrt(1 - e.e * e.e) * sin(ea)
        return Triple(x, y, sqrt(x * x + y * y))
    }

    /** Ecliptic longitude, latitude (degrees) and distance of an orbit at [e]. */
    private fun ecliptic(e: Elements): Triple<Double, Double, Double> {
        val (x, y, r) = orbit(e)
        val v = atan2(y, x)
        val n = rad(e.n)
        val i = rad(e.i)
        val vw = v + rad(e.w)
        val xh = r * (cos(n) * cos(vw) - sin(n) * sin(vw) * cos(i))
        val yh = r * (sin(n) * cos(vw) + cos(n) * sin(vw) * cos(i))
        val zh = r * sin(vw) * sin(i)
        return Triple(norm(deg(atan2(yh, xh))), deg(atan2(zh, sqrt(xh * xh + yh * yh))), r)
    }

    /** Ecliptic (longitude, latitude, distance) to (ra, dec). */
    private fun equatorial(lon: Double, lat: Double, obliquity: Double): Pair<Double, Double> {
        val l = rad(lon)
        val b = rad(lat)
        val o = rad(obliquity)
        val x = cos(l) * cos(b)
        val y = sin(l) * cos(b) * cos(o) - sin(b) * sin(o)
        val z = sin(l) * cos(b) * sin(o) + sin(b) * cos(o)
        return norm(deg(atan2(y, x))) to deg(asin(z.coerceIn(-1.0, 1.0)))
    }

    fun bodies(instant: Instant): SkyBodies {
        val d = day(instant)
        val obliquity = 23.4393 - 3.563E-7 * d

        // The sun (Earth's orbit seen from the Earth).
        val sunElements = Elements(0.0, 0.0, 282.9404 + 4.70935E-5 * d, 1.0, 0.016709 - 1.151E-9 * d, 356.0470 + 0.9856002585 * d)
        val (sx, sy, sr) = orbit(sunElements)
        val sunLon = norm(deg(atan2(sy, sx)) + sunElements.w)
        val (sunRa, sunDec) = equatorial(sunLon, 0.0, obliquity)
        val sun = SkyBody("Sun", sunRa, sunDec, sr, -26.7)
        val sunX = sr * cos(rad(sunLon))
        val sunY = sr * sin(rad(sunLon))

        // The moon, with its largest perturbations.
        val moonElements = Elements(125.1228 - 0.0529538083 * d, 5.1454, 318.0634 + 0.1643573223 * d, 60.2666, 0.054900, 115.3654 + 13.0649929509 * d)
        var (moonLon, moonLat, moonR) = ecliptic(moonElements)
        val ms = rad(norm(sunElements.m))
        val mm = rad(norm(moonElements.m))
        val ls = norm(sunElements.m + sunElements.w)
        val lm = norm(moonElements.m + moonElements.w + moonElements.n)
        val dd = rad(norm(lm - ls))
        val f = rad(norm(lm - moonElements.n))
        moonLon += -1.274 * sin(mm - 2 * dd) + 0.658 * sin(2 * dd) - 0.186 * sin(ms) - 0.059 * sin(2 * mm - 2 * dd) -
            0.057 * sin(mm - 2 * dd + ms) + 0.053 * sin(mm + 2 * dd) + 0.046 * sin(2 * dd - ms) + 0.041 * sin(mm - ms) -
            0.035 * sin(dd) - 0.031 * sin(mm + ms) - 0.015 * sin(2 * f - 2 * dd) + 0.011 * sin(mm - 4 * dd)
        moonLat += -0.173 * sin(f - 2 * dd) - 0.055 * sin(mm - f - 2 * dd) - 0.046 * sin(mm + f - 2 * dd) +
            0.033 * sin(f + 2 * dd) + 0.017 * sin(2 * mm + f)
        moonR += -0.58 * cos(mm - 2 * dd) - 0.46 * cos(2 * dd)
        moonLon = norm(moonLon)
        val (moonRa, moonDec) = equatorial(moonLon, moonLat, obliquity)
        val elongation = deg(acos((cos(rad(sunLon - moonLon)) * cos(rad(moonLat))).coerceIn(-1.0, 1.0)))
        val lit = (1 - cos(rad(elongation))) / 2
        // The age follows the moon's lead over the sun in longitude (0..360° in one synodic month).
        val age = norm(moonLon - sunLon) / 360 * 29.530589
        val moon = SkyBody("Moon", moonRa, moonDec, moonR, -12.7 + 2.5 * log10(1 / lit.coerceAtLeast(0.002)).coerceAtMost(10.0))

        // The planets: heliocentric orbits seen from the Earth.
        val mj = norm(PLANETS[3].elements(d).m)
        val msat = norm(PLANETS[4].elements(d).m)
        val planets = PLANETS.map { planet ->
            val elements = planet.elements(d)
            var (lon, lat, r) = ecliptic(elements)
            when (planet.name) {
                "Jupiter" -> lon += -0.332 * sin(rad(2 * mj - 5 * msat - 67.6)) - 0.056 * sin(rad(2 * mj - 2 * msat + 21)) +
                    0.042 * sin(rad(3 * mj - 5 * msat + 21)) - 0.036 * sin(rad(mj - 2 * msat)) + 0.022 * cos(rad(mj - msat)) +
                    0.023 * sin(rad(2 * mj - 3 * msat + 52)) - 0.016 * sin(rad(mj - 5 * msat - 69))
                "Saturn" -> {
                    lon += 0.812 * sin(rad(2 * mj - 5 * msat - 67.6)) - 0.229 * cos(rad(2 * mj - 4 * msat - 2)) +
                        0.119 * sin(rad(mj - 2 * msat - 3)) + 0.046 * sin(rad(2 * mj - 6 * msat - 69)) + 0.014 * sin(rad(mj - 3 * msat + 32))
                    lat += -0.020 * cos(rad(2 * mj - 4 * msat - 2)) + 0.018 * sin(rad(2 * mj - 6 * msat - 49))
                }
            }
            val xh = r * cos(rad(lon)) * cos(rad(lat))
            val yh = r * sin(rad(lon)) * cos(rad(lat))
            val zh = r * sin(rad(lat))
            val xg = xh + sunX
            val yg = yh + sunY
            val distance = sqrt(xg * xg + yg * yg + zh * zh)
            val (ra, dec) = equatorial(norm(deg(atan2(yg, xg))), deg(atan2(zh, sqrt(xg * xg + yg * yg))), obliquity)
            val phase = deg(acos(((r * r + distance * distance - sr * sr) / (2 * r * distance)).coerceIn(-1.0, 1.0)))
            SkyBody(planet.name, ra, dec, distance, planet.magnitude(r, distance, phase))
        }
        return SkyBodies(sun, moon, lit, age, planets)
    }

    /** The angle between two directions in the sky, in degrees. */
    fun separation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val c = sin(rad(dec1)) * sin(rad(dec2)) + cos(rad(dec1)) * cos(rad(dec2)) * cos(rad(ra1 - ra2))
        return deg(acos(c.coerceIn(-1.0, 1.0))).let { if (abs(it) < 1e-9) 0.0 else it }
    }
}
