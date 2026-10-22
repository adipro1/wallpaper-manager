package wpm.ui.pages

import wpm.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import wpm.ui.components.EmptyState
import wpm.ui.components.Tag
import wpm.ui.components.foldedShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import wpm.ui.components.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import wpm.ui.components.IconButton
import wpm.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import wpm.ui.components.SegmentedButton
import wpm.ui.components.SegmentedButtonDefaults
import wpm.ui.components.SingleChoiceSegmentedButtonRow
import wpm.ui.components.Switch
import androidx.compose.material3.Text
import wpm.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem
import wpm.data.Playlist
import wpm.data.ScheduleRule
import wpm.services.Occurrence
import wpm.services.parseTime
import wpm.services.describeTime
import wpm.services.solarTime
import wpm.services.Place
import wpm.services.Sun
import wpm.services.findCities
import wpm.services.guessCity
import wpm.ui.Theme
import wpm.ui.components.ButtonIcon
import wpm.ui.components.Card
import wpm.ui.components.FilterChip
import wpm.ui.components.MediaPickerDialog
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle

private data class ScheduleInfo(
    val enabled: Boolean = true,
    val rules: List<ScheduleRule> = emptyList(),
    val active: Occurrence? = null,
    val upcoming: Occurrence? = null,
    /** Today's time of each rule ("18:12"); missing for sun rules without a location. */
    val today: Map<Long, String> = emptyMap(),
    val place: Place? = null,
)

/** "18:00", or for sun rules today's time and the rule ("18:12 · 30 min before sunset"). */
private fun ScheduleInfo.timeOf(occurrence: Occurrence): String {
    val time = occurrence.time.toLocalTime().let { "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute) }
    return if (solarTime(occurrence.rule.time) != null) "$time (${describeTime(occurrence.rule.time).lowercase()})" else time
}

/** Rule being edited: null id = new rule. */
private data class RuleDraft(val id: Long? = null, val time: String = "", val mediaId: Long? = null, val playlistId: Long? = null)

@Composable
fun SchedulePage(app: AppState) {
    var info by remember { mutableStateOf(ScheduleInfo()) }
    var draft by remember { mutableStateOf<RuleDraft?>(null) }
    LaunchedEffect(app.libraryVersion, app.wallpaperVersion) {
        info = withContext(Dispatchers.IO) {
            val rules = app.db.listRules()
            val today = rules.mapNotNull { rule -> app.scheduler.todayText(rule)?.let { rule.id to it } }.toMap()
            ScheduleInfo(
                app.scheduler.enabled,
                rules.sortedWith(compareBy<ScheduleRule> { today[it.id] ?: "99" }.thenBy { it.id }), // in the order they happen today
                app.scheduler.active(), app.scheduler.upcoming(), today, app.scheduler.place,
            )
        }
    }
    fun changed() = app.libraryChanged()

    PageScaffold(tr("Schedule"), tr("Show different wallpapers at different times of day."), actions = {
        Button(onClick = { draft = RuleDraft() }) { ButtonIcon(Icons.Outlined.Add); Text(tr("Add rule")) }
    }) {
        Card(Modifier.fillMaxWidth(), accent = if (info.enabled) Theme.colors.teal else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(if (info.enabled) tr("Schedule is on") else tr("Schedule is off"))
                    MutedText(
                        when {
                            !info.enabled -> tr("Off")
                            info.rules.none { it.enabled } -> tr("No active rules")
                            else -> buildString {
                                info.active?.let { append(tr("Now: {0} (since {1})", it.rule.targetName, info.timeOf(it))) }
                                info.upcoming?.let { append(tr("   ·   Next: {0} at {1}", it.rule.targetName, info.timeOf(it))) }
                            }
                        },
                    )
                }
                Switch(info.enabled, { on -> app.runTask { app.scheduler.setEnabled(on); changed() } })
            }
            Spacer(Modifier.height(6.dp))
            MutedText(tr("Changes take effect at the next rule time. Use “Apply now” to switch straight away."))
        }

        SunCard(app, info) { changed() }

        if (info.rules.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(tr("No rules yet"), tr("For example: 07:00 → a bright picture, 18:00 → a city video, 22:00 → a night playlist.")) {
                    Button(onClick = { draft = RuleDraft() }) { ButtonIcon(Icons.Outlined.Add); Text(tr("Add rule")) }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth(), padding = PaddingValues(start = 12.dp, end = 22.dp, top = 12.dp, bottom = 12.dp)) {
                info.rules.forEachIndexed { i, rule ->
                    val active = info.enabled && info.active?.rule?.id == rule.id
                    Row(
                        Modifier.height(IntrinsicSize.Min).fillMaxWidth()
                            .background(if (active) Theme.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent, foldedShape(10.dp)),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TimelineNode(first = i == 0, last = i == info.rules.lastIndex, active = active, enabled = rule.enabled)
                        val solar = solarTime(rule.time) != null
                        Text(info.today[rule.id] ?: "--:--", color = if (rule.enabled) Theme.colors.text else Theme.colors.textSubtle, fontFamily = wpm.ui.DisplayFont,
                            fontWeight = FontWeight.SemiBold, fontSize = 26.sp, modifier = Modifier.width(84.dp).padding(vertical = 14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.targetName.ifEmpty { "(missing)" }, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            Text(
                                (if (rule.playlistId != null) tr("Playlist") else tr("Wallpaper")) + when {
                                    !solar -> ""
                                    info.place == null -> tr("  ·  {0} (set your location)", describeTime(rule.time))
                                    else -> "  ·  ${describeTime(rule.time)}"
                                },
                                color = if (solar && info.place == null) Theme.colors.danger else Theme.colors.textMuted, fontSize = 12.sp,
                            )
                        }
                        if (active) Tag(tr("Now"), Theme.colors.accent)
                        TextButton(onClick = {
                            app.runTask(tr("Applied {0}", rule.targetName), tr("Couldn't apply the rule")) {
                                app.changeWallpaper {
                                    app.scheduler.applyRule(rule)
                                    app.scheduler.markCurrentApplied()
                                }
                            }
                        }) { Text(tr("Apply now")) }
                        IconButton(onClick = { draft = RuleDraft(rule.id, rule.time, rule.mediaId, rule.playlistId) }) { Icon(Icons.Outlined.Edit, tr("Edit")) }
                        IconButton(onClick = {
                            app.runTask { app.db.deleteRule(rule.id); app.scheduler.markCurrentApplied(); changed() }
                        }) { Icon(Icons.Outlined.Delete, tr("Delete")) }
                        Switch(rule.enabled, { on ->
                            app.runTask { app.db.setRuleEnabled(rule.id, on); app.scheduler.markCurrentApplied(); changed() }
                        })
                    }
                }
            }
        }
    }

    draft?.let { d ->
        RuleDialog(app, d, onDismiss = { draft = null }) { saved ->
            app.runTask(if (saved.id == null) tr("Rule added") else tr("Rule saved")) {
                if (saved.id == null) app.db.addRule(saved.time, saved.mediaId, saved.playlistId)
                else app.db.updateRule(saved.id, saved.time, saved.mediaId, saved.playlistId)
                app.scheduler.markCurrentApplied() // edits apply from the next rule time
                changed()
            }
        }
    }
}

@Composable
private fun RuleDialog(app: AppState, initial: RuleDraft, onDismiss: () -> Unit, onSave: (RuleDraft) -> Unit) {
    var time by remember { mutableStateOf(initial.time) }
    var usePlaylist by remember { mutableStateOf(initial.playlistId != null) }
    var media by remember { mutableStateOf<MediaItem?>(null) }
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var playlists by remember { mutableStateOf(emptyList<Playlist>()) }
    var picking by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            playlists = app.db.listPlaylists()
            media = initial.mediaId?.let(app.db::getMedia)
            playlist = playlists.firstOrNull { it.id == initial.playlistId }
        }
    }
    val timeError = time.isNotBlank() && runCatching { parseTime(time) }.isFailure
    val valid = time.isNotBlank() && !timeError && (if (usePlaylist) playlist != null else media != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) tr("Add rule") else tr("Edit rule")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(time, { time = it }, label = { Text(tr("Time (HH:MM, every day)")) }, singleLine = true, isError = timeError,
                    supportingText = {
                        Text(
                            if (timeError) runCatching { parseTime(time) }.exceptionOrNull()?.message ?: ""
                            else tr("Or relative to the sun: sunrise, sunset, sunset - 30, sunrise + 1:00"),
                        )
                    })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("sunrise", "sunset").forEach { event ->
                        FilterChip(selected = time.trim().startsWith(event, ignoreCase = true), onClick = { time = event }, label = { Text(event.replaceFirstChar { it.uppercase() }) })
                    }
                }
                if (!timeError && solarTime(runCatching { parseTime(time) }.getOrDefault("")) != null && app.scheduler.place == null) {
                    Text(tr("Set your location on the Schedule page so sunrise and sunset can be worked out."), color = Theme.colors.danger, fontSize = 12.sp)
                }
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(!usePlaylist, { usePlaylist = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(tr("Wallpaper")) }
                    SegmentedButton(usePlaylist, { usePlaylist = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(tr("Playlist")) }
                }
                if (usePlaylist) {
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, enabled = playlists.isNotEmpty()) {
                            Text(playlist?.name ?: if (playlists.isEmpty()) tr("No playlists yet") else tr("Choose a playlist"))
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            playlists.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { playlist = p; menuOpen = false }) }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { picking = true }) { Text(media?.filename ?: tr("Choose a wallpaper…"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onSave(RuleDraft(initial.id, parseTime(time), if (usePlaylist) null else media?.id, if (usePlaylist) playlist?.id else null))
            }, enabled = valid) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (picking) MediaPickerDialog(app, tr("Choose a wallpaper"), multiple = false, onDismiss = { picking = false }) { media = it.first() }
}

/** One stop on the day's timeline: a fold line with a diamond, filled for the rule in effect. */
@Composable
private fun TimelineNode(first: Boolean, last: Boolean, active: Boolean, enabled: Boolean) {
    val c = Theme.colors
    val line = c.cardBorder
    val node = when {
        active -> c.accent
        enabled -> c.indigo
        else -> c.textSubtle
    }
    Canvas(Modifier.width(28.dp).fillMaxHeight()) {
        val x = size.width / 2
        val y = size.height / 2
        val r = 7.dp.toPx()
        if (!first) drawLine(line, Offset(x, 0f), Offset(x, y - r), strokeWidth = 2.dp.toPx())
        if (!last) drawLine(line, Offset(x, y + r), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        val diamond = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
        drawPath(diamond, node)
        // the lit half of the folded diamond
        drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x - r, y); close() }, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f))
    }
}

/** Where sunrise and sunset are worked out for, with today's times. */
@Composable
private fun SunCard(app: AppState, info: ScheduleInfo, onChanged: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val anySolar = info.rules.any { solarTime(it.time) != null }
    if (info.place == null && !anySolar) {
        // Only a hint while nobody uses sun rules.
        MutedText(tr("Tip: rules can also follow the sun, e.g. “sunset - 30”."))
        return
    }
    Card(Modifier.fillMaxWidth(), accent = if (info.place == null) Theme.colors.danger else Theme.colors.gold) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(tr("Sunrise and sunset"))
                val place = info.place
                if (place == null) {
                    MutedText(tr("Set your location so rules at sunrise or sunset know when to run."))
                } else {
                    val zone = java.time.ZoneId.systemDefault()
                    val today = java.time.LocalDate.now()
                    fun hm(t: java.time.ZonedDateTime?) = t?.let { "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute) } ?: "none"
                    MutedText(tr("{0}: sunrise {1}, sunset {2} today", place.name, hm(Sun.sunrise(today, place, zone)), hm(Sun.sunset(today, place, zone))))
                }
            }
            OutlinedButton(onClick = { editing = true }) { Text(if (info.place == null) tr("Set location") else tr("Change")) }
        }
    }
    if (editing) {
        LocationDialog(info.place, onDismiss = { editing = false }) { place ->
            app.runTask(tr("Location saved")) {
                app.scheduler.setPlace(place)
                onChanged()
            }
        }
    }
}

@Composable
private fun LocationDialog(current: Place?, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    val suggestion = remember { current ?: guessCity() }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(suggestion) }
    var lat by remember { mutableStateOf(suggestion?.latitude?.toString() ?: "") }
    var lon by remember { mutableStateOf(suggestion?.longitude?.toString() ?: "") }
    val latitude = lat.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val longitude = lon.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Your location")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(tr("Pick a city near you, or enter the coordinates. Nothing is sent anywhere."))
                OutlinedTextField(query, { query = it }, label = { Text(tr("Search a city")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                findCities(query).forEach { city ->
                    TextButton(onClick = {
                        chosen = city
                        lat = city.latitude.toString()
                        lon = city.longitude.toString()
                        query = ""
                    }) { Text("${city.name}  (${city.latitude}, ${city.longitude})") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it; chosen = null }, label = { Text(tr("Latitude (N+)")) }, singleLine = true,
                        isError = lat.isNotBlank() && latitude == null, modifier = Modifier.weight(1f))
                    OutlinedTextField(lon, { lon = it; chosen = null }, label = { Text(tr("Longitude (E+)")) }, singleLine = true,
                        isError = lon.isNotBlank() && longitude == null, modifier = Modifier.weight(1f))
                }
                chosen?.let { MutedText(tr("Near {0}", it.name)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onSave(Place(chosen?.name ?: "%.2f, %.2f".format(java.util.Locale.ROOT, latitude, longitude), latitude!!, longitude!!))
            }, enabled = latitude != null && longitude != null) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
