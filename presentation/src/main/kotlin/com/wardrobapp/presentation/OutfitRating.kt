package com.wardrobapp.presentation

import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a set of ratings adds up to.
 *
 * Ported from `src/domain/outfit-rating.ts` and held to it by
 * `outfit-rating.jsonl`. That module exists because the React Native app computed
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
        label = oneDecimalPlace(average),
        showsAverage = true,
    )
}

/**
 * A number to one decimal place, the way JavaScript's `toFixed(1)` writes it.
 *
 * Pinned to [Locale.ROOT] rather than the device's: this is compared against what
 * the TypeScript produced, and half of Europe would render the separator as a
 * comma and fail a fixture for a reason that has nothing to do with the
 * arithmetic. The screen decides how to present it; this decides what it says.
 */
private fun oneDecimalPlace(value: Double): String =
    String.format(Locale.ROOT, "%.1f", value)
