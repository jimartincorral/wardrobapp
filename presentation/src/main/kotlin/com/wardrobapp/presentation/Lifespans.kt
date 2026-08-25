package com.wardrobapp.presentation

/**
 * How long the things you stopped wearing lasted.
 *
 * All that is left of `AnalyticsView`, which was the model for a second
 * statistics screen. The screens are one now, so the counts and bars it shared
 * with [StatisticsView] live there and this keeps the part that was only ever
 * its own: a span of days, drawn against a year.
 */

/** A garment as the lifespan chart needs it, which is only its name and its span. */
data class LifespanEntry(
    val garmentId: String,
    val category: String,
    val subcategories: List<String>,
    val days: Long,
)

/** One bar: the days it reports, and how much of the track to fill. */
data class LifespanBar(
    val key: String,
    val entry: LifespanEntry,
    val days: Long,
    /** 0 to 1. Never negative, never over 1, never NaN. */
    val fraction: Double,
)

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

/**
 * The longest-lived few, as bars.
 *
 * A share of a year, safe at the edges: a garment retired before the purchase
 * date recorded for it gives a negative span -- one edit away, and not something
 * a bar can draw. Clamped rather than dropped, since the number beside it is
 * still the row.
 */
fun lifespanBars(entries: List<LifespanEntry>): List<LifespanBar> =
    entries.take(LIFESPAN_BARS).map { entry ->
        LifespanBar(
            key = entry.garmentId,
            entry = entry,
            days = entry.days,
            fraction = (entry.days.toDouble() / LIFESPAN_FULL_BAR_DAYS).coerceIn(0.0, 1.0),
        )
    }
