package wpm.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.Page
import wpm.data.MediaItem
import wpm.data.MediaStats
import wpm.services.Occurrence
import wpm.services.PauseReason
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.MediaThumbnail
import wpm.ui.components.MediaTile
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.ui.rememberPreview
import java.nio.file.Path
import java.time.LocalTime

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

private data class HomeInfo(
    val stats: MediaStats? = null,
    val current: MediaItem? = null,
    val kind: String? = null,
    val picture: Path? = null,
    val videoActive: Boolean = false,
    val videoPaused: Boolean = false,
    val autoReason: PauseReason? = null,
    val playlist: String? = null,
    val playlistCountdown: Double? = null,
    val upcoming: Occurrence? = null,
    val recent: List<MediaItem> = emptyList(),
)

@Composable
fun HomePage(app: AppState) {
    var info by remember { mutableStateOf(HomeInfo()) }
    LaunchedEffect(app.libraryVersion, app.wallpaperVersion) {
        info = withContext(Dispatchers.IO) {
            val w = app.wallpapers
            HomeInfo(
                stats = app.db.mediaStats(),
                current = w.currentItem(),
                kind = w.state.kind,
                picture = w.currentPicture(),
                videoActive = w.videoActive,
                videoPaused = w.videoPaused,
                autoReason = w.autoPauseReason,
                playlist = app.playlists.activePlaylist()?.name,
                playlistCountdown = app.playlists.secondsUntilChange(),
                upcoming = if (app.scheduler.enabled) app.scheduler.upcoming() else null,
                recent = app.db.listMedia(sort = "newest", limit = 6),
            )
        }
    }

    PageScaffold(greeting(), "Here's your wallpaper collection.") {
        Card(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(400.dp).height(225.dp).clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg)) {
                    val item = info.current
                    if (info.kind == "video" && item != null) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    } else {
                        rememberPreview(info.picture, 1000, app.wallpaperVersion)?.let {
                            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MutedText("Current wallpaper")
                    Text(info.current?.filename ?: "Set outside this app", color = Theme.colors.text, fontSize = 18.sp)
                    if (info.kind == "video") {
                        MutedText(
                            when {
                                !info.videoActive -> "Live wallpaper (not running)"
                                info.autoReason != null -> "Live wallpaper · paused because ${info.autoReason!!.text}"
                                info.videoPaused -> "Live wallpaper · paused"
                                else -> "Live wallpaper · playing"
                            },
                        )
                    }
                    info.playlist?.let { name ->
                        Text("Playlist “$name” · next change ${info.playlistCountdown?.let(::formatCountdown) ?: "soon"}", color = Theme.colors.accent)
                    }
                    info.upcoming?.let { MutedText("Schedule: ${it.rule.targetName} at ${it.rule.time}") }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = app::nextWallpaper) { Icon(Icons.Outlined.SkipNext, null); Text("  Next") }
                        OutlinedButton(onClick = app::randomWallpaper) { Icon(Icons.Outlined.Shuffle, null); Text("  Random") }
                        if (info.videoActive) {
                            OutlinedButton(onClick = {
                                app.videoAction { if (app.wallpapers.videoPaused) app.wallpapers.resumeVideo() else app.wallpapers.pauseVideo() }
                            }) {
                                Icon(if (info.videoPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
                                Text(if (info.videoPaused) "  Resume" else "  Pause")
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val s = info.stats
            StatCard("Wallpapers", s?.total, Modifier.weight(1f))
            StatCard("Images", s?.images, Modifier.weight(1f))
            StatCard("Videos", s?.videos, Modifier.weight(1f))
            StatCard("Favorites", s?.favorites, Modifier.weight(1f))
        }

        if (info.recent.isNotEmpty()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SectionTitle("Recently added")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { app.page = Page.Library }) { Text("Open library") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                info.recent.forEach { item ->
                    Box(Modifier.weight(1f)) { MediaTile(item, app.thumbs, selected = false, onClick = { app.setDesktop(item) }) }
                }
                repeat(6 - info.recent.size) { Spacer(Modifier.weight(1f)) }
            }
        } else if (info.stats?.total == 0) {
            Card(Modifier.fillMaxWidth()) {
                SectionTitle("Welcome!")
                Spacer(Modifier.height(6.dp))
                MutedText("Start by importing pictures and videos on the Library page, or drag them onto this window.")
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int?, modifier: Modifier) {
    Card(modifier) {
        Text(value?.toString() ?: "–", color = Theme.colors.text, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        MutedText(label)
    }
}
