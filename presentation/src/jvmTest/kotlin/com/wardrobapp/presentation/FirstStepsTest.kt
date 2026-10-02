package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which rows are ticked, and whether the card is there at all.
 *
 * Worth its own test rather than left to the composable for the reason every
 * other decision in this module is: a row that ticks off a read that has not
 * finished says "you have added a garment" to somebody with an empty wardrobe,
 * and that is invisible in a screenshot of a wardrobe that has one.
 */
class FirstStepsTest {

    private fun steps(
        dismissed: Boolean = false,
        garments: Long? = 0,
        bulkAddUsed: Boolean = false,
        ratedOutfits: Long? = 0,
    ) = firstStepsFor(dismissed, garments, bulkAddUsed, ratedOutfits)

    @Test
    fun `a fresh install has nothing done and shows the card`() {
        val fresh = steps()

        assertEquals(emptySet(), fresh.done)
        assertFalse(fresh.isComplete)
        assertTrue(fresh.isVisible)
    }

    @Test
    fun `each row ticks off its own condition`() {
        assertEquals(setOf(FirstStep.GARMENT), steps(garments = 1).done)
        assertEquals(setOf(FirstStep.BULK_ADD), steps(bulkAddUsed = true).done)
        assertEquals(setOf(FirstStep.RATE), steps(ratedOutfits = 1).done)
    }

    @Test
    fun `a count that is not known yet ticks nothing`() {
        // Null is a read that has not finished or has failed, and it is not zero.
        // Both directions matter: nothing may tick from it, and nothing already
        // ticked may come unticked because a later read failed -- which is why the
        // card is written off for good once it is complete rather than recomputed
        // on every visit.
        assertEquals(emptySet(), steps(garments = null, ratedOutfits = null).done)
        assertTrue(steps(garments = null, ratedOutfits = null).isVisible)
    }

    @Test
    fun `the card goes once every job is done`() {
        val everything = steps(garments = 4, bulkAddUsed = true, ratedOutfits = 2)

        assertTrue(everything.isComplete)
        assertFalse(everything.isVisible)
    }

    @Test
    fun `two of three is not done`() {
        val nearly = steps(garments = 4, bulkAddUsed = true)

        assertFalse(nearly.isComplete)
        assertTrue(nearly.isVisible)
    }

    @Test
    fun `dismissing hides the card whatever is ticked`() {
        assertFalse(steps(dismissed = true).isVisible)
        assertFalse(steps(dismissed = true, garments = 3).isVisible)

        // Still reported honestly: the rows are what they are, and only the
        // showing of them was declined.
        assertEquals(setOf(FirstStep.GARMENT), steps(dismissed = true, garments = 3).done)
    }

    @Test
    fun `isDone answers per row`() {
        val one = steps(garments = 1)

        assertTrue(one.isDone(FirstStep.GARMENT))
        assertFalse(one.isDone(FirstStep.BULK_ADD))
        assertFalse(one.isDone(FirstStep.RATE))
    }
}
