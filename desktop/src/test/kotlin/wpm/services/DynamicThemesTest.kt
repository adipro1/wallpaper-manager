package wpm.services

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import wpm.media.MediaLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DynamicThemesTest {
    private val dir: Path = Files.createTempDirectory("wpm-dynamic")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)
    private val zone = ZoneId.of("Europe/Berlin")
    private val berlin = Place("Berlin", 52.52, 13.40)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun picturesFollowTheSun() {
        // Midsummer in Berlin: sunrise 04:43, sunset 21:33. 4 day pictures, 2 night pictures.
        val early = sunSlot(at("2026-06-21T05:00"), berlin, zone, 6, 4)
        assertEquals(0, early.index)
        val noon = sunSlot(at("2026-06-21T13:10"), berlin, zone, 6, 4)
        assertEquals(2, noon.index) // the day is split in four: 04:43-08:55-13:08-17:20-21:33
        assertTrue(noon.until > at("2026-06-21T17:00") && noon.until < at("2026-06-21T17:40"), "${noon.until}")
        assertEquals(3, sunSlot(at("2026-06-21T21:00"), berlin, zone, 6, 4).index)
        assertEquals(4, sunSlot(at("2026-06-21T22:30"), berlin, zone, 6, 4).index)
        // After midnight it is still the same night, its second half.
        val late = sunSlot(at("2026-06-22T03:30"), berlin, zone, 6, 4)
        assertEquals(5, late.index)
        assertTrue(late.until > at("2026-06-22T04:30") && late.until < at("2026-06-22T05:00"), "next change at sunrise: ${late.until}")

        // Without a city: day from 07:00 to 19:00.
        assertEquals(2, sunSlot(at("2026-06-21T06:30"), null, zone, 3, 2).index)
        assertEquals(0, sunSlot(at("2026-06-21T08:00"), null, zone, 3, 2).index)
        // Only day pictures: at night the last one stays.
        assertEquals(1, sunSlot(at("2026-06-21T23:00"), null, zone, 2, 2).index)
        // Polar day in Tromsø: all day is daytime.
        val tromso = Place("Tromsø", 69.65, 18.96)
        assertTrue(sunSlot(at("2026-06-21T01:00"), tromso, zone, 4, 2).index < 2)
        assertTrue(sunSlot(at("2026-12-21T12:00"), tromso, zone, 4, 2).index >= 2)
    }

    @Test
    fun readsWinDynamicDesktopThemes() {
        val theme = DynamicThemes.readTheme("""{"displayName":"Lake","imageFilename":"lake_*.jpg",
            "sunriseImageList":[1],"dayImageList":[2,3],"sunsetImageList":[4],"nightImageList":[5,1]}""", "fallback")
        assertEquals("Lake", theme.name)
        assertEquals(listOf("lake_1.jpg", "lake_2.jpg", "lake_3.jpg", "lake_4.jpg"), theme.dayImages)
        assertEquals(listOf("lake_5.jpg"), theme.nightImages) // lake_1 is already a day picture
        assertEquals("fallback", DynamicThemes.readTheme("""{"imageFilename":"a*.png","dayImageList":[1]}""", "fallback").name)
        assertFailsWith<java.io.IOException> { DynamicThemes.readTheme("""{"dayImageList":[1]}""", "x") }
    }

    @Test
    fun importsADdwAsAPlaylistThatFollowsTheSun() {
        val pictures = (1..4).map { Samples.image(dir.resolve("src/desert_$it.png"), 64, 36) }
        val ddw = dir.resolve("Desert.ddw")
        ZipOutputStream(Files.newOutputStream(ddw)).use { zip ->
            zip.putNextEntry(ZipEntry("Desert/theme.json"))
            zip.write("""{"imageFilename":"desert_*.png","dayImageList":[1,2,3],"nightImageList":[4]}""".toByteArray())
            for (p in pictures) {
                zip.putNextEntry(ZipEntry("Desert/../../${p.fileName}")) // a hostile path: only the name is used
                zip.write(Files.readAllBytes(p))
            }
        }
        val id = DynamicThemes.import(ddw, paths.dataDir.resolve("dynamic"), db, library)
        val playlist = db.getPlaylist(id)!!
        assertEquals("Desert", playlist.name)
        assertEquals("sun", playlist.mode)
        val items = db.playlistItems(id)
        assertEquals(listOf("desert_1.png", "desert_2.png", "desert_3.png", "desert_4.png"), items.map { it.filename })
        assertTrue(items.all { it.path!!.startsWith(paths.dataDir.resolve("dynamic").toString()) }, "unpacked into the app's folder")
        val wallpapers = WallpaperService(db, paths, FakeDesktop(), FakeLivePlayer())
        assertEquals(3, PlaylistPlayer(db, wallpapers).dayCount(id, items.size))
    }

    @Test
    fun aSunPlaylistShowsThePictureForTheTimeOfDay() {
        val ids = (0..3).map { db.addMedia(Samples.image(dir.resolve("p$it.png")).toString(), "image", 64, 36, null, 5, null)!! }
        val playlist = db.createPlaylist("Sun", "sun", 3600)
        db.addToPlaylist(playlist, ids)
        val wallpapers = WallpaperService(db, paths, FakeDesktop(), FakeLivePlayer())
        var now = at("2026-06-21T13:10").epochSecond.toDouble()
        val player = PlaylistPlayer(db, wallpapers, { berlin }, { zone }) { now }
        player.setDayCount(playlist, 2)
        // Two day pictures: 04:43-13:08 and 13:08-21:33.
        assertEquals(ids[1], player.start(playlist).id)
        assertTrue(!player.isDue())
        now = at("2026-06-21T22:00").epochSecond.toDouble()
        assertTrue(player.isDue())
        assertEquals(ids[2], player.advance().id)
        assertEquals(ids[2], wallpapers.currentItem()?.id)
    }
}
