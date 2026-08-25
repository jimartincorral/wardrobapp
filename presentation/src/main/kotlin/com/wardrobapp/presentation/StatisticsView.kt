package com.wardrobapp.presentation


/**
 * What the statistics page shows.
 *
 * Ported from `src/domain/statistics-view.ts` in the app this replaced. Counts
 * in, bars out: every bar's width is a division,
 * and a bar of the wrong length is wrong in a way nobody notices, which is why
 * the arithmetic came out of the React Native screen -- where it was inline, in
 * two places, and untested.
 *
 * Labels are deliberately absent. The screen resolves every key to text, and in
 * this app that text does not exist yet. Keys, counts, fractions and swatches
 * port; words do not.
 */

/** One (key, count) pair, as the distribution queries return them. */
data class Distribution(val key: String, val count: Long)

/** One bar: what it counts, and how much of the track to fill. */
data class StatBar(
    val key: String,
    val count: Long,
    /** 0 to 1. Never negative, never over 1. */
    val fraction: Double,
)

/** A colour's bar, with the swatch to draw beside it. */
data class ColorBar(
    val key: String,
    val count: Long,
    val fraction: Double,
    /**
     * A hex to fill with, or [MULTI_SWATCH] for the many-coloured swatch.
     *
     * A colour that is not in the palette keeps its stored value: it is a hex
     * already, just not one with a name.
     */
    val swatch: String,
)

enum class BrandSort { COUNT, ALPHA }

/** The key the distributions use for a garment with no subcategory recorded. */
const val NO_SUBCATEGORY = "__none__"

/** The swatch value meaning "many colours" rather than one. */
const val MULTI_SWATCH = "multi"

data class StatisticsView(
    /** Garments still worn, which is what every bar below counts. */
    val inUse: Long,
    /** Garments marked as no longer worn. Counted, never charted. */
    val retired: Long,
    val distinctCategories: Int,
    val distinctColors: Int,
    val distinctBrands: Int,
    val categories: List<StatBar>,
    val colors: List<ColorBar>,
    val brands: List<StatBar>,
    /**
     * Subcategory bars per category, keyed by category.
     *
     * Each group is scaled against its *own* largest bar rather than the category
     * chart's, so opening a small category still shows a readable spread instead
     * of four slivers.
     */
    val subcategories: Map<String, List<StatBar>>,
    /** The longest-lived of the retired garments, already scaled. */
    val lifespans: List<LifespanBar>,
) {

    /** The whole wardrobe: what is worn and what has been put away. */
    val items: Long get() = inUse + retired

    /**
     * True when there is nothing to measure at all.
     *
     * Over the whole wardrobe rather than what is in use, which is what it used to
     * be: a wardrobe of nothing but retired garments has lifespans to show and a
     * page that said "nothing to measure yet" over three filled bars would be
     * arguing with itself.
     */
    val isEmpty: Boolean get() = items <= 0L
}

fun statisticsView(
    inUse: Long,
    categories: List<Distribution>,
    colors: List<Distribution>,
    brands: List<Distribution>,
    subcategories: Map<String, List<Distribution>>,
    brandSort: BrandSort = BrandSort.COUNT,
    retired: Long = 0L,
    lifespans: List<LifespanEntry> = emptyList(),
): StatisticsView {
    val brandBars = bars(brands)

    return StatisticsView(
        inUse = inUse,
        retired = retired,
        distinctCategories = categories.size,
        distinctColors = colors.size,
        distinctBrands = brands.size,
        categories = bars(categories),
        colors = bars(colors).map { bar ->
            ColorBar(
                key = bar.key,
                count = bar.count,
                fraction = bar.fraction,
                swatch = swatchFor(bar.key),
            )
        },
        brands = if (brandSort == BrandSort.ALPHA) alphabetically(brandBars) else brandBars,
        subcategories = subcategories.mapValues { (category, subs) ->
            // Prefixed, because the same subcategory name appears under more than
            // one category and a list keyed on the bare name would collapse them.
            bars(subs).map { it.copy(key = "$category:${it.key}") }
        },
        // Scaled against a year rather than against these counts, so this one is
        // built where that scale is written down.
        lifespans = lifespanBars(lifespans),
    )
}

/** Scale a distribution against its own largest count. */
private fun bars(distribution: List<Distribution>): List<StatBar> {
    val max = distribution.maxOfOrNull { it.count } ?: 0L

    return distribution.map { StatBar(key = it.key, count = it.count, fraction = share(it.count, max)) }
}

/**
 * A count as a portion of the largest.
 *
 * Counts come from `COUNT(*)` so they should be whole and positive, but this is
 * used as a width and a negative would draw backwards -- which would not look
 * like a bug in a query.
 *
 * That one guard is all it takes, which is worth saying because the obvious
 * extra two are not: clamping to 1 cannot fire, since `max` is this list's own
 * largest and nothing exceeds it, and flooring `max` at 1 cannot fire either,
 * since a `max` of zero means every count was zero and this returned already.
 * Mutation testing found both, and dead guards that read as caution are worse
 * than none -- they suggest a case that was considered and handled.
 */
private fun share(count: Long, max: Long): Double {
    if (count <= 0) return 0.0
    return count.toDouble() / max
}

/**
 * What to draw beside a colour's bar.
 *
 * A named colour draws the palette's own hex rather than the stored one, so a
 * wardrobe holding both `#cc0000` and `#CC0000` shows one swatch and not two
 * spellings of it.
 */
private fun swatchFor(key: String): String {
    val named = paletteColorFor(key) ?: return key
    return if (named.first == MULTI_SWATCH) MULTI_SWATCH else named.second
}

/**
 * By name rather than by count.
 *
 * Case- and accent-insensitively, so accented brands sort where a reader expects
 * rather than after Z, which is where comparing raw characters puts them. This is
 * what JavaScript's `localeCompare` does, and it is compared against it.
 */
private fun alphabetically(brands: List<StatBar>): List<StatBar> =
    brands.sortedWith(compareBy(java.text.Collator.getInstance()) { it.key })
