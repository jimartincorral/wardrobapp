package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord

/**
 * Finding a garment by typing at it.
 *
 * The wardrobe list searches in SQL, because it is already asking the database
 * for its rows and a `LIKE` costs nothing there. The outfit picker cannot: it
 * holds the whole wardrobe already and re-querying on every keystroke would be a
 * round trip and a re-parse per character.
 *
 * So the same question is asked two ways, and the fields have to be kept in step
 * by hand -- see `GarmentQueries.allGarments`. That is the honest cost of having
 * both, and it is written down here rather than discovered when one of them finds
 * something the other cannot.
 */

/**
 * Whether a garment answers to what was typed.
 *
 * The fields somebody would actually reach for: what kind of garment it is, who
 * made it, what size it is, and whatever they tagged it with. Not the colour,
 * which is stored as a hex nobody types, and not the id.
 *
 * A blank term matches everything, so an empty search box is not a filter.
 */
fun garmentMatchesSearch(garment: GarmentRecord, term: String): Boolean {
    val needle = term.trim().lowercase()
    if (needle.isEmpty()) return true

    val haystack = buildList {
        add(garment.category)
        garment.subcategory?.let { add(it) }
        addAll(garment.subcategories)
        garment.brand?.let { add(it) }
        garment.size?.let { add(it) }
        addAll(garment.tags)
    }

    return haystack.any { it.lowercase().contains(needle) }
}

/** The garments that answer to what was typed, in the order they were given. */
fun garmentsMatching(garments: List<GarmentRecord>, term: String): List<GarmentRecord> =
    garments.filter { garmentMatchesSearch(it, term) }
