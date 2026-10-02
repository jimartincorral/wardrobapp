package com.wardrobapp.presentation

import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The choices the filter panel offers.
 *
 * The panel used to offer everything the app knows about, so most of it matched
 * nothing in any given wardrobe. These are the rules that make it offer what the
 * wardrobe holds instead -- and the one that keeps it usable when a combination
 * matches nothing at all.
 */
class WardrobeFacetsTest {

    private fun garment(
        category: String = "tops",
        subcategories: List<String> = listOf("T-Shirt"),
        tags: List<String> = emptyList(),
        brand: String? = null,
        size: String? = null,
        palette: List<String> = listOf("#000000"),
    ) = normalizeGarmentRow(
        mapOf(
            "id" to "g-$category-$brand-$size",
            "image_uri" to "a.jpg",
            "category" to category,
            "subcategories" to subcategories.joinToString("\",\"", "[\"", "\"]"),
            "tags" to (if (tags.isEmpty()) "[]" else tags.joinToString("\",\"", "[\"", "\"]")),
            "brand" to brand,
            "size" to size,
            "color_primary" to palette.first(),
            "color_palette" to palette.joinToString("\",\"", "[\"", "\"]"),
            "is_available" to 1,
        ),
        "",
    )

    @Test
    fun `only the brands and sizes the wardrobe holds are offered`() {
        val facets = wardrobeFacets(
            listOf(
                garment(brand = "Uniqlo", size = "M"),
                garment(brand = "Arket", size = "L"),
                garment(brand = null, size = null),
            ),
            WardrobeQuery(),
        )

        // Sorted for a reader rather than in wardrobe order, and a garment with no
        // brand recorded contributes no brand.
        assertEquals(listOf("Arket", "Uniqlo"), facets.brands)
        assertEquals(listOf("L", "M"), facets.sizes)
    }

    @Test
    fun `the same brand spelled two ways is offered once`() {
        val facets = wardrobeFacets(
            listOf(garment(brand = "Uniqlo"), garment(brand = "uniqlo")),
            WardrobeQuery(),
        )

        assertEquals(1, facets.brands.size)
    }

    @Test
    fun `a blank brand is not a brand`() {
        val facets = wardrobeFacets(listOf(garment(brand = "   ", size = "  ")), WardrobeQuery())

        assertEquals(emptyList(), facets.brands)
        assertEquals(emptyList(), facets.sizes)
    }

    @Test
    fun `colours come in the palette's order, with an unnamed hex last`() {
        // Black is early in the palette and gold late; a hex that is not in the
        // palette at all is still offered, because a garment is wearing it.
        val facets = wardrobeFacets(
            listOf(
                garment(palette = listOf("#DAA520")),
                garment(palette = listOf("#123456")),
                garment(palette = listOf("#000000")),
            ),
            WardrobeQuery(),
        )

        assertEquals(listOf("#000000", "#DAA520", "#123456"), facets.colors)
    }

    @Test
    fun `a colour stored in lower case is offered in the palette's spelling`() {
        val facets = wardrobeFacets(listOf(garment(palette = listOf("#daa520"))), WardrobeQuery())

        assertEquals(listOf("#DAA520"), facets.colors)
    }

    @Test
    fun `categories and types are what is present, in the app's order`() {
        val facets = wardrobeFacets(
            listOf(
                garment(category = "bottoms", subcategories = listOf("Jeans")),
                garment(category = "tops", subcategories = listOf("Shirt")),
            ),
            WardrobeQuery(),
        )

        assertEquals(listOf("tops", "bottoms"), facets.categories)
        // No category chosen, so there is no type row: a type only means anything
        // inside its category.
        assertEquals(emptyList(), facets.subcategories)
    }

    @Test
    fun `the type row is the chosen category's types that the wardrobe has`() {
        val facets = wardrobeFacets(
            listOf(garment(category = "tops", subcategories = listOf("Shirt"))),
            WardrobeQuery(category = "tops"),
        )

        assertEquals(listOf("Shirt"), facets.subcategories)
    }

    @Test
    fun `seasons and occasions are the ones the garments actually carry`() {
        val facets = wardrobeFacets(
            listOf(garment(tags = listOf("winter"), subcategories = listOf("Blouse"))),
            WardrobeQuery(),
        )

        assertEquals(listOf(Season.WINTER), facets.seasons)
        // An occasion is what a garment *is*, not what it was tagged, so a blouse
        // brings the formal chip with it.
        assertTrue(Occasion.FORMAL in facets.occasions)
    }

    @Test
    fun `what is picked stays on offer even when nothing matches it`() {
        // The rule that keeps the panel operable. Brand and size are picked from a
        // wardrobe that no longer contains them -- a combination matching nothing --
        // and every row would otherwise be empty, including the chips holding the
        // choices somebody would want to undo.
        val facets = wardrobeFacets(
            emptyList(),
            WardrobeQuery(
                category = "tops",
                subcategory = "Shirt",
                season = Season.WINTER,
                occasion = Occasion.FORMAL,
                color = "#DAA520",
                brand = "Uniqlo",
                size = "M",
            ),
        )

        assertEquals(listOf("tops"), facets.categories)
        assertEquals(listOf("Shirt"), facets.subcategories)
        assertEquals(listOf(Season.WINTER), facets.seasons)
        assertEquals(listOf(Occasion.FORMAL), facets.occasions)
        assertEquals(listOf("#DAA520"), facets.colors)
        assertEquals(listOf("Uniqlo"), facets.brands)
        assertEquals(listOf("M"), facets.sizes)
    }

    @Test
    fun `an empty wardrobe with nothing picked offers nothing`() {
        val facets = wardrobeFacets(emptyList(), WardrobeQuery())

        assertEquals(WardrobeFacets(), facets)
    }
}
