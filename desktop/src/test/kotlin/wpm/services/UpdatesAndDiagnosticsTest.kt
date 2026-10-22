package wpm.services

import wpm.AppPaths
import wpm.data.Database
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesAndDiagnosticsTest {
    private val dir = Files.createTempDirectory("wpm-upd")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private var now = 1_000_000.0
    private var latest: String? = "2.1.0"
    private var calls = 0
    private var failing = false
    private val checker = UpdateChecker(db, fetch = {
        calls++
        if (failing) throw IOException("offline")
        latest?.let { ReleaseInfo(it) }
    }, clock = { now }, current = "2.0.0")

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun comparesVersions() {
        assertEquals(listOf(1, 10, 2), parseVersion("v1.10.2"))
        assertTrue(isNewer("1.10.0", "1.9.9"))
        assertTrue(isNewer("2.0.1", "2.0"))
        assertFalse(isNewer("2.0", "2.0.0"))
        assertFalse(isNewer("v1.2.0-beta", "1.2.0"))
    }

    @Test
    fun checksOnceADay() {
        assertEquals("2.1.0", checker.checkIfDue())
        assertEquals(1, calls)
        now += 3600
        assertEquals("2.1.0", checker.checkIfDue()) // remembered, no new request
        assertEquals(1, calls)
        now += 24 * 3600
        latest = "2.0.0"
        assertNull(checker.checkIfDue())
        assertEquals(2, calls)
    }

    @Test
    fun failuresAreSilentUnlessForced() {
        failing = true
        assertNull(checker.checkIfDue())
        assertFailsWith<IOException> { checker.checkIfDue(force = true) }
    }

    @Test
    fun noReleaseYetAndDisabled() {
        latest = null
        assertNull(checker.checkIfDue())
        wpm.data.Settings(db).set("check_for_updates", false)
        now += 2 * 24 * 3600
        latest = "9.0.0"
        assertNull(checker.checkIfDue())
        assertEquals(1, calls)
    }

    @Test
    fun diagnosticsZipContainsReportAndLogs() {
        paths.logDir.resolve("wallpaper_manager.0.log").writeText("hello log")
        val service = WallpaperService(db, paths, FakeDesktop(), FakeLivePlayer())
        val zip = saveDiagnostics(dir.resolve("diag.zip"), db, paths, service)
        ZipFile(zip.toFile()).use { file ->
            val names = file.entries().toList().map { it.name }
            assertTrue("report.txt" in names)
            assertTrue("logs/wallpaper_manager.0.log" in names)
            val report = file.getInputStream(file.getEntry("report.txt")).readBytes().decodeToString()
            assertTrue("== Settings ==" in report)
            assertTrue("== Desktop windows (Explorer) ==" in report)
        }
    }
}
