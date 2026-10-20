package wpm.mpv

import org.jetbrains.skia.Image
import wpm.media.Imaging
import wpm.media.MediaInfo
import wpm.media.VideoReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.listDirectoryEntries

/**
 * Video metadata and single frames from a short-lived, window-less mpv instance.
 * (Replaces PyAV from version 1.x: one media engine for everything.)
 */
class MpvVideoReader(private val tempDir: Path) : VideoReader {
    private val timeoutMs = 20_000L

    private fun headless(extra: Map<String, String>): MpvPlayer = MpvPlayer(
        mapOf(
            "aid" to "no", "sid" to "no", "audio-display" to "no",
            "hwdec" to "no", "terminal" to "no", "config" to "no", "ytdl" to "no",
            "idle" to "yes", "keep-open" to "no", // idle: never quit before loadfile arrives; closed by us
        ) + extra,
    )

    /** Waits for one of [ids]; throws if the file ends with an error first. */
    private fun MpvPlayer.await(vararg ids: Int): MpvEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val event = waitEvent(0.5)
            if (event.id == Mpv.EVENT_LOG_MESSAGE) logMessage(event)
            if (event.id in ids) return event
            if (event.id == Mpv.EVENT_END_FILE && event.endReason == Mpv.END_ERROR) {
                throw IllegalArgumentException("not a valid video file (${errorString(event.endError)})")
            }
            if (event.id == Mpv.EVENT_SHUTDOWN) throw IllegalStateException("mpv stopped unexpectedly")
        }
        throw IllegalStateException("Reading the video took too long")
    }

    override fun probe(path: Path): MediaInfo = headless(mapOf("vo" to "null", "pause" to "yes")).use { mpv ->
        if (!Files.isRegularFile(path)) throw java.nio.file.NoSuchFileException(path.toString())
        mpv.command("loadfile", path.absolutePathString())
        mpv.await(Mpv.EVENT_FILE_LOADED)
        if (mpv["current-tracks/video/id"] == null) throw IllegalArgumentException("File has no video stream")
        // The decoded size (with rotation) arrives with the first frame; the container's size is a fallback.
        val reconfigured = runCatching { mpv.await(Mpv.EVENT_VIDEO_RECONFIG) }.isSuccess
        val width = (if (reconfigured) mpv["video-params/dw"] else null) ?: mpv["current-tracks/video/demux-w"]
        val height = (if (reconfigured) mpv["video-params/dh"] else null) ?: mpv["current-tracks/video/demux-h"]
        MediaInfo("video", width?.toIntOrNull(), height?.toIntOrNull(), mpv["duration"]?.toDoubleOrNull())
    }

    override fun frame(path: Path, seconds: Double): Image {
        if (!Files.isRegularFile(path)) throw java.nio.file.NoSuchFileException(path.toString())
        Files.createDirectories(tempDir)
        val outDir = Files.createTempDirectory(tempDir, "frame")
        try {
            headless(
                mapOf(
                    "vo" to "image", "vo-image-format" to "png", "vo-image-outdir" to outDir.absolutePathString(),
                    "frames" to "1", "start" to "%.3f".format(java.util.Locale.ROOT, seconds), "hr-seek" to "yes",
                ),
            ).use { mpv ->
                mpv.command("loadfile", path.absolutePathString())
                mpv.await(Mpv.EVENT_END_FILE)
            }
            val png = outDir.listDirectoryEntries("*.png").minOrNull() ?: throw IllegalArgumentException("Could not decode any video frames")
            return Imaging.load(png)
        } finally {
            outDir.toFile().deleteRecursively()
        }
    }
}
