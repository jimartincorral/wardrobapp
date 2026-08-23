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

/** Case-insensitive "contains", with a blank needle matching everything. */
private fun contains(haystack: String?, needle: String): Boolean =
    (haystack ?: "").lowercase().contains(needle.lowercase().trim())

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

    if (filter.brand != null && !contains(it.brand, filter.brand)) return@filter false
    if (filter.size != null && !contains(it.size, filter.size)) return@filter false

    if (filter.color != null && !it.palette.contains(filter.color)) return@filter false

    true
}

/**
 * Order by when a garment was added.
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
fun List<GarmentRecord>.sortedBy(sort: GarmentSort = GarmentSort.NEWEST): List<GarmentRecord> {
    val byCreatedAt = compareBy<GarmentRecord> { it.createdAt ?: "" }
    return when (sort) {
        GarmentSort.OLDEST -> sortedWith(byCreatedAt)
        GarmentSort.NEWEST -> sortedWith(byCreatedAt.reversed())
    }
}
