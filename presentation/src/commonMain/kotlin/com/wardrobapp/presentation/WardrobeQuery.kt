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
     * What the wardrobe should be showing when another screen opens it.
     *
     * Built from nothing rather than from the query in force, and that is the
     * point: a tap on the "Tops" bar means "show me those 14 garments", and a
     * size or a season left over from the last visit would show some of them
     * with nothing on screen to say where the rest went. The number tapped and
     * the length of the list have to agree, or the link misrepresents the chart.
     *
     * The screen still reports itself as narrowed -- the header shows the count
     * and the clear button -- so arriving filtered is visible and one tap from
     * undone.
     */
    companion object {
        fun showing(link: WardrobeLink?): WardrobeQuery = when (link) {
            null -> WardrobeQuery()
            is WardrobeLink.Category -> WardrobeQuery(category = link.id)
            // Both, because a type only means anything inside a category: a
            // subcategory on its own would be filtering by "Boots" across a
            // wardrobe where two categories have boots in them.
            is WardrobeLink.Type -> WardrobeQuery(category = link.category, subcategory = link.name)
            is WardrobeLink.Colour -> WardrobeQuery(color = link.value)
            is WardrobeLink.Brand -> WardrobeQuery(brand = link.name)
            // The one link that asks for retired garments. It comes from a number
            // that counts exactly the garments the plain wardrobe hides, so
            // without this it would open on a list holding none of them.
            WardrobeLink.Retired -> WardrobeQuery(includeRetired = true)
        }
    }

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

    /**
     * Pick a brand, or tap the current one again to drop it.
     *
     * A toggle rather than a typed box: the panel offers the brands the wardrobe
     * holds, so there is a chip to tap twice. Held as a string still, because that
     * is what a garment stores and what [garmentFilter] compares -- an empty one
     * means no brand filter, which is what dropping it produces.
     */
    fun withBrand(value: String): WardrobeQuery =
        copy(brand = if (brand.equals(value, ignoreCase = true)) "" else value)

    fun withSize(value: String): WardrobeQuery =
        copy(size = if (size.equals(value, ignoreCase = true)) "" else value)

    fun withSortToggled(): WardrobeQuery =
        copy(sort = if (sort == GarmentSort.NEWEST) GarmentSort.OLDEST else GarmentSort.NEWEST)
}

/**
 * Something counted somewhere else in the app, as a thing the wardrobe can show.
 *
 * Every number the app displays is a number *of garments*, and every one of them
 * is somewhere you might want to go. This is the vocabulary of those links: the
 * statistics charts and the home counts produce them, and
 * [WardrobeQuery.Companion.showing] turns each into the query that shows exactly
 * what was counted.
 *
 * A type carries its category because the statistics page prefixes its
 * subcategory keys for the same reason the filter needs both -- "Boots" appears
 * under more than one category.
 *
 * Not every number is a link. A count of *distinct* colours or brands counts
 * labels rather than garments, so there is no list of things behind it to show,
 * and a lifespan bar is one particular garment -- which is why that one opens the
 * garment rather than a filtered list.
 */
sealed interface WardrobeLink {
    data class Category(val id: String) : WardrobeLink
    data class Type(val category: String, val name: String) : WardrobeLink
    /** The value a garment stores, not the palette's name for it. */
    data class Colour(val value: String) : WardrobeLink
    /** As the wearer typed it. */
    data class Brand(val name: String) : WardrobeLink
    /** Garments no longer worn, which nothing else in the app shows. */
    data object Retired : WardrobeLink
}
