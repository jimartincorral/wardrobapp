package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic behind the statistics bars.
 *
 * Counts in, bars out. Every width is a division, and a bar of the wrong length
 * is wrong in a way nobody notices -- which is why this came out of the screen,
 * where it was inline, in two places, and untested.
 *
 * These cases replace a recorded corpus whose answers came from the app this was
 * ported from; what they keep is the part that has to hold for any input.
 */
class StatisticsViewTest {

    private fun view(
        inUse: Long = 6,
        categories: List<Distribution> = listOf(Distribution("tops", 3), Distribution("shoes", 1)),
        colors: List<Distribution> = emptyList(),
        brands: List<Distribution> = emptyList(),
        subcategories: Map<String, List<Distribution>> = emptyMap(),
        brandSort: BrandSort = BrandSort.COUNT,
        retired: Long = 0,
        lifespans: List<LifespanEntry> = emptyList(),
    ) = statisticsView(
        inUse = inUse,
        categories = categories,
        colors = colors,
        brands = brands,
        subcategories = subcategories,
        brandSort = brandSort,
        retired = retired,
        lifespans = lifespans,
    )

    @Test
    fun `a bar is its share of the largest bar, not of the wardrobe`() {
        // Scaled against the biggest count so the chart uses its full width. A
        // wardrobe of six garments split 3/1 draws a full bar and a third of one.
        val bars = view().categories

        assertEquals(listOf(1.0, 1.0 / 3.0), bars.map { it.fraction })
        assertEquals(listOf(3L, 1L), bars.map { it.count })
    }

    @Test
    fun `an empty wardrobe says so and draws nothing`() {
        val empty = view(inUse = 0, categories = emptyList())

        assertTrue(empty.isEmpty)
        assertEquals(emptyList(), empty.categories)
        assertEquals(0, empty.distinctCategories)
    }

    @Test
    fun `the wardrobe is what is worn plus what was put away`() {
        val view = view(inUse = 4, retired = 3)

        assertEquals(4L, view.inUse)
        assertEquals(3L, view.retired)
        assertEquals(7L, view.items)
    }

    @Test
    fun `a wardrobe of nothing but retired garments is not nothing to measure`() {
        // It used to be: emptiness was measured on what is in use, so a page with
        // three filled lifespan bars would have said "nothing to measure yet" over
        // the top of them.
        val view = view(
            inUse = 0,
            categories = emptyList(),
            retired = 2,
            lifespans = listOf(LifespanEntry("g1", "tops", listOf("Coat"), days = 400)),
        )

        assertFalse(view.isEmpty)
        assertEquals(1, view.lifespans.size)
    }

    @Test
    fun `lifespans arrive scaled, and only as many as the chart holds`() {
        // The scale itself is `LifespansTest`; what matters here is that asking for
        // the page's numbers is one call rather than two models to keep in step.
        val view = view(
            retired = 9,
            lifespans = (1..9).map { LifespanEntry("g$it", "tops", listOf("Coat"), days = 400L) },
        )

        assertEquals(LIFESPAN_BARS, view.lifespans.size)
        assertEquals(listOf(1.0, 1.0, 1.0), view.lifespans.map { it.fraction })
    }

    @Test
    fun `a distribution of zeroes draws no bars rather than NaN ones`() {
        // Dividing by a max of zero is a NaN in floating point, and a NaN width
        // draws nothing while the count sits beside it.
        val bars = view(categories = listOf(Distribution("tops", 0), Distribution("shoes", 0))).categories

        assertEquals(listOf(0.0, 0.0), bars.map { it.fraction })
        assertTrue(bars.none { it.fraction.isNaN() }, "a NaN reached the screen")
    }

    @Test
    fun `the counts of distinct things are the sizes of their distributions`() {
        val stats = view(
            categories = listOf(Distribution("tops", 2), Distribution("shoes", 1)),
            colors = listOf(Distribution("#1F3A93", 2)),
            brands = listOf(Distribution("Arket", 1), Distribution("Uniqlo", 2)),
        )

        assertEquals(2, stats.distinctCategories)
        assertEquals(1, stats.distinctColors)
        assertEquals(2, stats.distinctBrands)
    }

    @Test
    fun `a named colour draws the palette's own hex, and an unnamed one its own`() {
        // So a wardrobe holding both #cc0000 and #CC0000 shows one swatch rather
        // than two spellings of it.
        val named = GARMENT_COLORS.first { it.first != "multi" }
        val stats = view(
            colors = listOf(
                Distribution(named.second.lowercase(), 3),
                Distribution("#123456", 1),
            ),
        )

        assertEquals(named.second, stats.colors[0].swatch)
        assertEquals("#123456", stats.colors[1].swatch)
    }

    @Test
    fun `the many-coloured swatch is not a hex`() {
        val stats = view(colors = listOf(Distribution("multi", 2)))

        assertEquals(MULTI_SWATCH, stats.colors.single().swatch)
    }

    @Test
    fun `brands can be read by count or by name`() {
        val brands = listOf(
            Distribution("Uniqlo", 5),
            Distribution("arket", 2),
            Distribution("Zara", 2),
        )

        // By count is the order the query gave, untouched.
        assertEquals(
            listOf("Uniqlo", "arket", "Zara"),
            view(brands = brands).brands.map { it.key },
        )

        // Alphabetically means a person's alphabet: case-insensitive, and by the
        // locale's collation rather than by byte value.
        assertEquals(
            listOf("arket", "Uniqlo", "Zara"),
            view(brands = brands, brandSort = BrandSort.ALPHA).brands.map { it.key },
        )
    }

    @Test
    fun `each category's subcategories are scaled against their own largest`() {
        // Otherwise opening a small category shows four slivers.
        val stats = view(
            categories = listOf(Distribution("tops", 4), Distribution("shoes", 2)),
            subcategories = mapOf(
                "tops" to listOf(Distribution("T-Shirt", 3), Distribution("Shirt", 1)),
                "shoes" to listOf(Distribution("Boots", 2)),
            ),
        )

        assertEquals(listOf(1.0, 1.0 / 3.0), stats.subcategories.getValue("tops").map { it.fraction })
        assertEquals(listOf(1.0), stats.subcategories.getValue("shoes").map { it.fraction })
    }

    @Test
    fun `a subcategory key carries its category`() {
        // The same type name appears under more than one category, and a list
        // keyed on the bare name would collapse them into one row.
        val stats = view(
            subcategories = mapOf(
                "tops" to listOf(Distribution("Vest", 1)),
                "midlayer" to listOf(Distribution("Vest", 1)),
            ),
        )

        assertEquals(listOf("tops:Vest"), stats.subcategories.getValue("tops").map { it.key })
        assertEquals(listOf("midlayer:Vest"), stats.subcategories.getValue("midlayer").map { it.key })
    }

    @Test
    fun `a garment with no type recorded still counts, under its own key`() {
        val stats = view(
            subcategories = mapOf("tops" to listOf(Distribution(NO_SUBCATEGORY, 2))),
        )

        assertEquals(listOf("tops:$NO_SUBCATEGORY"), stats.subcategories.getValue("tops").map { it.key })
        assertEquals(listOf(2L), stats.subcategories.getValue("tops").map { it.count })
    }

    @Test
    fun `no bar is ever negative or past the end of its track`() {
        // Counts come from COUNT(*), so a negative should be impossible -- and a
        // width that draws backwards would not look like a bug in a query.
        val stats = view(
            categories = listOf(Distribution("tops", -1), Distribution("shoes", 4)),
        )

        for (bar in stats.categories) {
            assertTrue(bar.fraction in 0.0..1.0, "${bar.key} draws at ${bar.fraction}")
        }
    }
}
