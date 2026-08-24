package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season

/**
 * Everything the wardrobe screen lets you narrow by, in one place.
 *
 * Separate from [GarmentFilter], which is the set of predicates the *database*
 * cannot express. This is the
 * screen's own state: it holds what the text boxes literally contain, decides
 * what counts as narrowed, and produces the filter from it. Keeping the two apart
 * means the text boxes can hold blanks -- which is what an empty box is -- without
 * a blank ever reaching a predicate as a search for the empty string.
 *
 * The count and the clearing exist here rather than in the composable because
 * they are rules, not layout: the React Native screen computes both inline and
 * has no test for either.
 */
data class WardrobeQuery(
    val search: String = "",
    val sort: GarmentSort = GarmentSort.NEWEST,
    val category: String? = null,
    val subcategory: String? = null,
    val season: Season? = null,
    val occasion: Occasion? = null,
    val brand: String = "",
    val size: String = "",
    val color: String? = null,
    /**
     * Whether retired garments are shown.
     *
     * Not in the React Native app, and the reason it is here: nothing there ever
     * asks for them, so a retired garment cannot be found again and the "wearing
     * this again" action on its detail screen cannot be reached. Retiring is a
     * one-way door. Being able to ask for them is what makes it reversible.
     */
    val includeRetired: Boolean = false,
) {

    /**
     * How many things are narrowing the list.
     *
     * Counts a typed-but-blank box as nothing, and counts the sort only when it is
     * not the default -- the same nine the React Native screen counts, plus
     * showing retired garments, which is as much a departure from the plain list
     * as any of the others.
     */
    val activeFilterCount: Int
        get() = listOf(
            category,
            subcategory,
            season,
            occasion,
            brand.trim().ifEmpty { null },
            size.trim().ifEmpty { null },
            color,
            search.trim().ifEmpty { null },
            sort.takeIf { it != GarmentSort.NEWEST },
            true.takeIf { includeRetired },
        ).count { it != null }

    /** True when the list is showing something other than the whole wardrobe. */
    val isNarrowed: Boolean get() = activeFilterCount > 0

    /** The search term, or null when the box is empty -- never a blank search. */
    val searchTerm: String? get() = search.trim().ifEmpty { null }

    /** The predicates the database cannot apply, blanks normalised away. */
    fun garmentFilter(): GarmentFilter = GarmentFilter(
        subcategory = subcategory,
        season = season,
        occasion = occasion,
        brand = brand.trim().ifEmpty { null },
        size = size.trim().ifEmpty { null },
        color = color,
    )

    /**
     * Back to the plain wardrobe.
     *
     * Everything, including the search box and the sort -- which is what the
     * React Native "clear all" does, and what "all" ought to mean.
     */
    fun cleared(): WardrobeQuery = WardrobeQuery()

    /**
     * Pick a category, or tap the current one again to drop it.
     *
     * Either way the subcategory goes: it only means anything inside a category,
     * so leaving it behind would filter by a type the chosen category does not
     * have and quietly show nothing.
     */
    fun withCategory(key: String?): WardrobeQuery =
        copy(category = if (category == key) null else key, subcategory = null)

    /** Toggle a subcategory within the chosen category. */
    fun withSubcategory(key: String?): WardrobeQuery =
        copy(subcategory = if (subcategory == key) null else key)

    fun withSeason(value: Season?): WardrobeQuery =
        copy(season = if (season == value) null else value)

    fun withOccasion(value: Occasion?): WardrobeQuery =
        copy(occasion = if (occasion == value) null else value)

    fun withColor(value: String?): WardrobeQuery =
        copy(color = if (color == value) null else value)

    fun withSortToggled(): WardrobeQuery =
        copy(sort = if (sort == GarmentSort.NEWEST) GarmentSort.OLDEST else GarmentSort.NEWEST)
}
