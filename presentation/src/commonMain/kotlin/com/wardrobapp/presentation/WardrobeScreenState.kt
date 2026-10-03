package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord

/**
 * What the wardrobe screen is given to draw.
 *
 * Was `WardrobeViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 */

data class WardrobeScreenState(
    val loading: Boolean = true,
    val garments: List<GarmentRecord> = emptyList(),
    /** Everything the screen is narrowing by. */
    val query: WardrobeQuery = WardrobeQuery(),
    /** Whether the filter panel is open. Kept here so it survives a tab switch. */
    val filtersExpanded: Boolean = false,
    /**
     * Set when loading failed. Shown rather than swallowed: the React Native
     * app logged the error and left the list at its previous value, so a
     * failure looked exactly like an empty wardrobe.
     */
    val error: String? = null,
    /**
     * Rows or cells, and how many across.
     *
     * Alongside the query rather than inside it: it changes how the same
     * garments are drawn, not which ones they are, so it never triggers a
     * re-read. Persisted, so it is the same wardrobe you left.
     */
    val view: WardrobeView = WardrobeView(),
    /**
     * What a grid cell says under its photo.
     *
     * Beside [view] and for the same reason: it changes what the same garments
     * are labelled with, not which ones they are, so it never triggers a
     * re-read either. Persisted alongside the layout.
     */
    val caption: GarmentCaption = GarmentCaption.BRAND,
    /**
     * What the filter panel has to offer, from what the list holds.
     *
     * Derived when the list is, rather than in the composable: which values a
     * wardrobe contains is a fact about the wardrobe, and a screen that worked
     * it out per frame would recompute it on every scroll.
     */
    val facets: WardrobeFacets = WardrobeFacets(),
) {
    /** True only when the wardrobe really is empty, not when a read failed. */
    val isEmpty: Boolean get() = !loading && error == null && garments.isEmpty()

    /**
     * True when nothing matched but something would have.
     *
     * Worth distinguishing: "no garments yet" and "nothing matches these
     * filters" call for different things to do next, and the second is
     * reached by narrowing rather than by having an empty wardrobe.
     */
    val isFilteredEmpty: Boolean get() = isEmpty && query.isNarrowed
}
