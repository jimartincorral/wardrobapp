package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding a garment by typing at it.
 *
 * The field list is the whole content of this: a search that looks in five of the
 * six places somebody would reach for is a search that appears broken exactly
 * once, on the garment they were looking for. The list here has to stay in step
 * with the SQL in `GarmentQueries.allGarments`.
 */
class GarmentSearchTest {

    private fun garment(
        id: String = "g1",
        category: String = "tops",
        subcategory: String? = null,
        subcategories: List<String> = emptyList(),
        brand: String? = null,
        size: String? = null,
        tags: List<String> = emptyList(),
    ) = GarmentRecord(
        id = id,
        imageUri = "$id.jpg",
        imageUriNoBg = null,
        imageUris = listOf("$id.jpg"),
        imageUrisNoBg = emptyList(),
        category = category,
        subcategory = subcategory,
        subcategories = subcategories,
        tags = tags,
        brand = brand,
        colorPrimary = "#123456",
        colorSecondary = null,
        colorPalette = listOf("#123456"),
        size = size,
        purchaseDate = null,
        isAvailable = true,
        unavailableDate = null,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `an empty search is not a filter`() {
        assertTrue(garmentMatchesSearch(garment(), ""))
        assertTrue(garmentMatchesSearch(garment(), "   "))
    }

    @Test
    fun `every field somebody would reach for is searched`() {
        assertTrue(garmentMatchesSearch(garment(category = "shoes"), "shoes"), "category")
        assertTrue(garmentMatchesSearch(garment(subcategory = "Parka"), "parka"), "type")
        assertTrue(
            garmentMatchesSearch(garment(subcategories = listOf("Sneakers")), "sneak"),
            "types",
        )
        assertTrue(garmentMatchesSearch(garment(brand = "Acme"), "acme"), "brand")
        assertTrue(garmentMatchesSearch(garment(size = "XL"), "xl"), "size")
        assertTrue(garmentMatchesSearch(garment(tags = listOf("linen")), "linen"), "tag")
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertTrue(garmentMatchesSearch(garment(brand = "Acme"), "  ACME "))
    }

    @Test
    fun `part of a word is enough`() {
        // Typing is the point: a search that needs the whole word is a search you
        // have to already know the answer to.
        assertTrue(garmentMatchesSearch(garment(subcategory = "Windbreaker"), "break"))
    }

    @Test
    fun `a garment that has nothing to do with it does not match`() {
        assertFalse(garmentMatchesSearch(garment(category = "tops", brand = "Acme"), "boots"))
    }

    @Test
    fun `the colour is not searched`() {
        // Stored as a hex, which nobody types. Matching it would mean "123" finding
        // garments by their colour code.
        assertFalse(garmentMatchesSearch(garment(), "123456"))
    }

    @Test
    fun `the id is not searched`() {
        // Ids are UUIDs on a real phone, and a search that matches them turns a
        // typo into a result.
        assertFalse(garmentMatchesSearch(garment(id = "abc123"), "abc123"))
    }

    @Test
    fun `matches come back in the order they were given`() {
        // The picker groups them by category afterwards; a filter that reordered
        // would shuffle the rows under somebody's finger as they type.
        val wardrobe = listOf(
            garment(id = "a", brand = "Acme"),
            garment(id = "b", brand = "Other"),
            garment(id = "c", brand = "Acme"),
        )

        assertEquals(listOf("a", "c"), garmentsMatching(wardrobe, "acme").map { it.id })
    }

    @Test
    fun `a search nothing answers to comes back empty`() {
        assertEquals(emptyList(), garmentsMatching(listOf(garment()), "nothing here"))
    }
}
