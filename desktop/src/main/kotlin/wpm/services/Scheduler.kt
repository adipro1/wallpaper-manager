package wpm.services

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

/** "7:5" / "07:05" / "0705" -> "07:05". Throws IllegalArgumentException for invalid times. */
fun parseTime(text: String): String {
    val t = text.trim()
    val (h, m) = when {
        ':' in t -> t.substringBefore(':') to t.substringAfter(':')
        t.all(Char::isDigit) && t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
        else -> throw IllegalArgumentException("“$t” is not a time (use HH:MM)")
    }
    val hours = h.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()
    val minutes = m.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()
    if (hours == null || minutes == null) throw IllegalArgumentException("“$t” is not a time (use HH:MM)")
    if (hours !in 0..23 || minutes !in 0..59) throw IllegalArgumentException("“$t” is not a valid time")
    return "%02d:%02d".format(java.util.Locale.ROOT, hours, minutes)
}

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

    fun setEnabled(enabled: Boolean) {
        settings.set("schedule_enabled", enabled)
        markCurrentApplied()
    }

    /** Yesterday's, today's and tomorrow's occurrence of every enabled rule, in time order. */
    private fun occurrences(now: ZonedDateTime): List<Occurrence> {
        val rules = db.listRules().filter { it.enabled }
        return (-1L..1L).flatMap { offset ->
            val day = now.toLocalDate().plusDays(offset)
            // atZone moves times in a DST gap forward, and picks the earlier of repeated times.
            rules.map { Occurrence(day.atTime(LocalTime.of(it.minutes / 60, it.minutes % 60)).atZone(now.zone), it) }
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
            val item = rule.mediaId?.let(db::getMedia) ?: throw IllegalStateException("The wallpaper of this rule is no longer in the library")
            playlists.stop()
            wallpapers.set(item)
        }
        log.info("Schedule: applied ${rule.time} → ${rule.targetName}")
    }
}
