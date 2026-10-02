package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the wardrobe is drawn, as rules rather than as layout.
 *
 * Two of these are about a value this app did not write: a preferences file from a
 * later build, or one edited by hand, must not be able to produce a wardrobe with
 * no columns or forty of them. The third is the one a person would notice -- that
 * looking at a list does not forget how wide their grid was.
 */
class WardrobeViewTest {

    @Test
    fun `a list is one garment across, a grid is what it was set to`() {
        assertEquals(1, WardrobeView(WardrobeLayout.LIST, columns = 4).cellsAcross)
        assertEquals(4, WardrobeView(WardrobeLayout.GRID, columns = 4).cellsAcross)
    }

    @Test
    fun `the grid's width survives a trip through the list`() {
        val wide = WardrobeView(WardrobeLayout.GRID, columns = 4)

        // The menu's own list entry carries the default width, and choosing it must
        // not overwrite the width that was chosen -- it says "show me a list".
        val asList = wide.withChoice(WardrobeView(WardrobeLayout.LIST))
        assertEquals(WardrobeLayout.LIST, asList.layout)
        assertEquals(4, asList.columns)

        // So going back to a grid of no particular width goes back to that one.
        assertEquals(4, asList.withChoice(WardrobeView(WardrobeLayout.GRID, 4)).columns)
    }

    @Test
    fun `nothing stored is the list this app has always had`() {
        assertEquals(WardrobeView(), wardrobeViewFor(null, null))
        assertEquals(DEFAULT_GRID_COLUMNS, wardrobeViewFor(null, null).columns)
    }

    @Test
    fun `an unreadable layout is the list rather than a guess`() {
        assertEquals(WardrobeLayout.LIST, wardrobeViewFor("carousel", 3).layout)
        assertEquals(WardrobeLayout.GRID, wardrobeViewFor(" GRID ", 3).layout)
    }

    @Test
    fun `a width that is not on offer is snapped to the nearest that is`() {
        // Rather than dropped: a stored 6 says something about the size that was
        // wanted, and 4 is the closest this app can show.
        assertEquals(4, wardrobeViewFor("grid", 6).columns)
        assertEquals(2, wardrobeViewFor("grid", 1).columns)
        assertEquals(3, wardrobeViewFor("grid", 3).columns)
    }

    @Test
    fun `the list is stored as the absence of a choice`() {
        assertEquals(null, WardrobeLayout.LIST.storedValue)
        assertEquals("grid", WardrobeLayout.GRID.storedValue)
    }

    @Test
    fun `the menu offers the list and every width, once each`() {
        assertEquals(1 + GRID_COLUMN_CHOICES.size, WARDROBE_VIEW_CHOICES.size)
        assertEquals(GRID_COLUMN_CHOICES, WARDROBE_VIEW_CHOICES.drop(1).map { it.columns })
        assertEquals(WardrobeLayout.LIST, WARDROBE_VIEW_CHOICES.first().layout)
    }

    @Test
    fun `which entry is ticked ignores the width when the choice is a list`() {
        val inList = WardrobeView(WardrobeLayout.LIST, columns = 4)
        assertTrue(WardrobeView(WardrobeLayout.LIST).isCurrent(inList))
        assertFalse(WardrobeView(WardrobeLayout.GRID, 4).isCurrent(inList))

        val inGrid = WardrobeView(WardrobeLayout.GRID, columns = 3)
        assertTrue(WardrobeView(WardrobeLayout.GRID, 3).isCurrent(inGrid))
        assertFalse(WardrobeView(WardrobeLayout.GRID, 2).isCurrent(inGrid))
    }
}
