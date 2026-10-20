package wpm.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaStats
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import java.time.LocalTime

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomePage(app: AppState) {
    var stats by remember { mutableStateOf<MediaStats?>(null) }
    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { app.db.mediaStats() }
    }
    PageScaffold(greeting(), "Here's your wallpaper collection.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val s = stats
            StatCard("Wallpapers", s?.total, Modifier.weight(1f))
            StatCard("Images", s?.images, Modifier.weight(1f))
            StatCard("Videos", s?.videos, Modifier.weight(1f))
            StatCard("Favorites", s?.favorites, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int?, modifier: Modifier) {
    Card(modifier) {
        Text(value?.toString() ?: "–", fontSize = 28.sp, color = Theme.colors.text, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        MutedText(label)
    }
}
