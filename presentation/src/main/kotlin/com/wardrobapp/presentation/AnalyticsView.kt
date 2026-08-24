package com.wardrobapp.presentation

/**
 * What the analytics screen shows.
 *
 * Counts and lifespans in, bars out. The arithmetic is the part worth being sure
 * about: a bar of the wrong length is wrong in a way nobody notices, and it is
 * the only thing on that screen that can be wrong at all.
 */

/** A garment as the lifespan chart needs it, which is only its name. */
data class LifespanEntry(
    val garmentId: String,
    val category: String,
    val subcategories: List<String>,
    val days: Long,
)

/** One bar: the number it reports, and how much of the track to fill. */
sealed interface Bar {
    val key: String
    val value: Long

    /** 0 to 1. Never negative, never over 1, never NaN. */
    val fraction: Double
}

data class CategoryBar(
    override val key: String,
    val category: String,
    override val value: Long,
    override val fraction: Double,
) : Bar

data class LifespanBar(
    override val key: String,
    val entry: LifespanEntry,
    override val value: Long,
    override val fraction: Double,
) : Bar

/**
 * A lifespan bar is full at a year.
 *
 * An arbitrary scale, but a readable one: most garments people retire have been
 * owned for months, and against a longest-owned-garment scale everything else
 * would be a sliver.
 */
const val LIFESPAN_FULL_BAR_DAYS = 365L

/** How many lifespans the chart has room for. */
const val LIFESPAN_BARS = 3

data class AnalyticsView(
    val totalItems: Long,
    val archivedItems: Long,
    val categories: List<CategoryBar>,
    val lifespans: List<LifespanBar>,
    /** True for a wardrobe with nothing in it, which is what the nudge turns on. */
    val isEmpty: Boolean,
)

/**
 * A share of a whole, safe at the edges.
 *
 * An empty wardrobe divides by zero -- which in floating point is not a rounding
 * problem but a NaN, and a NaN width draws nothing at all while the count sits
 * beside it, so the screen contradicts itself.
 */
private fun share(value: Long, total: Long): Double {
    if (total <= 0L) return 0.0
    return (value.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
}

fun analyticsView(
    totalItems: Long,
    archivedItems: Long,
    categoryCounts: List<Pair<String, Long>>,
    lifespans: List<LifespanEntry>,
): AnalyticsView = AnalyticsView(
    totalItems = totalItems,
    archivedItems = archivedItems,
    // Order comes from the query -- by count, descending -- and re-sorting here
    // would fight it.
    categories = categoryCounts.map { (category, count) ->
        CategoryBar(
            key = category,
            category = category,
            value = count,
            fraction = share(count, totalItems),
        )
    },
    lifespans = lifespans.take(LIFESPAN_BARS).map { entry ->
        LifespanBar(
            key = entry.garmentId,
            entry = entry,
            value = entry.days,
            // A garment retired before the purchase date recorded for it gives a
            // negative span -- one edit away, and not something a bar can draw.
            // Clamped rather than dropped: the number beside it is still the row.
            fraction = share(entry.days, LIFESPAN_FULL_BAR_DAYS),
        )
    },
    isEmpty = totalItems == 0L,
)
