package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the lifespan bars.
 *
 * Carried over from `AnalyticsViewTest` when the two statistics screens became
 * one: the category bars it also covered are `StatisticsViewTest`'s now, and these
 * are the cases that were only ever about a span of days.
 *
 * Each number is drawn as a width next to itself as a count, so the failure mode
 * is a chart that contradicts itself -- a bar past the end of its track, or an
 * empty bar beside a number that is not zero.
 */
class LifespansTest {

    @Test
    fun `a bar is full at a year`() {
        val bars = lifespanBars(
            listOf(
                LifespanEntry("year", "tops", listOf("Coat"), days = LIFESPAN_FULL_BAR_DAYS),
                LifespanEntry("half", "tops", listOf("Shirt"), days = LIFESPAN_FULL_BAR_DAYS / 2),
            )
        )

        assertEquals(1.0, bars[0].fraction)
        assertTrue(bars[1].fraction in 0.49..0.51)
    }

    @Test
    fun `a garment owned longer than a year does not draw past its track`() {
        val bars = lifespanBars(listOf(LifespanEntry("old", "tops", listOf("Coat"), days = 3_650)))

        assertEquals(1.0, bars.single().fraction)
        // The count itself is not clamped: the bar is a drawing, the number is the
        // truth.
        assertEquals(3_650L, bars.single().days)
    }

    @Test
    fun `a garment retired before it was bought still draws`() {
        // One edit away: a purchase date after the retirement date gives a negative
        // span. Clamped rather than dropped, because the number beside the bar is
        // still that garment's row.
        val bars = lifespanBars(listOf(LifespanEntry("g1", "tops", listOf("Shirt"), days = -30)))

        assertEquals(0.0, bars.single().fraction)
        assertEquals(-30L, bars.single().days)
    }

    @Test
    fun `keeps only as many as the chart has room for, in the order given`() {
        val entries = (1..10).map {
            LifespanEntry("g$it", "tops", listOf("T-Shirt"), (100 - it).toLong())
        }

        val bars = lifespanBars(entries)

        assertEquals(LIFESPAN_BARS, bars.size)
        assertEquals(listOf("g1", "g2", "g3"), bars.map { it.key })
    }

    @Test
    fun `nothing retired is no bars rather than an empty one`() {
        assertTrue(lifespanBars(emptyList()).isEmpty())
    }
}
