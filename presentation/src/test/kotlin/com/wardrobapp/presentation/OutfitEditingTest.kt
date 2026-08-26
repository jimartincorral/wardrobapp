package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of building an outfit and changing one.
 *
 * One state serves both, so the cases that matter are the ones where "build" and
 * "edit" could quietly diverge: what an untitled outfit ends up called, what
 * order its garments are in, and whether a stored value this app does not
 * recognise can still be got rid of.
 */
class OutfitEditingTest {

    private fun garment(id: String, category: String, subcategory: String? = null) = GarmentRecord(
        id = id,
        imageUri = "$id.jpg",
        imageUriNoBg = null,
        imageUris = listOf("$id.jpg"),
        imageUrisNoBg = emptyList(),
        category = category,
        subcategory = subcategory,
        subcategories = listOfNotNull(subcategory),
        tags = emptyList(),
        brand = null,
        colorPrimary = "#000000",
        colorSecondary = null,
        colorPalette = listOf("#000000"),
        size = null,
        purchaseDate = null,
        isAvailable = true,
        unavailableDate = null,
        createdAt = null,
        updatedAt = null,
    )

    private val wardrobe = listOf(
        garment("g1", "tops", "Shirt"),
        garment("g2", "bottoms", "Jeans"),
        garment("g3", "shoes"),
    )

    @Test
    fun `an outfit with nothing in it cannot be saved`() {
        assertFalse(OutfitEditState().canSave)
    }

    @Test
    fun `one garment is an outfit`() {
        // The engine builds single-garment outfits out of a dress, so refusing one
        // here would make the builder stricter than the thing it replaces.
        assertTrue(OutfitEditState().withGarmentToggled("g1").canSave)
    }

    @Test
    fun `garments stay in the order they were picked`() {
        // Not wardrobe order: re-sorting under somebody's finger is how a list
        // becomes hard to use, and this order is what the outfit then lists.
        val state = OutfitEditState()
            .withGarmentToggled("g3")
            .withGarmentToggled("g1")
            .withGarmentToggled("g2")

        assertEquals(listOf("g3", "g1", "g2"), state.garmentIds)
    }

    @Test
    fun `a garment tapped twice is not in the outfit`() {
        val state = OutfitEditState().withGarmentToggled("g1").withGarmentToggled("g1")

        assertFalse(state.holds("g1"))
        assertFalse(state.canSave)
    }

    @Test
    fun `taking one garment out leaves the others where they were`() {
        val state = OutfitEditState()
            .withGarmentToggled("g1")
            .withGarmentToggled("g2")
            .withGarmentToggled("g3")
            .withGarmentToggled("g2")

        assertEquals(listOf("g1", "g3"), state.garmentIds)
    }

    @Test
    fun `a typed name is what the outfit is called`() {
        val state = OutfitEditState(name = "  Monday  ").withGarmentToggled("g1")

        assertEquals("Monday", state.nameFor(wardrobe))
    }

    @Test
    fun `an untitled outfit is named from its garments, in their order`() {
        // The same name a suggestion would get, so an outfit built by hand and left
        // untitled sits in the list looking like every other outfit.
        val state = OutfitEditState()
            .withGarmentToggled("g2")
            .withGarmentToggled("g1")
            .withGarmentToggled("g3")

        assertEquals("Jeans + Shirt + shoes", state.nameFor(wardrobe))
    }

    @Test
    fun `a name of only spaces is not a name`() {
        val state = OutfitEditState(name = "   ").withGarmentToggled("g1")

        assertEquals("Shirt", state.nameFor(wardrobe))
    }

    @Test
    fun `a garment that has gone is left out of the name`() {
        // An outfit can hold an id whose garment was deleted, and a name with a gap
        // in it is worse than a shorter name.
        val state = OutfitEditState().withGarmentToggled("g1").withGarmentToggled("missing")

        assertEquals("Shirt", state.nameFor(wardrobe))
    }

    @Test
    fun `the chosen garments come back in the order they were chosen`() {
        val state = OutfitEditState().withGarmentToggled("g3").withGarmentToggled("g1")

        assertEquals(listOf("g3", "g1"), state.chosen(wardrobe).map { it.id })
    }

    @Test
    fun `tapping the chosen occasion clears it`() {
        // An outfit that is not for anything in particular has to be expressible.
        val state = OutfitEditState().withOccasion(Occasion.WORK)

        assertEquals(Occasion.WORK, state.occasion)
        assertNull(state.withOccasion(Occasion.WORK).occasion)
        assertEquals(Occasion.FORMAL, state.withOccasion(Occasion.FORMAL).occasion)
    }

    @Test
    fun `tapping the chosen season clears it`() {
        val state = OutfitEditState().withSeason(Season.WINTER)

        assertEquals(Season.WINTER, state.season)
        assertNull(state.withSeason(Season.WINTER).season)
    }

    @Test
    fun `an existing outfit starts an edit as it was stored`() {
        val state = outfitEditStateOf(
            name = "Monday",
            garmentIds = listOf("g1", "g2"),
            occasion = "work",
            season = "winter",
        )

        assertEquals("Monday", state.name)
        assertEquals(listOf("g1", "g2"), state.garmentIds)
        assertEquals(Occasion.WORK, state.occasion)
        assertEquals(Season.WINTER, state.season)
    }

    @Test
    fun `a stored value nothing can show becomes nothing`() {
        // From an older build or a restored backup. Kept as-is it would be a value
        // no row of chips can display, which is a value nobody can change.
        val state = outfitEditStateOf(
            name = "Odd",
            garmentIds = listOf("g1"),
            occasion = "brunch",
            season = "monsoon",
        )

        assertNull(state.occasion)
        assertNull(state.season)
    }
}
