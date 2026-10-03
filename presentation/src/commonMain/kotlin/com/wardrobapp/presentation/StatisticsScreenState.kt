package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapWithPhotos

/**
 * What the statistics screen is given to draw.
 *
 * Was `StatisticsViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 */

data class StatisticsScreenState(
    val loading: Boolean = true,
    val view: StatisticsView? = null,
    /** Reported rather than swallowed: an empty chart is not a failed read. */
    val error: String? = null,
    /**
     * Which categories are showing their subcategory breakdown.
     *
     * Here rather than in :presentation because it is not part of the answer:
     * which rows are open says nothing about what they contain, and the pure
     * module stays a function of the wardrobe alone.
     */
    val expanded: Set<String> = emptySet(),
    /**
     * Which sections are showing their bars.
     *
     * Empty to begin with, which is the page shut: tiles, then four headings.
     * Not persisted -- unlike the wardrobe's layout, this is where you are in
     * a page rather than how you like it drawn, and it survives a tab switch
     * for the same reason [expanded] does.
     */
    val openSections: Set<StatisticsSection> = emptySet(),
    val brandSort: BrandSort = BrandSort.COUNT,
    /**
     * Garments that look like each other, or null before anyone has asked.
     *
     * Beside the counts rather than inside [StatisticsView], because it is not
     * arithmetic over the wardrobe: it is a comparison of every garment with
     * every other, and the pure view builder stays a function of the tallies.
     *
     * Null rather than empty, and the difference is the whole reason this is
     * lazy: "nobody has looked yet" and "nothing in your wardrobe matches" are
     * different answers, and showing the second while the first is true tells
     * somebody their wardrobe is clean when nothing has checked.
     */
    val duplicates: List<DuplicateGarmentGroup>? = null,
    /**
     * What the wardrobe cannot finish, or null before anyone has asked.
     *
     * Lazy and null-until-asked for the same reasons as [duplicates], only
     * more so: the analysis runs the suggestion engine once per candidate
     * garment, which is the most expensive thing this app computes. Paying
     * for it on every return to the tab, to fill in a section most visits
     * never open, would make the whole page wait.
     *
     * Null rather than empty because the two are different answers, and the
     * wrong one is worse here than anywhere else on the page: "your wardrobe
     * has no gaps" is a claim, and showing it before anything has looked
     * would be making that claim on no evidence.
     */
    val gaps: List<GapWithPhotos>? = null,
)

/** The parts of the page that open and shut, all shut to begin with. */
enum class StatisticsSection { CATEGORY, COLOUR, BRAND, LIFESPAN, GAPS, DUPLICATES }
