package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a garment is for, and which seasons its type implies.
 *
 * Two lookup tables with fallbacks, which is exactly the shape where a mistake
 * hides: a subcategory missing from the table quietly becomes "casual", and
 * nothing about the screen says so. These tests pin the fallbacks rather than the
 * whole table -- the table is data, the fallbacks are decisions. They stand in
 * for 700 recorded category/subcategory pairings, whose oracle went with the app
 * it was recorded from.
 */
class OccasionsTest {

    @Test
    fun `a type that says something is taken at its word`() {
        assertEquals(listOf(Occasion.FORMAL), occasionsFor("dresses", listOf("Cocktail")))
        assertEquals(listOf(Occasion.WORK, Occasion.FORMAL), occasionsFor("tops", listOf("Blouse")))
    }

    @Test
    fun `several types are merged, in the app's own order`() {
        val occasions = occasionsFor("tops", listOf("Blouse", "T-Shirt"))

        assertTrue(Occasion.FORMAL in occasions && Occasion.CASUAL in occasions)
        // The order is the enum's, not the order the subcategories arrived in, so
        // a chip row does not reshuffle when a garment is edited.
        assertEquals(Occasion.entries.filter { it in occasions }, occasions)
    }

    @Test
    fun `a type nobody has mapped falls back to the category`() {
        assertEquals(listOf(Occasion.SPORT), occasionsFor("activewear", listOf("Unmapped Thing")))
        assertEquals(listOf(Occasion.LOUNGE), occasionsFor("loungewear", listOf("Unmapped Thing")))
    }

    @Test
    fun `a category nobody has mapped falls back to casual`() {
        assertEquals(listOf(Occasion.CASUAL), occasionsFor("tops", null))
        assertEquals(listOf(Occasion.CASUAL), occasionsFor("tops", emptyList()))
        assertEquals(listOf(Occasion.CASUAL), occasionsFor("spacesuits", listOf("Helmet")))
    }

    @Test
    fun `underwear is deliberately for no occasion at all`() {
        // Not an oversight in the table: an outfit occasion is a thing you dress
        // *for*, and this category is not one. The empty list is the answer.
        assertEquals(emptyList(), occasionsFor("underwear", emptyList()))
        assertEquals(emptyList(), occasionsFor("underwear", listOf("Unmapped Thing")))
    }

    @Test
    fun `a garment reads its own occasions off its subcategories`() {
        val garment = Garment(
            id = "g1",
            category = "tops",
            subcategory = "Blouse",
            colorPrimary = "#FFFFFF",
        )

        assertEquals(listOf(Occasion.WORK, Occasion.FORMAL), garment.occasions())
    }

    @Test
    fun `a type only implies a season when it genuinely says one`() {
        assertEquals(listOf(Season.SUMMER), seasonsForSubcategories(listOf("Sandals")))
        assertEquals(listOf(Season.FALL, Season.WINTER), seasonsForSubcategories(listOf("Sweater")))

        // Nothing rather than all-season: the form fills itself in from this, and
        // "no opinion" must not become a choice the user did not make.
        assertEquals(emptyList(), seasonsForSubcategories(listOf("T-Shirt")))
        assertEquals(emptyList(), seasonsForSubcategories(emptyList()))
    }

    @Test
    fun `several types merge their seasons, in the app's own order`() {
        val seasons = seasonsForSubcategories(listOf("Boots", "Sandals"))

        assertEquals(Season.entries.filter { it in seasons }, seasons)
        assertTrue(Season.SUMMER in seasons && Season.WINTER in seasons)
    }
}
