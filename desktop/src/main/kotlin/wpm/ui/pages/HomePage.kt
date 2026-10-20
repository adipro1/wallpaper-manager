package wpm.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import wpm.ui.components.Adaptive
import wpm.ui.components.EmptyState
import wpm.ui.components.Eyebrow
import wpm.ui.components.PreviewFrame
import wpm.ui.components.Tag
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import wpm.ui.components.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import wpm.ui.components.OutlinedButton
import androidx.compose.material3.Text
import wpm.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import wpm.ui.components.ButtonIcon
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
        Card(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp), fold = 26.dp) {
            Adaptive(820.dp, gap = 28.dp, first = { m ->
                PreviewFrame(m.widthIn(max = 460.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                    val item = info.current
                    if (info.kind != null && info.kind != "image" && item != null) {
                        MediaThumbnail(item, app.thumbs, Modifier.fillMaxSize())
                    } else {
                        rememberPreview(info.picture, 1000, app.wallpaperVersion)?.let {
                            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }) { m ->
                Column(m.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("Now showing")
                    Text(
                        info.current?.filename ?: "Set outside this app", color = Theme.colors.text,
                        style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val live = info.kind != null && info.kind != "image"
                        when {
                            !live -> Tag("Picture", Theme.colors.indigo)
                            !info.videoActive -> Tag("Live · stopped", Theme.colors.textMuted)
                            info.videoPaused || info.autoReason != null -> Tag("Live · paused", Theme.colors.gold)
                            else -> Tag("Live · playing", Theme.colors.teal)
                        }
                        if (info.playlist != null) Tag("Playlist", Theme.colors.accent)
                    }
                    info.autoReason?.let { MutedText("Paused because ${it.text}") }
                    info.playlist?.let { name ->
                        IconLine(Icons.AutoMirrored.Outlined.PlaylistPlay,
                            "“$name” · next change ${info.playlistCountdown?.let(::formatCountdown) ?: "soon"}", Theme.colors.accent)
                    }
                    info.upcoming?.let { IconLine(Icons.Outlined.Schedule, "Schedule: ${it.rule.targetName} at ${it.rule.time}", Theme.colors.textMuted) }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = app::nextWallpaper) { ButtonIcon(Icons.Outlined.SkipNext); Text("Next") }
                        OutlinedButton(onClick = app::randomWallpaper) { ButtonIcon(Icons.Outlined.Shuffle); Text("Random") }
                        if (info.videoActive) {
                            OutlinedButton(onClick = {
                                app.videoAction { if (app.wallpapers.videoPaused) app.wallpapers.resumeVideo() else app.wallpapers.pauseVideo() }
                            }) {
                                ButtonIcon(if (info.videoPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause)
                                Text(if (info.videoPaused) "Resume" else "Pause")
                            }
                        }
                    }
                }
            }
        }

        val s = info.stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCard("Wallpapers", s?.total, Icons.Outlined.PhotoLibrary, Theme.colors.accent, Modifier.weight(1f))
            StatCard("Pictures", s?.images, Icons.Outlined.Image, Theme.colors.indigo, Modifier.weight(1f))
            StatCard("Videos", s?.videos, Icons.Outlined.Movie, Theme.colors.teal, Modifier.weight(1f))
            StatCard("Favorites", s?.favorites, Icons.Outlined.StarOutline, Theme.colors.gold, Modifier.weight(1f))
        }

        if (info.recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recently added")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { app.page = Page.Library }) { Text("Open library"); ButtonIcon(Icons.AutoMirrored.Outlined.ArrowForward) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                info.recent.forEach { item ->
                    Box(Modifier.weight(1f)) { MediaTile(item, app.thumbs, selected = false, onClick = { app.setDesktop(item) }) }
                }
                repeat(6 - info.recent.size) { Spacer(Modifier.weight(1f)) }
            }
        } else if (info.stats?.total == 0) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState("Welcome!", "Start by importing pictures and videos on the Library page, or drag them onto this window.") {
                    Button(onClick = { app.page = Page.Library }) { ButtonIcon(Icons.Outlined.PhotoLibrary); Text("Open the library") }
                }
            }
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A folded tile with one big number; each tile has its own paper colour. */
@Composable
private fun StatCard(label: String, value: Int?, icon: ImageVector, tone: Color, modifier: Modifier) {
    Card(modifier, padding = PaddingValues(start = 22.dp, end = 18.dp, top = 18.dp, bottom = 18.dp), accent = tone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(value?.toString() ?: "–", color = Theme.colors.text, style = MaterialTheme.typography.displaySmall)
                Text(label.uppercase(), color = Theme.colors.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.size(40.dp).rotate(45f).background(tone.copy(alpha = 0.14f), CutCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp).rotate(-45f))
            }
        }
    }
}
