package wpm.services

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private val dir: Path = Files.createTempDirectory("wpm-update")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun readsTheInstallerFromTheRelease() {
        val release = parseRelease("""
            {"tag_name": "v2.1.0", "assets": [
              {"name": "WallpaperManager-2.1.0-win64.zip", "size": 10, "browser_download_url": "https://github.com/x/zip"},
              {"name": "WallpaperManager-2.1.0-setup.exe", "size": 1234, "digest": "sha256:abc",
               "browser_download_url": "https://github.com/adipro1/wallpaper-manager/releases/download/v2.1.0/WallpaperManager-2.1.0-setup.exe"}
            ]}
        """)!!
        assertEquals("2.1.0", release.version)
        assertEquals(1234, release.setupSize)
        assertEquals("sha256:abc", release.digest)
        assertTrue(release.setupUrl!!.endsWith("-setup.exe"))
        assertNull(parseRelease("""{"message": "Not Found"}"""))
    }

    @Test
    fun checksSizeAndDigest() {
        val file = dir.resolve("setup.exe")
        Files.writeString(file, "installer bytes")
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        assertTrue(Updater.verify(file, ReleaseInfo("2.1.0", setupSize = 15, digest = "sha256:$sha")))
        assertFalse(Updater.verify(file, ReleaseInfo("2.1.0", setupSize = 16, digest = "sha256:$sha")))
        assertFalse(Updater.verify(file, ReleaseInfo("2.1.0", setupSize = 15, digest = "sha256:" + "0".repeat(64))))
        // Only this project's release downloads.
        assertFailsWith<IllegalArgumentException> {
            Updater.download(ReleaseInfo("2.1.0", setupUrl = "https://example.com/evil-setup.exe"), dir)
        }
        assertFalse(Updater.canInstall) // tests don't run from an installed copy
    }

    /** Downloads the real 2.0.0 installer and checks it against GitHub's digest. Opt-in: WPM_NETWORK_TEST=1 (160 MB). */
    @Test
    fun downloadsARealRelease() {
        assumeTrue(System.getenv("WPM_NETWORK_TEST") == "1", "network test not requested")
        val body = java.net.URI("https://api.github.com/repos/$REPO/releases/tags/v2.0.0").toURL().readText()
        val release = parseRelease(body)!!
        var last = 0.0
        val file = Updater.download(release, dir) { last = it }
        assertEquals(1.0, last, 0.001)
        assertTrue(Updater.verify(file, release))
        assertEquals(release.setupSize, Files.size(file))
    }
}
