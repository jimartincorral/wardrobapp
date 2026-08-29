package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one line under a garment's photo.
 *
 * Weighted towards the fallbacks rather than the happy path, because the happy
 * path is visible the moment anybody opens the grid and a fallback is not: the
 * failure this guards against is a cell whose line is blank, which happens on
 * exactly the garments that have not got the field asked for and nowhere else.
 */
class GarmentCaptionTest {

    @Test
    fun `the brand is what the cells shipped as, so it is what an empty setting means`() {
        assertEquals(GarmentCaption.BRAND, garmentCaptionFor(null))
        assertEquals(null, GarmentCaption.BRAND.storedValue)
    }

    @Test
    fun `a value this build does not know reads as the brand rather than failing`() {
        // A preferences file written by a later build, or edited by hand. The
        // wardrobe still draws.
        assertEquals(GarmentCaption.BRAND, garmentCaptionFor("colour"))
        assertEquals(GarmentCaption.BRAND, garmentCaptionFor(""))
    }

    @Test
    fun `every choice survives being stored and read back`() {
        for (choice in GarmentCaption.entries) {
            assertEquals(choice, garmentCaptionFor(choice.storedValue), "$choice")
        }
    }

    @Test
    fun `a stored value is read whatever case or spacing it arrives in`() {
        assertEquals(GarmentCaption.TYPE, garmentCaptionFor("  TYPE "))
        assertEquals(GarmentCaption.CATEGORY, garmentCaptionFor("Category"))
    }

    @Test
    fun `a garment shows the field asked for when it has one`() {
        val garment = garment(brand = "Uniqlo", subcategory = "tshirt", category = "tops")

        assertEquals(GarmentCaption.BRAND, garment.captionField(GarmentCaption.BRAND))
        assertEquals(GarmentCaption.TYPE, garment.captionField(GarmentCaption.TYPE))
        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.CATEGORY))
    }

    @Test
    fun `a garment with no brand shows its type instead of a blank line`() {
        val garment = garment(brand = null, subcategory = "tshirt")

        assertEquals(GarmentCaption.TYPE, garment.captionField(GarmentCaption.BRAND))
    }

    @Test
    fun `a garment with neither brand nor type falls all the way to its category`() {
        val garment = garment(brand = null, subcategory = null)

        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.BRAND))
        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.TYPE))
    }

    @Test
    fun `a blank field is no field at all`() {
        // Which is what the cell already assumed: a brand entered as a space would
        // otherwise draw an empty line and look like a rendering fault.
        val garment = garment(brand = "   ", subcategory = " ")

        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.BRAND))
    }

    @Test
    fun `asking for the type does not fall back to the brand`() {
        // The category is the same kind of answer, one step coarser. Answering
        // "Uniqlo" would make the line mean different kinds of thing from one cell
        // to the next, which is the readability the choice was made for.
        val garment = garment(brand = "Uniqlo", subcategory = null, category = "tops")

        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.TYPE))
    }

    @Test
    fun `asking for the category answers with it whatever else the garment has`() {
        val garment = garment(brand = "Uniqlo", subcategory = "tshirt")

        assertEquals(GarmentCaption.CATEGORY, garment.captionField(GarmentCaption.CATEGORY))
    }

    private fun garment(
        category: String = "tops",
        subcategory: String? = null,
        brand: String? = null,
    ) = GarmentRecord(
        id = "g1",
        imageUri = "g1.jpg",
        imageUriNoBg = null,
        imageUris = listOf("g1.jpg"),
        imageUrisNoBg = emptyList(),
        category = category,
        subcategory = subcategory,
        subcategories = listOfNotNull(subcategory),
        tags = emptyList(),
        brand = brand,
        colorPrimary = "#123456",
        colorSecondary = null,
        colorPalette = listOf("#123456"),
        size = null,
        purchaseDate = null,
        isAvailable = true,
        unavailableDate = null,
        createdAt = null,
        updatedAt = null,
    )
}
