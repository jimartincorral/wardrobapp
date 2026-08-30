package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * How the app is set up, as a backup carries it.
 *
 * The wardrobe was always the thing a backup was for, and settings were
 * deliberately left out: they are facts about a phone, and a restore that brought
 * them along would apply somebody else's decisions to this handset. That reasoning
 * still holds, which is why restoring them is a separate answer the person gives
 * rather than something that happens to them.
 *
 * What is emphatically not here is anything from `wardrobapp_drive`. That file
 * holds an OAuth refresh token for a Google account, and an archive is a zip that
 * gets uploaded, downloaded, copied about and shared. A credential does not go in
 * one. The caller decides what to collect, and [ArchiveSettingsTest] is not the
 * thing standing between a token and a backup -- the allowlist on the :app side
 * is.
 */

const val SETTINGS_NAME = "settings.json"

/**
 * One stored value, with the type it has to come back as.
 *
 * Tagged rather than inferred from JSON, because SharedPreferences is typed and
 * reading a value back as the wrong one throws: a column count written as a Long
 * and read with `getInt` is a ClassCastException at the moment somebody opens
 * their wardrobe.
 */
sealed interface SettingValue {
    data class Text(val value: String) : SettingValue
    data class Flag(val value: Boolean) : SettingValue
    data class Whole(val value: Int) : SettingValue
    data class Big(val value: Long) : SettingValue
}

/** Everything a backup says about how the app was set up. */
data class ArchiveSettings(
    /** The app's language as a BCP-47 tag list, or null to follow the system. */
    val language: String? = null,
    /** Preference file name to the values in it. */
    val preferences: Map<String, Map<String, SettingValue>> = emptyMap(),
) {
    val isEmpty: Boolean get() = language == null && preferences.values.all { it.isEmpty() }
}

private const val TYPE = "type"
private const val VALUE = "value"

fun writeArchiveSettings(settings: ArchiveSettings): String = buildJsonObject {
    settings.language?.let { put("language", it) }

    put(
        "preferences",
        buildJsonObject {
            for ((file, values) in settings.preferences) {
                put(
                    file,
                    buildJsonObject {
                        for ((key, value) in values) {
                            put(key, value.asJson())
                        }
                    },
                )
            }
        },
    )
}.toString()

private fun SettingValue.asJson(): JsonObject = when (this) {
    is SettingValue.Text -> buildJsonObject { put(TYPE, "string"); put(VALUE, value) }
    is SettingValue.Flag -> buildJsonObject { put(TYPE, "boolean"); put(VALUE, value) }
    is SettingValue.Whole -> buildJsonObject { put(TYPE, "int"); put(VALUE, value) }
    is SettingValue.Big -> buildJsonObject { put(TYPE, "long"); put(VALUE, value) }
}

/**
 * Read what an archive said about its settings, or null if it did not say.
 *
 * **Nothing in here throws.** A settings file this build cannot read is a settings
 * file to ignore: the wardrobe is what a restore is for, and losing somebody's
 * photos because their theme was recorded oddly would be the wrong trade by a wide
 * margin. Unrecognised types and malformed entries are dropped one at a time
 * rather than taking the file down with them, for the same reason.
 */
fun readArchiveSettings(text: String): ArchiveSettings? {
    val root = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null

    val language = runCatching { root["language"]?.jsonPrimitive?.contentOrNullIfNotString() }.getOrNull()

    val preferences = runCatching { root["preferences"]?.jsonObject }.getOrNull()
        ?.mapNotNull { (file, entries) ->
            val values = runCatching { entries.jsonObject }.getOrNull()
                ?.mapNotNull { (key, entry) -> entry.asSettingValue()?.let { key to it } }
                ?.toMap()
                ?: return@mapNotNull null

            file to values
        }
        ?.toMap()
        .orEmpty()

    return ArchiveSettings(language = language, preferences = preferences)
}

private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null

private fun kotlinx.serialization.json.JsonElement.asSettingValue(): SettingValue? {
    val entry = runCatching { jsonObject }.getOrNull() ?: return null
    val type = runCatching { entry[TYPE]?.jsonPrimitive?.content }.getOrNull() ?: return null
    val value = runCatching { entry[VALUE]?.jsonPrimitive }.getOrNull() ?: return null

    return when (type) {
        "string" -> value.contentOrNullIfNotString()?.let { SettingValue.Text(it) }
        "boolean" -> value.booleanOrNull?.let { SettingValue.Flag(it) }
        // Through Long and narrowed, so a number too large for an Int is dropped
        // rather than silently wrapping into a plausible-looking wrong one.
        "int" -> value.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.let { SettingValue.Whole(it.toInt()) }
        "long" -> value.longOrNull?.let { SettingValue.Big(it) }
        else -> null
    }
}

private val lenient = Json { ignoreUnknownKeys = true }
