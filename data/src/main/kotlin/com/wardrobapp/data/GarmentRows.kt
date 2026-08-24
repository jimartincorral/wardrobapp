package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turning a database row into a [GarmentRecord].
 *
 * Every garment read passes through here, and every rule below exists because
 * some real row needed it. List columns hold a JSON array in rows written by
 * current builds, a bare comma-separated string in rows written by much older
 * ones, and occasionally nothing at all. Colour and photo columns exist in both
 * a single-value and a list form, either of which may be the populated one.
 *
 * Pure: `imageDirectory` is supplied by the caller rather than looked up, so this
 * -- the most compatibility-critical code in the port -- can be tested anywhere.
 */

private val json = Json

/**
 * Parse a list column: a JSON array, else a comma-separated string.
 *
 * `preserveEmpty` keeps blank entries, which the no-background photo list needs:
 * its indices line up with the plain photo list, so a garment whose second photo
 * has no cut-out version must keep that gap rather than shift the rest along.
 */
internal fun parseStringArray(value: Any?, preserveEmpty: Boolean = false): List<String> {
    fun keep(items: List<String>) = items.filter { preserveEmpty || it.isNotEmpty() }

    if (value is List<*>) {
        return keep(value.map { jsString(it).trim() })
    }

    if (value !is String || value.isBlank()) return emptyList()

    val parsed = try {
        json.parseToJsonElement(value)
    } catch (_: Exception) {
        // Not JSON at all: the oldest rows stored a bare comma-separated list.
        return keep(value.split(',').map { it.trim() })
    }

    // Parsed, but not an array -- a bare number or string. The TypeScript
    // returns nothing here rather than falling through to the comma split.
    if (parsed !is JsonArray) return emptyList()

    return keep(parsed.map { it.jsonPrimitive.content.trim() })
}

/** Deduplicate case-insensitively, keeping the first spelling seen. */
internal fun uniqueCaseInsensitive(values: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()

    for (value in values) {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || !seen.add(trimmed.lowercase())) continue
        result.add(trimmed)
    }

    return result
}

private fun stringOrEmpty(value: Any?): String = if (value is String) value else ""

/**
 * Coerce a scalar text column, preserving absence.
 *
 * A SQLite column declared TEXT can still hold a number, and the TypeScript
 * side normalizes these rather than passing them through -- a numeric `size`
 * used to reach duplicate detection and throw, since optional chaining guards
 * null but not a missing method.
 */
private fun asText(value: Any?): String? = value?.let { jsString(it) }

private fun stringOrNull(value: Any?): String? = value as? String

/** Normalize one row from the `garments` table. */
fun normalizeGarmentRow(row: Map<String, Any?>, imageDirectory: String): GarmentRecord {
    fun resolve(ref: String) = resolveImageRef(ref, imageDirectory)

    val tags = parseStringArray(row["tags"]).map { it.lowercase() }

    val subcategories = uniqueCaseInsensitive(
        parseStringArray(row["subcategories"]) + stringOrEmpty(row["subcategory"])
    )

    val imageUris = uniqueCaseInsensitive(
        parseStringArray(row["image_uris"]) + stringOrEmpty(row["image_uri"])
    )

    val imageUrisNoBg = parseStringArray(row["image_uris_nobg"], preserveEmpty = true).toMutableList()
    if (imageUrisNoBg.isEmpty() && row["image_uri_nobg"] is String) {
        imageUrisNoBg.add(row["image_uri_nobg"] as String)
    }

    val colorPalette = uniqueCaseInsensitive(
        parseStringArray(row["color_palette"]) +
            stringOrEmpty(row["color_primary"]) +
            stringOrEmpty(row["color_secondary"])
    )

    val imageUri = imageUris.firstOrNull() ?: jsString(row["image_uri"] ?: "")
    val imageUriNoBg = imageUrisNoBg.firstOrNull() ?: stringOrNull(row["image_uri_nobg"])
    val colorPrimary = colorPalette.firstOrNull() ?: jsString(row["color_primary"] ?: "#000000")
    val colorSecondary = colorPalette.getOrNull(1) ?: stringOrNull(row["color_secondary"])

    // Photo references are stored as bare filenames (and, in rows from older
    // builds, as absolute paths that may no longer exist). Re-attach the current
    // directory here so every consumer gets something loadable.
    val resolvedUris = (
        if (imageUris.isNotEmpty()) imageUris else listOf(imageUri).filter { it.isNotEmpty() }
        ).map(::resolve)

    return GarmentRecord(
        id = jsString(row["id"] ?: ""),
        imageUri = resolve(imageUri),
        imageUriNoBg = imageUriNoBg?.let { resolve(it) },
        imageUris = resolvedUris,
        imageUrisNoBg = imageUrisNoBg.map(::resolve),
        category = jsString(row["category"] ?: ""),
        subcategory = subcategories.firstOrNull() ?: stringOrNull(row["subcategory"]),
        subcategories = subcategories,
        tags = tags,
        brand = asText(row["brand"]),
        colorPrimary = colorPrimary,
        colorSecondary = colorSecondary,
        colorPalette = colorPalette.ifEmpty { listOf(colorPrimary).filter { it.isNotEmpty() } },
        size = asText(row["size"]),
        purchaseDate = asText(row["purchase_date"]),
        isAvailable = jsTruthy(row["is_available"]),
        unavailableDate = asText(row["unavailable_date"]),
        createdAt = stringOrNull(row["created_at"]),
        updatedAt = stringOrNull(row["updated_at"]),
    )
}
