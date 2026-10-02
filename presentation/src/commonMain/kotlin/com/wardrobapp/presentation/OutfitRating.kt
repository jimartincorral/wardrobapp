package com.wardrobapp.presentation

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a set of ratings adds up to.
 *
 * Ported from `src/domain/outfit-rating.ts` in the app this replaced. That module
 * existed because that app computed
 * this twice, differently: the outfit detail screen reduced the ratings it had
 * loaded and treated "none" as zero, while an unused service function asked
 * SQLite for `AVG(rating)` and treated "none" as null.
 */

/** The highest a rating goes, and so the most stars that can be filled. */
const val MAX_RATING = 5

data class RatingSummary(
    val count: Int,
    /** The mean, or null when there is nothing to average. */
    val average: Double?,
    /**
     * Stars to fill, 0 to [MAX_RATING].
     *
     * Rounded rather than truncated: an outfit rated 4 and 5 averages 4.5, and
     * showing four stars for that reads as the lower of the two opinions.
     */
    val stars: Int,
    /** The mean to one decimal place, or null when there is none. */
    val label: String?,
    /** Whether there is an average worth showing at all. */
    val showsAverage: Boolean,
)

fun ratingSummary(ratings: List<Int>): RatingSummary {
    // Ratings outside the scale cannot come from the star row, but they can come
    // from a restored backup or a hand-edited database, and an average of 9 would
    // fill more stars than exist. A zero means unrated rather than terrible.
    val usable = ratings.filter { it > 0 }

    if (usable.isEmpty()) {
        return RatingSummary(
            count = 0,
            average = null,
            stars = 0,
            label = null,
            showsAverage = false,
        )
    }

    val average = usable.sum().toDouble() / usable.size

    return RatingSummary(
        count = usable.size,
        average = average,
        stars = min(average, MAX_RATING.toDouble()).roundToInt(),
        label = meanToOneDecimalPlace(usable.sum(), usable.size),
        showsAverage = true,
    )
}

/**
 * The mean of [count] ratings totalling [sum], to one decimal place.
 *
 * Always a full stop as the separator, never the device's: half of Europe would
 * write 4,5, and a label compared in tests should not change with the phone it
 * runs on. The screen decides how to present it; this decides what it says.
 *
 * Integer arithmetic on the ratings themselves rather than formatting the mean
 * as a double. This was `String.format(Locale.ROOT, "%.1f", mean)`, which is
 * JVM-only, and it is the same answer: half up, on the exact value -- 4.25 is
 * 4.3 and 87/20 is 4.4 -- where a double would hand a formatter 4.3499...
 * for the second. `(20 * sum + count) / (2 * count)` is 10 * sum / count
 * rounded half up, exactly, because every rating is a whole number.
 */
private fun meanToOneDecimalPlace(sum: Int, count: Int): String {
    val tenths = (20L * sum + count) / (2L * count)
    return "${tenths / 10}.${tenths % 10}"
}
