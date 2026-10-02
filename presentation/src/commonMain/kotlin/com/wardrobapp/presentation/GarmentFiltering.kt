package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.occasions

/**
 * Narrowing and ordering a wardrobe.
 *
 * The database applies the filters it can express -- category, availability, a
 * text search across several columns. These are the rest: the ones that need a
 * parsed garment, because they look inside what were JSON columns or at a value
 * derived from the garment's type.
 */

enum class GarmentSort { NEWEST, OLDEST }

data class GarmentFilter(
    val subcategory: String? = null,
    val season: Season? = null,
    val occasion: Occasion? = null,
    val brand: String? = null,
    val size: String? = null,
    val color: String? = null,
)

/** Two stored colours are the same colour when their hex matches, whatever the case. */
private fun String.sameColorAs(other: String): Boolean =
    trim().equals(other.trim(), ignoreCase = true)

/**
 * Case-insensitive equality, with a blank needle matching everything.
 *
 * This was a "contains" while brand and size were typed into boxes, where a
 * partial word was the point: "ark" found Arket however it was capitalised. They
 * are picked from the wardrobe's own values now, so a partial match is no longer a
 * kindness -- picking Nike and being shown Nike Pro as well is simply the wrong
 * answer to a chip that came from the data.
 *
 * Blank still matches everything, because a filter nobody set is not a filter.
 */
private fun matches(value: String?, wanted: String): Boolean {
    val needle = wanted.trim()
    if (needle.isEmpty()) return true

    return (value ?: "").trim().equals(needle, ignoreCase = true)
}

fun List<GarmentRecord>.filterBy(filter: GarmentFilter): List<GarmentRecord> = filter {
    if (filter.subcategory != null && !it.effectiveSubcategories.contains(filter.subcategory)) {
        return@filter false
    }

    if (filter.season != null && !it.tags.map(String::lowercase).contains(filter.season.tag)) {
        return@filter false
    }

    // Occasion is derived from the garment's type, not stored as a tag.
    if (filter.occasion != null && !it.toDomain().occasions().contains(filter.occasion)) {
        return@filter false
    }

    if (filter.brand != null && !matches(it.brand, filter.brand)) return@filter false
    if (filter.size != null && !matches(it.size, filter.size)) return@filter false

    // A garment's palette holds hex, so hex is what a colour filter asks for --
    // the palette's key for a colour ("gold") appears nowhere in the wardrobe.
    // Case-insensitively, because the same colour really is stored both ways
    // across a wardrobe; `paletteColorFor` has always had to allow for that.
    val color = filter.color
    if (color != null && it.palette.none { hex -> hex.sameColorAs(color) }) return@filter false

    true
}

/**
 * Order by when a garment was added.
 *
 * Named `orderedBy` rather than `sortedBy`: the latter would shadow the stdlib's
 * `List.sortedBy(selector)` on the same receiver type. It happens to resolve,
 * since a GarmentSort is not a selector function, but a reader cannot tell which
 * one is meant at a glance and a future overload could make it ambiguous.
 *
 * `createdAt` is nullable because an install upgraded through the ALTER path
 * really can have no timestamp -- SQLite cannot add a NOT NULL column without a
 * default. The TypeScript declared it non-null and dereferenced it, which threw
 * for any wardrobe big enough for the null to reach the right-hand side of a
 * comparison; the list hook swallowed that, so the screen showed an *empty
 * wardrobe*. An absent timestamp sorts as the earliest possible, so such a
 * garment appears last under NEWEST and first under OLDEST rather than
 * disappearing.
 */
fun List<GarmentRecord>.orderedBy(sort: GarmentSort = GarmentSort.NEWEST): List<GarmentRecord> {
    val byCreatedAt = compareBy<GarmentRecord> { it.createdAt ?: "" }
    return when (sort) {
        GarmentSort.OLDEST -> sortedWith(byCreatedAt)
        GarmentSort.NEWEST -> sortedWith(byCreatedAt.reversed())
    }
}
