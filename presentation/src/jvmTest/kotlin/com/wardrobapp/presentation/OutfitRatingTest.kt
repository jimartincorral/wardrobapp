package com.wardrobapp.presentation

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A property of the rating summary that a JVM test almost cannot see.
 *
 * The fixture is replayed on whatever JVM runs it, and that JVM's locale is
 * English -- so it compares the label without ever exercising the one thing about
 * the label that varies by device.
 */
class OutfitRatingTest {

    @Test
    fun `the label does not follow the device locale`() {
        val original = Locale.getDefault()
        try {
            // Half of Europe writes 4,5 for this number. The label is compared
            // against what the TypeScript's toFixed(1) produced, which is always
            // a dot, so following the device here would fail a fixture on a
            // Spanish phone and pass on the machine that generated it.
            //
            // Showing it the reader's way is a localization decision, and belongs
            // with the rest of localization rather than being smuggled in as a
            // formatting default.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("4.5", ratingSummary(listOf(4, 5)).label)
            assertEquals("3.0", ratingSummary(listOf(3)).label)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the label is what String-format wrote, for every mean ratings can have`() {
        // The label used to be String.format(Locale.ROOT, "%.1f", mean), which is
        // JVM-only, and is now worked out from the ratings themselves so it can be
        // common code. The two must agree for every mean the star row can produce
        // -- every count of ratings, every total those ratings can reach -- and
        // the ties are where they could differ: 4.25 and 87/20 are both halfway,
        // and a double cannot hold the second exactly.
        for (count in 1..60) {
            for (sum in count..(MAX_RATING * count)) {
                val ratings = List(count) { 1 }.toMutableList()
                var remaining = sum - count
                for (i in ratings.indices) {
                    val add = minOf(MAX_RATING - 1, remaining)
                    ratings[i] += add
                    remaining -= add
                }

                assertEquals(
                    String.format(java.util.Locale.ROOT, "%.1f", sum.toDouble() / count),
                    ratingSummary(ratings).label,
                    "$count ratings totalling $sum",
                )
            }
        }
    }
}
