package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the wardrobe screen is narrowing by.
 *
 * The two things worth pinning are the ones the React Native screen computes
 * inline and never tests: what counts as an active filter, and what "clear all"
 * clears. Both are visible to the user -- a badge with the wrong number, or a
 * clear that leaves something behind -- and neither can fail loudly.
 */
class WardrobeQueryTest {

    // ---- what counts as narrowed --------------------------------------------

    @Test
    fun `a fresh query is not narrowing anything`() {
        val query = WardrobeQuery()
        assertEquals(0, query.activeFilterCount)
        assertFalse(query.isNarrowed)
    }

    @Test
    fun `each dimension counts once`() {
        assertEquals(1, WardrobeQuery(category = "tops").activeFilterCount)
        assertEquals(1, WardrobeQuery(subcategory = "shirt").activeFilterCount)
        assertEquals(1, WardrobeQuery(season = Season.WINTER).activeFilterCount)
        assertEquals(1, WardrobeQuery(occasion = Occasion.FORMAL).activeFilterCount)
        assertEquals(1, WardrobeQuery(brand = "Uniqlo").activeFilterCount)
        assertEquals(1, WardrobeQuery(size = "M").activeFilterCount)
        assertEquals(1, WardrobeQuery(color = "navy").activeFilterCount)
        assertEquals(1, WardrobeQuery(search = "wool").activeFilterCount)
        assertEquals(1, WardrobeQuery(includeRetired = true).activeFilterCount)
    }

    @Test
    fun `they add up`() {
        val query = WardrobeQuery(
            category = "tops",
            season = Season.WINTER,
            brand = "Uniqlo",
            search = "wool",
            includeRetired = true,
        )
        assertEquals(5, query.activeFilterCount)
        assertTrue(query.isNarrowed)
    }

    @Test
    fun `a box with only spaces in it is not a filter`() {
        // Typing and deleting leaves an empty box, and a space is what a stray
        // keystroke leaves. Neither is narrowing anything.
        assertEquals(0, WardrobeQuery(search = "   ").activeFilterCount)
        assertEquals(0, WardrobeQuery(brand = " ").activeFilterCount)
        assertEquals(0, WardrobeQuery(size = "\t").activeFilterCount)
    }

    @Test
    fun `the default sort does not count but the other one does`() {
        assertEquals(0, WardrobeQuery(sort = GarmentSort.NEWEST).activeFilterCount)
        assertEquals(1, WardrobeQuery(sort = GarmentSort.OLDEST).activeFilterCount)
    }

    // ---- what reaches the predicates ----------------------------------------

    @Test
    fun `a blank box never becomes a search for nothing`() {
        assertNull(WardrobeQuery(search = "  ").searchTerm)
        assertNull(WardrobeQuery(brand = "  ").garmentFilter().brand)
        assertNull(WardrobeQuery(size = "").garmentFilter().size)
    }

    @Test
    fun `the term is trimmed`() {
        assertEquals("wool", WardrobeQuery(search = "  wool  ").searchTerm)
        assertEquals("Uniqlo", WardrobeQuery(brand = " Uniqlo ").garmentFilter().brand)
    }

    @Test
    fun `the filter carries the dimensions the database cannot apply`() {
        val filter = WardrobeQuery(
            category = "tops",
            subcategory = "shirt",
            season = Season.WINTER,
            occasion = Occasion.FORMAL,
            brand = "Uniqlo",
            size = "M",
            color = "navy",
        ).garmentFilter()

        assertEquals("shirt", filter.subcategory)
        assertEquals(Season.WINTER, filter.season)
        assertEquals(Occasion.FORMAL, filter.occasion)
        assertEquals("Uniqlo", filter.brand)
        assertEquals("M", filter.size)
        assertEquals("navy", filter.color)
    }

    @Test
    fun `a brand or size is picked, and tapping it again drops it`() {
        val picked = WardrobeQuery().withBrand("Uniqlo").withSize("M")

        assertEquals("Uniqlo", picked.brand)
        assertEquals("M", picked.size)

        // The same value again clears it, whatever case the chip was drawn in --
        // one wardrobe holds "Uniqlo" and "uniqlo" and the panel offers one chip.
        assertEquals("", picked.withBrand("uniqlo").brand)
        assertEquals("", picked.withSize("m").size)

        // A different value replaces rather than clears.
        assertEquals("Arket", picked.withBrand("Arket").brand)
    }

    @Test
    fun `category is not in the filter, because the query applies it`() {
        // It is a plain column, so it belongs in the SQL rather than in a pass
        // over every row. Having it in both would filter twice.
        assertEquals(GarmentFilter(), WardrobeQuery(category = "tops").garmentFilter())
    }

    // ---- clearing ------------------------------------------------------------

    @Test
    fun `clearing puts everything back, sort and search included`() {
        val query = WardrobeQuery(
            search = "wool",
            sort = GarmentSort.OLDEST,
            category = "tops",
            subcategory = "shirt",
            season = Season.WINTER,
            occasion = Occasion.FORMAL,
            brand = "Uniqlo",
            size = "M",
            color = "navy",
            includeRetired = true,
        )

        assertEquals(WardrobeQuery(), query.cleared())
        assertEquals(0, query.cleared().activeFilterCount)
    }

    // ---- toggling ------------------------------------------------------------

    @Test
    fun `tapping the chosen category again drops it`() {
        val chosen = WardrobeQuery().withCategory("tops")
        assertEquals("tops", chosen.category)
        assertNull(chosen.withCategory("tops").category)
    }

    @Test
    fun `changing category drops the subcategory`() {
        // A type only means something inside a category, so keeping it would
        // filter by something the new category does not have and show nothing.
        val query = WardrobeQuery().withCategory("tops").withSubcategory("shirt")
        assertEquals("shirt", query.subcategory)

        assertNull(query.withCategory("bottoms").subcategory)
        // Including when the category is being cleared rather than changed.
        assertNull(query.withCategory("tops").subcategory)
    }

    @Test
    fun `the other dimensions toggle off when tapped twice`() {
        assertNull(WardrobeQuery().withSubcategory("shirt").withSubcategory("shirt").subcategory)
        assertNull(WardrobeQuery().withSeason(Season.WINTER).withSeason(Season.WINTER).season)
        assertNull(WardrobeQuery().withOccasion(Occasion.SPORT).withOccasion(Occasion.SPORT).occasion)
        assertNull(WardrobeQuery().withColor("navy").withColor("navy").color)
    }

    @Test
    fun `tapping a different value replaces rather than clears`() {
        assertEquals(Season.SUMMER, WardrobeQuery().withSeason(Season.WINTER).withSeason(Season.SUMMER).season)
        assertEquals("navy", WardrobeQuery().withColor("red").withColor("navy").color)
    }

    @Test
    fun `sort goes back and forth`() {
        val once = WardrobeQuery().withSortToggled()
        assertEquals(GarmentSort.OLDEST, once.sort)
        assertEquals(GarmentSort.NEWEST, once.withSortToggled().sort)
    }
}
