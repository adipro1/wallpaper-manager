package wpm.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import wpm.logger

/** Lenient: settings written by an older or newer version never break startup. */
val settingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

private val log = logger("settings")

/** Typed access to the settings table (values are JSON, same keys as version 1.x). */
class Settings(private val db: Database) {
    fun string(key: String, default: String): String =
        runCatching { db.getSetting(key)?.jsonPrimitive?.contentOrNull }.getOrNull() ?: default

    fun boolean(key: String, default: Boolean): Boolean =
        runCatching { db.getSetting(key)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: default

    fun set(key: String, value: String) = db.setSetting(key, JsonPrimitive(value))
    fun remove(key: String) {
        db.execute("DELETE FROM settings WHERE key = ?", key)
    }
    fun set(key: String, value: Boolean) = db.setSetting(key, JsonPrimitive(value))

    /** A structured setting, or null if it is missing or can't be read. */
    fun <T> obj(key: String, serializer: KSerializer<T>): T? {
        val element = db.getSetting(key) ?: return null
        return try {
            settingsJson.decodeFromJsonElement(serializer, element)
        } catch (e: Exception) {
            log.warning("Ignoring unreadable setting '$key': ${e.message}")
            null
        }
    }

    fun <T> set(key: String, serializer: KSerializer<T>, value: T) =
        db.setSetting(key, settingsJson.encodeToJsonElement(serializer, value))
}
