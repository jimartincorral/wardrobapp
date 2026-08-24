package com.wardrobapp.app

import com.wardrobapp.presentation.ColorBar
import com.wardrobapp.presentation.MULTI_SWATCH
import com.wardrobapp.presentation.NO_SUBCATEGORY
import com.wardrobapp.presentation.StatBar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words the statistics screen puts on its bars.
 *
 * :presentation deliberately does not own these -- it answers in keys, counts and
 * fractions -- so this is the half of that boundary nothing else checks. Both
 * rules fail silently if they are wrong: a mis-stripped subcategory reads
 * "tops:tshirt" beside a correct bar, and a colour whose swatch is not recognised
 * reads as a raw hex. Neither looks like a crash and neither moves a number.
 *
 * Plain JUnit, no Robolectric: these touch no Android API. They live in :app only
 * because the labels do -- and they were run before landing by copying both the
 * rules and the cases into :presentation, which compiles without an Android SDK.
 * Nine of eleven injected faults were caught. The two that were not are the same
 * fault twice -- reading the palette from the bar's key rather than its swatch --
 * and no input distinguishes them: `byColor()` groups on `UPPER(color_primary)`
 * and every palette hex is already uppercase, so for a named colour the two
 * strings are equal, and for an unnamed one the swatch *is* the key.
 */
class StatisticsLabelsTest {

    private fun sub(key: String, count: Long = 1L) = StatBar(key = key, count = count, fraction = 1.0)

    private fun color(key: String, swatch: String) =
        ColorBar(key = key, count = 1L, fraction = 1.0, swatch = swatch)

    @Test
    fun `a subcategory drops the category the module prefixed it with`() {
        assertEquals("Tshirt", sub("tops:tshirt").subcategoryLabel("tops"))
    }

    @Test
    fun `a subcategory keeps a colon of its own`() {
        // Only the prefix goes. Stripping to the last colon, or splitting on it,
        // would eat part of the name.
        assertEquals("A:b", sub("tops:a:b").subcategoryLabel("tops"))
    }

    @Test
    fun `a subcategory under another category is not stripped`() {
        // The prefix is what keeps the same name under two categories distinct,
        // so a mismatched category must leave the key alone rather than guess.
        assertEquals("Bottoms:jeans", sub("bottoms:jeans").subcategoryLabel("tops"))
    }

    @Test
    fun `a hyphenated subcategory reads as words`() {
        assertEquals("Dress shirt", sub("tops:dress-shirt").subcategoryLabel("tops"))
    }

    @Test
    fun `the no-subcategory sentinel is worded, not shown`() {
        assertEquals("Not specified", sub("tops:$NO_SUBCATEGORY").subcategoryLabel("tops"))
    }

    @Test
    fun `a palette colour is named`() {
        // The key arrives uppercased -- byColor groups on UPPER(color_primary) --
        // and the swatch is the palette's own spelling.
        assertEquals("Navy", color(key = "#000080", swatch = "#000080").colorLabel())
    }

    @Test
    fun `a two-word palette key reads as two words`() {
        // The same wording the garment detail uses, rather than "LightBlue".
        assertEquals("Light blue", color(key = "#87CEEB", swatch = "#87CEEB").colorLabel())
    }

    @Test
    fun `a colour outside the palette keeps the value stored`() {
        assertEquals("#123456", color(key = "#123456", swatch = "#123456").colorLabel())
    }

    @Test
    fun `the many-coloured sentinel is named rather than looked up`() {
        // It is not a hex, so the palette -- which is keyed by hex -- cannot
        // answer for it. Falling through to the key would print '#RAINBOW'.
        assertEquals("Multi", color(key = "#RAINBOW", swatch = MULTI_SWATCH).colorLabel())
    }
}
