package wpm.services

import wpm.APP_VERSION
import wpm.logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

private val log = logger("updater")

/**
 * Installs a newer release with one click: downloads its setup.exe from this project's GitHub
 * releases, checks size and SHA-256, and runs it silently. The installer closes this app,
 * updates it and starts it again. Only for the installed app (a copy from the zip has no
 * uninstaller, so it is updated by downloading the zip again).
 */
object Updater {
    private const val RELEASES = "https://github.com/$REPO/releases/download/"

    /** The installed app's folder (with Inno Setup's uninstaller), or null for a zip copy or a development run. */
    fun installFolder(): Path? {
        val exe = System.getProperty("jpackage.app-path")?.let(::Path) ?: return null
        return exe.parent?.takeIf { it.resolve("unins000.exe").exists() }
    }

    val canInstall: Boolean get() = installFolder() != null

    /** Downloads the installer into [folder]; [progress] gets 0..1. Throws if anything doesn't match. */
    fun download(release: ReleaseInfo, folder: Path, progress: (Double) -> Unit = {}): Path {
        val url = release.setupUrl ?: throw IOException("Version ${release.version} has no installer")
        require(url.startsWith(RELEASES)) { "Unexpected download address: $url" }
        Files.createDirectories(folder)
        val target = folder.resolve(url.substringAfterLast('/'))
        if (target.exists() && verify(target, release)) return target
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
        val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "WallpaperManager/$APP_VERSION").build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("GitHub answered ${response.statusCode()}")
        val total = release.setupSize.takeIf { it > 0 } ?: response.headers().firstValueAsLong("Content-Length").orElse(0)
        val partial = folder.resolve(target.fileName.toString() + ".part")
        response.body().use { input ->
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) progress((done.toDouble() / total).coerceAtMost(1.0))
                }
            }
        }
        if (!verify(partial, release)) {
            Files.deleteIfExists(partial)
            throw IOException("The download doesn't match the release (size or checksum); nothing was installed")
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        log.info("Downloaded the ${release.version} installer to $target")
        return target
    }

    /** Size and (when GitHub reports one) SHA-256 match the release. */
    fun verify(file: Path, release: ReleaseInfo): Boolean {
        if (release.setupSize > 0 && file.fileSize() != release.setupSize) return false
        val expected = release.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase() ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    /** Starts the installer silently; it closes this app, updates it and starts it again. */
    fun launch(installer: Path) {
        log.info("Starting the installer $installer")
        ProcessBuilder(installer.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS").start()
    }
}
