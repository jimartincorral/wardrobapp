package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a star rating becomes a learned affinity between two garments.
 *
 * The whole of the app's learning is here: an exponential moving average per
 * pair, and the inverse step that undoes a rating the user is correcting. Small
 * enough to state exactly, which is what these tests do -- they replaced a
 * 222-case corpus recorded from the TypeScript this was ported from, and lose
 * nothing by it, because the arithmetic has closed forms worth writing down.
 */
class PairLearningTest {

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) < 1e-12, "$what: expected $expected, got $actual")
    }

    @Test
    fun `three stars is neutral, and the ends are the ends`() {
        assertEquals(-1.0, normalizeRating(1))
        assertEquals(-0.5, normalizeRating(2))
        assertEquals(0.0, normalizeRating(3))
        assertEquals(0.5, normalizeRating(4))
        assertEquals(1.0, normalizeRating(5))
    }

    @Test
    fun `a first rating starts from nothing`() {
        // No existing score means the average has nothing to average against, so
        // the step reduces to the rating's own share.
        val rated = foldRatingIntoPair(null, 5)

        assertClose(PAIR_LEARNING_RATE, rated.score, "a first five")
        assertEquals(1, rated.wearCount)

        val disliked = foldRatingIntoPair(null, 1)
        assertClose(-PAIR_LEARNING_RATE, disliked.score, "a first one")
        assertEquals(1, disliked.wearCount)

        // Three stars is an opinion too: it says "neutral", and neutral moves a
        // learned score back towards zero rather than leaving it alone.
        assertClose(0.0, foldRatingIntoPair(null, 3).score, "a first three")
    }

    @Test
    fun `each rating moves the score by the learning rate`() {
        val once = foldRatingIntoPair(null, 5)
        val twice = foldRatingIntoPair(once, 5)

        // 0.3, then 0.3*0.7 + 1*0.3 = 0.51.
        assertClose(0.51, twice.score, "a second five")
        assertEquals(2, twice.wearCount)

        // It converges on the rating rather than jumping to it: ten fives get
        // close to 1 without reaching it, which is what makes one bad rating
        // recoverable.
        var state = foldRatingIntoPair(null, 5)
        repeat(9) { state = foldRatingIntoPair(state, 5) }

        assertTrue(state.score > 0.97, "ten fives only reached ${state.score}")
        assertTrue(state.score < 1.0, "the average reached its limit exactly")
        assertEquals(10, state.wearCount)
    }

    @Test
    fun `a correction undoes the rating it replaces`() {
        // Stated directly: rating 5 then correcting to 1 must land exactly where
        // rating 1 alone would have. The EMA step inverts, which is why the app
        // can let someone change their mind without training on both answers.
        val rated5 = foldRatingIntoPair(null, 5)
        val corrected = foldRatingIntoPair(rated5, 1, previous = 5)
        val rated1 = foldRatingIntoPair(null, 1)

        assertClose(rated1.score, corrected.score, "correcting five to one")
        assertEquals(
            rated5.wearCount,
            corrected.wearCount,
            "a correction must not count another wear",
        )
    }

    @Test
    fun `a correction is undone from history, not from the latest value`() {
        // The harder case: a pair rated 5, then 2, and the 2 corrected to 4. The
        // 2 has to come out of the middle of the average, not off the end.
        val first = foldRatingIntoPair(null, 5)
        val second = foldRatingIntoPair(first, 2)
        val corrected = foldRatingIntoPair(second, 4, previous = 2)

        val asIfRatedCorrectly = foldRatingIntoPair(first, 4)

        assertClose(asIfRatedCorrectly.score, corrected.score, "correcting the middle rating")
        assertEquals(second.wearCount, corrected.wearCount)
    }

    @Test
    fun `an unchanged correction leaves the score alone`() {
        val rated = foldRatingIntoPair(null, 4)
        val recorrected = foldRatingIntoPair(rated, 4, previous = 4)

        assertClose(rated.score, recorrected.score, "correcting four to four")
        assertEquals(rated.wearCount, recorrected.wearCount)
    }

    @Test
    fun `an outfit teaches every pair in it, once, in a stable order`() {
        val pairs = garmentPairs(listOf("c", "a", "b"))

        // Three garments make three pairs, each with its ids in key order so the
        // same pair is stored once however the outfit happened to list it.
        assertEquals(listOf("a" to "c", "b" to "c", "a" to "b"), pairs)
        assertEquals(pairs.size, pairs.toSet().size)

        for ((a, b) in pairs) {
            assertTrue(a <= b, "$a|$b is not in key order")
        }
    }

    @Test
    fun `an outfit of one teaches nothing`() {
        assertEquals(emptyList(), garmentPairs(listOf("only")))
        assertEquals(emptyList(), garmentPairs(emptyList()))
    }

    @Test
    fun `four garments make six pairs`() {
        // n(n-1)/2, and worth pinning: an off-by-one here would quietly stop the
        // app learning about one pair in every outfit.
        assertEquals(6, garmentPairs(listOf("a", "b", "c", "d")).size)
    }
}
