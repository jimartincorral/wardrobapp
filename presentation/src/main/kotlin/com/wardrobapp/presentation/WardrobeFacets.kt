package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.occasions
import java.text.Collator

/**
 * What a wardrobe actually holds, per filter.
 *
 * The panel used to offer everything the *app* knows: every category, every type
 * in it, all twenty-five palette colours, and two text boxes for brand and size.
 * Most of those match nothing in any particular wardrobe, so most taps ended at
 * "nothing matches these filters" -- and the two things a person is most likely to
 * filter by, a brand and a size, had to be typed from memory and spelled right.
 *
 * Derived from the garments *on screen* rather than from the whole table, which is
 * what makes the choices narrow as filters are picked: choose Tops and the colour
 * row is the colours your tops come in. It also settles the retired question for
 * free -- the list excludes them until you ask for them, so their brands and sizes
 * appear exactly when they can match something.
 *
 * One rule exists only to keep the panel usable: whatever is *currently picked* is
 * always offered, even when nothing matches it. Without that, a combination that
 * matches nothing empties every row, including the row holding the choice you
 * would want to undo.
 */
data class WardrobeFacets(
    val categories: List<String> = emptyList(),
    /** The types within the chosen category. Empty when no category is chosen. */
    val subcategories: List<String> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val occasions: List<Occasion> = emptyList(),
    /** Palette hexes, in the palette's own order, with anything unnamed last. */
    val colors: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val sizes: List<String> = emptyList(),
)

/**
 * Brands and sizes are what somebody typed, so they sort the way a reader reads.
 *
 * Through a collator rather than by raw characters, for the reason the brand chart
 * already sorts that way: an accented brand belongs where a reader expects it and
 * not after Z. Wrapped in `compareBy` because a Collator compares objects, not
 * strings.
 */
private val byName: Comparator<String> = compareBy(Collator.getInstance()) { it }

/** Blank is not a value: a garment with no brand recorded is not a brand. */
private fun List<String?>.values(): List<String> =
    mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }

/**
 * The choices to offer for a wardrobe, given what is picked.
 *
 * [garments] is the list as shown -- already narrowed by the query -- and [query]
 * is only consulted to keep the current choice on offer and to decide whether the
 * type row applies at all.
 */
fun wardrobeFacets(garments: List<GarmentRecord>, query: WardrobeQuery): WardrobeFacets {
    val present = garments.map { it.category }.distinct()

    // In the app's own order rather than the wardrobe's, so the row does not
    // rearrange itself as the list changes underneath it.
    val categories = GARMENT_CATEGORIES.map { it.id }
        .filter { it in present || it == query.category }

    val chosen = GARMENT_CATEGORIES.firstOrNull { it.id == query.category }
    val subcategoriesPresent = garments.flatMap { it.effectiveSubcategories }.distinct()
    val subcategories = chosen
        ?.subcategories
        ?.filter { it in subcategoriesPresent || it == query.subcategory }
        .orEmpty()

    val tags = garments.flatMap { garment -> garment.tags.map { it.lowercase() } }.toSet()
    val seasons = Season.entries.filter { it.tag in tags || it == query.season }

    val occasionsPresent = garments.flatMap { it.toDomain().occasions() }.toSet()
    val occasions = Occasion.entries.filter { it in occasionsPresent || it == query.occasion }

    // Compared case-insensitively because the same colour is stored in both cases
    // across a wardrobe, and offered in the palette's spelling.
    val paletteUsed = garments.flatMap { it.palette }.values().map { it.uppercase() }.toSet()
    val named = GARMENT_COLORS.map { it.second }
        .filter { it.uppercase() in paletteUsed || it.equals(query.color, ignoreCase = true) }
    val unnamed = paletteUsed
        .filter { hex -> paletteColorFor(hex) == null }
        .sorted()

    // The picked one is appended before the blanks are dropped, so an empty box
    // adds nothing and a chosen brand survives a combination that matches none.
    val brands = (garments.map { it.brand } + query.brand).values()
        .distinctBy { it.lowercase() }
        .sortedWith(byName)

    val sizes = (garments.map { it.size } + query.size).values()
        .distinctBy { it.lowercase() }
        .sortedWith(byName)

    return WardrobeFacets(
        categories = categories,
        subcategories = subcategories,
        seasons = seasons,
        occasions = occasions,
        colors = named + unnamed,
        brands = brands,
        sizes = sizes,
    )
}
