package com.wardrobapp.app

import com.wardrobapp.presentation.ColorBar
import com.wardrobapp.presentation.MULTI_SWATCH
import com.wardrobapp.presentation.NO_SUBCATEGORY
import com.wardrobapp.presentation.StatBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the statistics screen looks up for each bar.
 *
 * The wording moved into string resources, so what is left here is the part that
 * decides *which* word to ask for -- and that is the part that fails silently: a
 * mis-stripped subcategory asks for "tops:tshirt" and gets nothing back, and a
 * colour whose swatch is not recognised falls through to a raw hex.
 *
 * Plain JUnit, no Robolectric: these touch no Android API, and they only live in
 * :app because the screen does. Verified before landing by copying both the rules
 * and the cases into :presentation, which compiles without an Android SDK.
 */
class StatisticsLabelsTest {

    private fun sub(key: String) = StatBar(key = key, count = 1L, fraction = 1.0)

    private fun color(key: String, swatch: String) =
        ColorBar(key = key, count = 1L, fraction = 1.0, swatch = swatch)

    @Test
    fun `a subcategory drops the category the module prefixed it with`() {
        assertEquals("tshirt", sub("tops:tshirt").subcategoryName("tops"))
    }

    @Test
    fun `a subcategory keeps a colon of its own`() {
        // Only the prefix goes. Splitting on the colon would eat part of the name.
        assertEquals("a:b", sub("tops:a:b").subcategoryName("tops"))
    }

    @Test
    fun `a subcategory under another category is not stripped`() {
        // The prefix is what keeps one name under two categories distinct, so a
        // mismatched category has to leave the key alone rather than guess.
        assertEquals("bottoms:jeans", sub("bottoms:jeans").subcategoryName("tops"))
    }

    @Test
    fun `the no-subcategory sentinel asks for no type at all`() {
        // Null rather than the sentinel string, so the caller cannot accidentally
        // look up a garment type called "__none__".
        assertNull(sub("tops:$NO_SUBCATEGORY").subcategoryName("tops"))
    }

    @Test
    fun `a palette colour resolves to its key`() {
        // The bar's key arrives uppercased -- byColor groups on
        // UPPER(color_primary) -- and the palette matches it case-insensitively.
        assertEquals("navy", color(key = "#000080", swatch = "#000080").paletteKey())
    }

    @Test
    fun `a colour outside the palette resolves to nothing`() {
        assertNull(color(key = "#123456", swatch = "#123456").paletteKey())
    }

    @Test
    fun `the many-coloured sentinel resolves to its own key`() {
        // Not a hex, so the palette cannot answer for it: without this the bar
        // would fall through and print "#RAINBOW".
        assertEquals(MULTI_SWATCH, color(key = "#RAINBOW", swatch = MULTI_SWATCH).paletteKey())
    }
}
