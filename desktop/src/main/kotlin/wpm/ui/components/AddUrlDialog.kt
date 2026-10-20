package wpm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.ToastKind
import wpm.data.MediaItem
import wpm.logger
import wpm.media.DownloadCancelledException
import wpm.media.Imaging
import wpm.media.RemoteVideo
import wpm.media.parseVideoUrl
import wpm.ui.Theme
import wpm.win32.Monitors
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = logger("add-url")

private sealed interface UrlStep {
    data object Typing : UrlStep
    data object LookingUp : UrlStep
    data class Found(val video: RemoteVideo, val preview: ImageBitmap?) : UrlStep
    data class Downloading(val video: RemoteVideo, val progress: Double) : UrlStep
    data class Failed(val message: String) : UrlStep
}

/** Paste a link to a video (YouTube and other sites, or a plain .mp4/.webm/.m3u8 link), then stream it or save it. */
@Composable
fun AddUrlDialog(app: AppState, initialUrl: String = "", onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialUrl) }
    var step by remember { mutableStateOf<UrlStep>(UrlStep.Typing) }
    var cancelled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val url = parseVideoUrl(text)

    fun lookUp() {
        val link = url ?: return
        step = UrlStep.LookingUp
        scope.launch {
            step = try {
                val video = withContext(Dispatchers.IO) { app.urls.lookUp(link) }
                val preview = withContext(Dispatchers.IO) { video.thumbnailUrl?.let(::downloadPreview) }
                UrlStep.Found(video, preview)
            } catch (e: Exception) {
                log.info("Look-up failed for $link: $e")
                UrlStep.Failed(e.message ?: "Couldn't read this link")
            }
        }
    }

    fun finish(item: MediaItem?, message: String, setNow: Boolean) {
        app.libraryChanged()
        app.toast(message, ToastKind.Success)
        if (setNow && item != null) app.setDesktop(item)
        onDismiss()
    }

    fun stream(video: RemoteVideo, setNow: Boolean) = scope.launch {
        try {
            val id = withContext(Dispatchers.IO) { app.urls.addStream(video) }
            if (id == null) {
                step = UrlStep.Failed("This video is already in your library")
                return@launch
            }
            finish(withContext(Dispatchers.IO) { app.db.getMedia(id) }, "Added “${video.title}”", setNow)
        } catch (e: Exception) {
            step = UrlStep.Failed(e.message ?: "Couldn't add the video")
        }
    }

    fun download(video: RemoteVideo, setNow: Boolean) {
        cancelled = false
        step = UrlStep.Downloading(video, 0.0)
        scope.launch {
            try {
                val maxHeight = Monitors.all().maxOfOrNull { it.bounds.height } ?: 1080
                val id = withContext(Dispatchers.IO) {
                    app.urls.download(video, maxHeight, { p -> step = UrlStep.Downloading(video, p) }, { cancelled })
                }
                finish(id?.let { withContext(Dispatchers.IO) { app.db.getMedia(it) } }, "Saved “${video.title}” for offline use", setNow)
            } catch (e: DownloadCancelledException) {
                step = UrlStep.Typing
            } catch (e: Exception) {
                log.info("Download failed: $e")
                step = UrlStep.Failed(e.message ?: "The download failed")
            }
        }
    }

    LaunchedEffect(Unit) { if (url != null) lookUp() }

    AlertDialog(
        onDismissRequest = { if (step !is UrlStep.Downloading) onDismiss() },
        title = { Text("Add a video from the internet") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!app.urls.available) {
                    Text("Video links need yt-dlp.exe in ${app.ytDlp.exe.parent}.", color = Theme.colors.danger)
                }
                OutlinedTextField(
                    text, { text = it; if (step !is UrlStep.LookingUp && step !is UrlStep.Downloading) step = UrlStep.Typing },
                    label = { Text("Link (YouTube, Vimeo, or a direct .mp4 / .webm / .m3u8 link)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = step !is UrlStep.Downloading,
                    isError = text.isNotBlank() && url == null,
                )
                when (val s = step) {
                    UrlStep.Typing -> MutedText("Streamed videos play straight from the internet and need a connection. " +
                        "Saved videos are downloaded to ${app.urls.downloadDir} and work offline (without sound).")
                    UrlStep.LookingUp -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 3.dp)
                        MutedText("Looking up the video…")
                    }
                    is UrlStep.Found -> VideoSummary(s.video, s.preview)
                    is UrlStep.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoSummary(s.video, null)
                        LinearProgressIndicator({ s.progress.toFloat() }, Modifier.fillMaxWidth())
                        MutedText("Downloading… ${(s.progress * 100).toInt()}%")
                    }
                    is UrlStep.Failed -> Text(s.message, color = Theme.colors.danger)
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                is UrlStep.Found -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { download(s.video, setNow = false) }, enabled = !s.video.isLive) { Text("Save offline") }
                    OutlinedButton(onClick = { stream(s.video, setNow = false) }) { Text("Add stream") }
                    Button(onClick = { stream(s.video, setNow = true) }) { Text("Stream as wallpaper") }
                }
                is UrlStep.Downloading -> TextButton(onClick = { cancelled = true }) { Text("Cancel download") }
                else -> Button(onClick = ::lookUp, enabled = url != null && step !is UrlStep.LookingUp && app.urls.available) { Text("Look up") }
            }
        },
        dismissButton = {
            if (step !is UrlStep.Downloading) TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun VideoSummary(video: RemoteVideo, preview: ImageBitmap?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg)) {
            if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(video.title, color = Theme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                if (video.isLive) "Live stream" else video.duration?.let { d -> "%d:%02d".format(d.toInt() / 60, d.toInt() % 60) },
                if (video.width != null && video.height != null) "${video.width} × ${video.height}" else null,
            )
            MutedText(details.joinToString(" · ").ifEmpty { "Video" })
        }
    }
}

private fun downloadPreview(url: String): ImageBitmap? = runCatching {
    val bytes = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        .send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray()).body()
    Imaging.decode(bytes).use { img -> Imaging.contain(img, 480, 270).use { it.toComposeImageBitmap() } }
}.getOrNull()
