package wpm.services

import wpm.tr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import wpm.data.Database
import wpm.data.ScheduleRule
import wpm.data.Settings
import wpm.logger
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val log = logger("schedule")

private val SOLAR = Regex("""^(sunrise|sunset)\s*(?:([+-])\s*(\d{1,3})(?::(\d{2}))?\s*(?:min|m)?)?$""", RegexOption.IGNORE_CASE)

/**
 * A rule time: "7:5" / "07:05" / "0705" -> "07:05", or one relative to the sun:
 * "sunset" / "Sunrise + 30" / "sunset-1:15" -> "sunset", "sunrise+30", "sunset-75" (minutes).
 * Throws IllegalArgumentException for anything else.
 */
fun parseTime(text: String): String {
    val t = text.trim()
    SOLAR.matchEntire(t)?.let { m ->
        val (event, sign, first, second) = m.destructured
        val minutes = when {
            sign.isEmpty() -> 0
            second.isNotEmpty() -> first.toInt() * 60 + second.toInt()
            else -> first.toInt()
        }
        if (minutes > 6 * 60) throw IllegalArgumentException(tr("Keep it within 6 hours of {0}", event.lowercase()))
        return event.lowercase() + if (minutes == 0) "" else "$sign$minutes"
    }
    val (h, m) = when {
        ':' in t -> t.substringBefore(':') to t.substringAfter(':')
        t.all(Char::isDigit) && t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
        else -> throw IllegalArgumentException(tr("“{0}” is not a time (use HH:MM)", t))
    }
    val hours = h.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()
    val minutes = m.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()
    if (hours == null || minutes == null) throw IllegalArgumentException(tr("“{0}” is not a time (use HH:MM)", t))
    if (hours !in 0..23 || minutes !in 0..59) throw IllegalArgumentException(tr("“{0}” is not a valid time", t))
    return "%02d:%02d".format(java.util.Locale.ROOT, hours, minutes)
}

/** For a rule time from [parseTime]: the sun event and the offset in minutes, or null for a clock time. */
fun solarTime(time: String): Pair<String, Int>? {
    if (!time.startsWith("sun")) return null
    val event = if (time.startsWith("sunrise")) "sunrise" else "sunset"
    return event to (time.removePrefix(event).toIntOrNull() ?: 0)
}

/** "sunset-30" -> "30 min before sunset"; clock times stay as they are. */
fun describeTime(time: String): String {
    val (event, offset) = solarTime(time) ?: return time
    return when {
        offset == 0 -> event.replaceFirstChar { it.uppercase() }
        offset > 0 -> "${formatOffset(offset)} after $event"
        else -> "${formatOffset(-offset)} before $event"
    }
}

private fun formatOffset(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} h" else if (minutes > 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** One occurrence of a rule on a particular day. */
data class Occurrence(val time: ZonedDateTime, val rule: ScheduleRule) {
    /** Same key format as version 1.x: "<rule id>@2026-10-16T18:00" (local time). */
    val key: String get() = "${rule.id}@${time.toLocalDateTime().format(KEY_FORMAT)}"

    companion object {
        private val KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}

/**
 * Time-based schedule, e.g. 07:00 → morning.jpg, 18:00 → city.mp4. Rules repeat daily.
 * At any moment the *active* rule is the one with the latest time not after now (wrapping
 * round to yesterday's last rule). Each occurrence is applied once; the last applied one
 * is saved, so after a restart the app catches up without re-applying it every time.
 *
 * Editing rules or switching the schedule on doesn't change the wallpaper straight away -
 * changes take effect at the next rule time (or via "Apply now").
 */
class Scheduler(
    private val db: Database,
    private val wallpapers: WallpaperService,
    private val playlists: PlaylistPlayer,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val settings = Settings(db)

    val enabled: Boolean get() = settings.boolean("schedule_enabled", true)

    /** Where sunrise and sunset are worked out for; null until the user sets it. */
    val place: Place? get() = settings.obj("location", Place.serializer())

    fun setPlace(place: Place?) {
        if (place == null) db.execute("DELETE FROM settings WHERE key = 'location'") else settings.set("location", Place.serializer(), place)
        markCurrentApplied() // the times moved; don't jump to another wallpaper because of that
    }

    /**
     * When [rule] happens on [day]: its clock time, or the sun event plus its offset. Null for
     * sun rules without a location, or on days the sun doesn't rise or set there.
     */
    fun timeOn(rule: ScheduleRule, day: java.time.LocalDate, zone: java.time.ZoneId): ZonedDateTime? {
        val (event, offset) = solarTime(rule.time)
            // atZone moves times in a DST gap forward, and picks the earlier of repeated times.
            ?: return day.atTime(LocalTime.of(rule.minutes / 60, rule.minutes % 60)).atZone(zone)
        val where = place ?: return null
        val sun = if (event == "sunrise") Sun.sunrise(day, where, zone) else Sun.sunset(day, where, zone)
        return sun?.plusMinutes(offset.toLong())?.withSecond(0)
    }

    /** Today's time of a rule, e.g. "18:12", or null (see [timeOn]). */
    fun todayText(rule: ScheduleRule): String? {
        val now = clock()
        return timeOn(rule, now.toLocalDate(), now.zone)?.toLocalTime()?.let { "%02d:%02d".format(it.hour, it.minute) }
    }

    fun setEnabled(enabled: Boolean) {
        settings.set("schedule_enabled", enabled)
        markCurrentApplied()
    }

    /** Yesterday's, today's and tomorrow's occurrence of every enabled rule, in time order. */
    private fun occurrences(now: ZonedDateTime): List<Occurrence> {
        val rules = db.listRules().filter { it.enabled }
        return (-1L..1L).flatMap { offset ->
            val day = now.toLocalDate().plusDays(offset)
            rules.mapNotNull { rule -> timeOn(rule, day, now.zone)?.let { Occurrence(it, rule) } }
        }.sortedWith(compareBy<Occurrence> { it.time }.thenBy { it.rule.id })
    }

    /** The rule that should be showing now, with the time it started. */
    fun active(): Occurrence? {
        val now = clock()
        return occurrences(now).lastOrNull { !it.time.isAfter(now) }
    }

    fun upcoming(): Occurrence? {
        val now = clock()
        return occurrences(now).firstOrNull { it.time.isAfter(now) }
    }

    private val lastApplied: String?
        get() = runCatching { db.getSetting("schedule_last_applied")?.jsonPrimitive?.contentOrNull }.getOrNull()

    fun isDue(): Boolean {
        if (!enabled) return false
        val current = active() ?: return false
        return current.key != lastApplied
    }

    /** Treats the rule that is active right now as done (used after edits). */
    fun markCurrentApplied() {
        db.setSetting("schedule_last_applied", active()?.let { JsonPrimitive(it.key) } ?: JsonNull)
    }

    /** Applies the active rule if it hasn't been applied yet. */
    fun applyDue(): ScheduleRule? {
        if (!isDue()) return null
        val current = active() ?: return null
        // Mark first: if the file is broken we don't want to retry every few seconds.
        db.setSetting("schedule_last_applied", JsonPrimitive(current.key))
        applyRule(current.rule)
        return current.rule
    }

    fun applyRule(rule: ScheduleRule) {
        if (rule.playlistId != null) {
            playlists.start(rule.playlistId)
        } else {
            val item = rule.mediaId?.let(db::getMedia) ?: throw IllegalStateException(tr("The wallpaper of this rule is no longer in the library"))
            playlists.stop()
            wallpapers.set(item)
        }
        log.info("Schedule: applied ${describeTime(rule.time)} → ${rule.targetName}")
    }
}
