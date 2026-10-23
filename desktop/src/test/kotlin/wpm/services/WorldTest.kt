package wpm.services

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldTest {
    private val berlin = Place("Berlin", 52.52, 13.40)

    private fun near(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected ± $tolerance, got $actual")

    @Test
    fun sunPositionAtNoonAndMidnight() {
        // Midsummer in Berlin: highest at about 13:13 CEST, 90 - 52.52 + 23.44 = 60.9° high, due south.
        val noon = Sun.position(ZonedDateTime.of(2026, 6, 21, 13, 13, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(), berlin)
        near(60.9, noon.elevation, 0.5, "elevation")
        near(180.0, noon.azimuth, 3.0, "azimuth")
        val midnight = Sun.position(ZonedDateTime.of(2026, 6, 22, 1, 13, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(), berlin)
        assertTrue(midnight.elevation < 0, "below the horizon at night")
        // Southern hemisphere: the noon sun is in the north.
        val sydney = Sun.position(ZonedDateTime.of(2026, 12, 21, 12, 55, 0, 0, ZoneId.of("Australia/Sydney")).toInstant(), Place("Sydney", -33.87, 151.21))
        assertTrue(sydney.azimuth < 20 || sydney.azimuth > 340, "azimuth ${sydney.azimuth}")
        near(79.6, sydney.elevation, 1.0, "Sydney elevation")
    }

    /** At sunrise and sunset the sun's center is 0.833° below the horizon (refraction and its radius). */
    @Test
    fun sunPositionAgreesWithSunrise() {
        val zone = ZoneId.of("Europe/Berlin")
        for (date in listOf(java.time.LocalDate.of(2026, 3, 20), java.time.LocalDate.of(2026, 6, 21), java.time.LocalDate.of(2026, 12, 21))) {
            val rise = Sun.position(Sun.sunrise(date, berlin, zone)!!.toInstant(), berlin)
            val set = Sun.position(Sun.sunset(date, berlin, zone)!!.toInstant(), berlin)
            near(-0.833, rise.elevation, 0.3, "sunrise $date")
            near(-0.833, set.elevation, 0.3, "sunset $date")
            assertTrue(rise.azimuth in 30.0..150.0, "rises in the east: ${rise.azimuth}")
            assertTrue(set.azimuth in 210.0..330.0, "sets in the west: ${set.azimuth}")
        }
    }

    @Test
    fun weatherCodesBecomeRainSnowFogAndStorms() {
        assertEquals(Weather(clouds = 0.2), weatherFromCode(1, cloudCover = 20.0))
        assertEquals(0.8, weatherFromCode(45).fog)
        val drizzle = weatherFromCode(51)
        val heavy = weatherFromCode(65, precipitation = 8.0)
        assertTrue(drizzle.rain in 0.1..0.3 && heavy.rain > 0.9 && weatherFromCode(65).rain in 0.65..0.75 && heavy.clouds >= 0.8, "$drizzle / $heavy")
        assertTrue(weatherFromCode(75).snow > weatherFromCode(71).snow)
        assertEquals(0.0, weatherFromCode(73).rain)
        val storm = weatherFromCode(99)
        assertEquals(1.0, storm.storm)
        assertTrue(storm.rain >= 0.9)
    }

    @Test
    fun readsOpenMeteosAnswer() {
        val json = """{"latitude":52.52,"longitude":13.42,"current_units":{"weather_code":"wmo code"},
            "current":{"time":"2026-10-23T09:00","interval":900,"weather_code":63,"cloud_cover":100,"precipitation":1.2}}"""
        val weather = OpenMeteo.parse(json)
        assertEquals(1.0, weather.clouds)
        assertTrue(weather.rain in 0.6..0.75, "$weather") // moderate rain
    }

    @Test
    fun weatherIsLookedUpAtMostEveryFifteenMinutes() {
        var now = Instant.parse("2026-10-23T09:00:00Z")
        var calls = 0
        var answer = weatherFromCode(61)
        val world = World({ berlin }, { now }, { calls++; answer })
        assertTrue(world.refreshWeather())
        assertEquals(answer, world.weather)
        now = now.plusSeconds(10 * 60)
        answer = weatherFromCode(95)
        assertTrue(!world.refreshWeather() && calls == 1, "cached")
        now = now.plusSeconds(6 * 60)
        assertTrue(world.refreshWeather())
        assertEquals(1.0 * 0.7, world.weather.storm)
        // A failure keeps the last weather.
        now = now.plusSeconds(20 * 60)
        val failing = World({ berlin }, { now }, { throw java.io.IOException("offline") })
        assertTrue(!failing.refreshWeather())
        assertEquals(Weather(), failing.weather)
        // Without a city nothing is looked up.
        assertTrue(!World({ null }, { now }, { error("not called") }).refreshWeather())
    }

    /** Asks open-meteo.com for Berlin's weather. Opt-in: WPM_NETWORK_TEST=1. */
    @Test
    fun realWeather() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val weather = OpenMeteo().current(berlin)
        assertTrue(listOf(weather.rain, weather.snow, weather.fog, weather.storm, weather.clouds).all { it in 0.0..1.0 }, "$weather")
    }

    @Test
    fun scriptCarriesTheSkyToThePage() {
        val instant = Instant.parse("2026-06-21T11:13:00Z")
        val world = World({ berlin }, { instant })
        world.weather = Weather(rain = 0.5, storm = 1.0, clouds = 0.8)
        val script = world.script()
        assertTrue(script.startsWith("window.wpm&&window.wpm.setWorld&&window.wpm.setWorld({sun:[60.9"), script)
        assertTrue("weather:[0.500,0.000,0.000,1.000],clouds:0.800" in script, script)
        assertEquals("window.wpm&&window.wpm.setWorld&&window.wpm.setWorld({sun:null,weather:[0.000,0.000,0.000,0.000],clouds:0.000});",
            World({ null }).script())
    }
}
