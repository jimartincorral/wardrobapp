package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the analytics bars.
 *
 * Every number here is drawn as a width next to itself as a count, so the failure
 * mode is a screen that contradicts itself: a bar past the end of its track, or an
 * empty bar beside a number that is not zero. A NaN width is the worst of them --
 * it draws nothing at all while the count sits there.
 */
class AnalyticsViewTest {

    @Test
    fun `a bar is its share of the wardrobe`() {
        val view = analyticsView(
            totalItems = 10,
            archivedItems = 2,
            categoryCounts = listOf("tops" to 5L, "bottoms" to 3L, "shoes" to 2L),
            lifespans = emptyList(),
        )

        assertEquals(listOf(0.5, 0.3, 0.2), view.categories.map { it.fraction })
        assertEquals(listOf(5L, 3L, 2L), view.categories.map { it.value })
        assertEquals(10L, view.totalItems)
        assertEquals(2L, view.archivedItems)
    }

    @Test
    fun `the order the query returned is the order the chart draws`() {
        // Sorting again here would fight the query, which already ordered by
        // count -- and would silently disagree with it whenever two counts tie.
        val view = analyticsView(3, 0, listOf("shoes" to 1L, "tops" to 2L), emptyList())

        assertEquals(listOf("shoes", "tops"), view.categories.map { it.key })
    }

    @Test
    fun `an empty wardrobe draws nothing and says so`() {
        // Dividing by zero in floating point is a NaN rather than an error, and a
        // NaN width draws no bar at all.
        val view = analyticsView(0, 0, listOf("tops" to 0L), emptyList())

        assertTrue(view.isEmpty)
        assertEquals(listOf(0.0), view.categories.map { it.fraction })
        assertTrue(view.categories.none { it.fraction.isNaN() }, "a NaN reached the screen")
    }

    @Test
    fun `a wardrobe with something in it is not empty`() {
        assertTrue(!analyticsView(1, 0, emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `no bar can be drawn outside its track`() {
        // A count larger than the total should not happen, and if it does the
        // answer is a full bar rather than one that overflows its row.
        val view = analyticsView(
            totalItems = 2,
            archivedItems = 0,
            categoryCounts = listOf("tops" to 5L),
            lifespans = listOf(LifespanEntry("g1", "tops", listOf("Coat"), days = 3650)),
        )

        assertEquals(1.0, view.categories.single().fraction)
        assertEquals(1.0, view.lifespans.single().fraction)
        // The count itself is not clamped: the bar is a drawing, the number is the
        // truth.
        assertEquals(5L, view.categories.single().value)
    }

    @Test
    fun `a lifespan bar is full at a year`() {
        val view = analyticsView(
            totalItems = 3,
            archivedItems = 3,
            categoryCounts = emptyList(),
            lifespans = listOf(
                LifespanEntry("year", "tops", listOf("Coat"), days = LIFESPAN_FULL_BAR_DAYS),
                LifespanEntry("half", "tops", listOf("Shirt"), days = LIFESPAN_FULL_BAR_DAYS / 2),
            ),
        )

        assertEquals(1.0, view.lifespans[0].fraction)
        assertTrue(view.lifespans[1].fraction in 0.49..0.51)
    }

    @Test
    fun `a garment retired before it was bought still draws`() {
        // One edit away: a purchase date after the retirement date gives a
        // negative span. Clamped rather than dropped, because the number beside
        // the bar is still that garment's row.
        val view = analyticsView(
            totalItems = 1,
            archivedItems = 1,
            categoryCounts = emptyList(),
            lifespans = listOf(LifespanEntry("g1", "tops", listOf("Shirt"), days = -30)),
        )

        assertEquals(0.0, view.lifespans.single().fraction)
        assertEquals(-30L, view.lifespans.single().value)
    }

    @Test
    fun `keeps only as many lifespans as the chart has room for`() {
        val entries = (1..10).map {
            LifespanEntry("g$it", "tops", listOf("T-Shirt"), (100 - it).toLong())
        }

        val view = analyticsView(5, 10, emptyList(), entries)

        assertEquals(LIFESPAN_BARS, view.lifespans.size)
        assertEquals(listOf("g1", "g2", "g3"), view.lifespans.map { it.key })
    }
}
