package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WardrobeAskTest {

    @Test
    fun `Nano's answer is read as filters, and only values the filters offer are kept`() {
        val ask = parseWardrobeAsk(
            """
            - **type**: Chinos
            colour: navy blue
            season: invierno
            occasion: work
            brand: Arket
            something else: entirely
            """.trimIndent(),
        )

        assertEquals("bottoms", ask.category)
        assertEquals("Chinos", ask.subcategory)
        assertEquals("#000080", ask.color)
        assertNull(ask.season, "a season not on the list was kept")
        assertEquals(Occasion.WORK, ask.occasion)
        assertEquals("Arket", ask.brand)
    }

    @Test
    fun `a category stands when no type was named, and a type wins over it`() {
        assertEquals("shoes", parseWardrobeAsk("category: shoes").category)
        val both = parseWardrobeAsk("category: tops\ntype: Jeans")
        assertEquals("bottoms" to "Jeans", both.category to both.subcategory)
    }

    @Test
    fun `plain words are read against the app's own vocabulary`() {
        val ask = readWardrobeAsk("my light blue Arket shirt for the office in summer", brands = listOf("Arket", "Zara"))

        assertEquals("tops", ask.category)
        assertEquals("Shirt", ask.subcategory)
        assertEquals("#87CEEB", ask.color, "light blue was read as blue")
        assertEquals(Season.SUMMER, ask.season)
        assertEquals(Occasion.WORK, ask.occasion)
        assertEquals("Arket", ask.brand)

        assertEquals("shoes", readWardrobeAsk("white shoes", emptyList()).category)
        assertTrue(readWardrobeAsk("the one from the party", emptyList()).isEmpty)
    }

    @Test
    fun `a brand is kept only as the wardrobe writes it`() {
        val known = listOf("Zara", "Arket")
        assertEquals("Zara", WardrobeAsk(brand = "zara").withBrandFrom(known).brand)
        assertNull(WardrobeAsk(brand = "Gucci").withBrandFrom(known).brand)
    }

    @Test
    fun `an answer replaces the old filters but keeps how the list is sorted and what it includes`() {
        val before = WardrobeQuery(search = "navy chinos", size = "M", sort = GarmentSort.OLDEST, includeRetired = true)
        val after = before.answering(WardrobeAsk(category = "bottoms", subcategory = "Chinos", color = "#000080"))

        assertEquals(
            WardrobeQuery(
                sort = GarmentSort.OLDEST,
                includeRetired = true,
                category = "bottoms",
                subcategory = "Chinos",
                color = "#000080",
            ),
            after,
        )
    }

    @Test
    fun `the prompt carries the request and every list it is answered from`() {
        val prompt = wardrobeAskPrompt("  something warm for a wedding  ")
        assertTrue("\"something warm for a wedding\"" in prompt)
        assertTrue("Chinos" in prompt && "light blue" in prompt && "winter" in prompt && "formal" in prompt)
        assertTrue("multi" !in prompt.substringAfter("colour:").substringBefore('\n'))
    }
}
