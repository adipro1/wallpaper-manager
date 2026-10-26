package wpm.services

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SkyTest {
    private fun at(text: String) = Instant.parse(text)
    private fun SkyBodies.planet(name: String) = planets.first { it.name == name }
    private fun opposite(b: SkyBody) = (b.ra + 180) % 360 to -b.dec

    @Test
    fun moonPhases() {
        val full = Sky.bodies(at("2024-01-25T17:54:00Z"))
        assertTrue(full.moonLit > 0.98, "full moon lit ${full.moonLit}")
        assertTrue(abs(full.moonAge - 14.77) < 1.0, "full moon age ${full.moonAge}")
        val new = Sky.bodies(at("2024-01-11T11:57:00Z"))
        assertTrue(new.moonLit < 0.02, "new moon lit ${new.moonLit}")
        assertTrue(new.moonAge < 1.0 || new.moonAge > 28.5, "new moon age ${new.moonAge}")
        val quarter = Sky.bodies(at("2024-01-18T03:52:00Z")) // first quarter
        assertTrue(abs(quarter.moonLit - 0.5) < 0.05, "first quarter lit ${quarter.moonLit}")
    }

    @Test
    fun eclipsesLineUpTheSunAndTheMoon() {
        val lunar = Sky.bodies(at("2025-03-14T06:58:00Z"))
        val (ra, dec) = opposite(lunar.sun)
        assertTrue(Sky.separation(lunar.moon.ra, lunar.moon.dec, ra, dec) < 0.6, "the moon is in the Earth's shadow")
        val solar = Sky.bodies(at("2024-04-08T18:17:00Z"))
        assertTrue(Sky.separation(solar.moon.ra, solar.moon.dec, solar.sun.ra, solar.sun.dec) < 1.0, "the moon is in front of the sun")
    }

    @Test
    fun planetsAtOppositionAndGreatestElongation() {
        fun elongation(time: String, name: String): Double {
            val sky = Sky.bodies(at(time))
            val p = sky.planet(name)
            return Sky.separation(p.ra, p.dec, sky.sun.ra, sky.sun.dec)
        }
        assertTrue(elongation("2023-11-03T05:00:00Z", "Jupiter") > 177, "Jupiter at opposition")
        assertTrue(elongation("2024-09-08T04:00:00Z", "Saturn") > 176, "Saturn at opposition")
        assertTrue(elongation("2025-01-16T02:00:00Z", "Mars") > 170, "Mars at opposition")
        assertTrue(abs(elongation("2025-01-10T03:00:00Z", "Venus") - 47.2) < 1.0, "Venus at greatest elongation")
        assertTrue(abs(elongation("2024-12-25T02:00:00Z", "Mercury") - 22.0) < 1.0, "Mercury at greatest elongation")
        val jupiter = Sky.bodies(at("2023-11-03T05:00:00Z")).planet("Jupiter").magnitude
        assertTrue(jupiter in -3.2..-2.7, "Jupiter's brightness $jupiter")
        val venus = Sky.bodies(at("2025-01-10T03:00:00Z")).planet("Venus").magnitude
        assertTrue(venus in -4.8..-4.2, "Venus's brightness $venus")
    }

    @Test
    fun horizonCoordinates() {
        val berlin = Place("Berlin", 52.52, 13.40)
        val sydney = Place("Sydney", -33.87, 151.21)
        val now = at("2024-06-01T22:00:00Z")
        // Polaris stands as high as the latitude, always due north.
        val (alt, az) = Sky.altAz(37.95, 89.264, berlin, now)
        assertTrue(abs(alt - 52.52) < 1.0, "Polaris altitude $alt")
        assertTrue(az < 2 || az > 358, "Polaris azimuth $az")
        assertTrue(Sky.altAz(37.95, 89.264, sydney, now).first < 0, "Polaris is below Sydney's horizon")
        // The sun agrees with the sunrise/sunset equations.
        for (time in listOf("2024-06-01T10:00:00Z", "2024-12-21T15:30:00Z", "2025-03-20T06:00:00Z")) {
            val sun = Sky.bodies(at(time)).sun
            val (sunAlt, sunAz) = Sky.altAz(sun.ra, sun.dec, berlin, at(time))
            val expected = Sun.position(at(time), berlin)
            assertTrue(abs(sunAlt - expected.elevation) < 0.7, "sun altitude $sunAlt vs ${expected.elevation} at $time")
            assertTrue(abs(sunAz - expected.azimuth) < 1.0, "sun azimuth $sunAz vs ${expected.azimuth} at $time")
        }
    }
}
