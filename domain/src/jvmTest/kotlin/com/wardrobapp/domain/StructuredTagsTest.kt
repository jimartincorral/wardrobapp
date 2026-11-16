package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Telling typed tags from structured ones.
 *
 * Tested directly rather than only through a garment, because this function is
 * what *defines* a tag -- and a record's tags arrive already lowercased, so
 * going through one would leave the normalization here unexercised.
 */
class StructuredTagsTest {

    @Test
    fun `separates seasons from what the user typed`() {
        val (customTags, seasons) = splitStructuredTags(
            listOf("vintage", "winter", "striped", "all-season")
        )

        assertEquals(listOf("vintage", "striped"), customTags)
        assertEquals(listOf(Season.WINTER, Season.ALL_SEASON), seasons)
    }

    @Test
    fun `normalizes case and drops blanks, whatever it is handed`() {
        val (customTags, seasons) = splitStructuredTags(
            listOf("Vintage", " SUMMER ", "   ", "", "Formal", "STRIPED")
        )

        assertEquals(listOf("vintage", "striped"), customTags)
        assertEquals(listOf(Season.SUMMER), seasons)
    }

    @Test
    fun `discards weather and occasion values left over from older versions`() {
        // Restoring an old backup puts them straight back, so a one-time
        // migration is not enough -- without this they resurface as if someone
        // had typed them.
        val (customTags, seasons) = splitStructuredTags(
            listOf("hot", "casual", "gift", "summer", "party", "travel", "rainy")
        )

        assertEquals(listOf("gift"), customTags)
        assertEquals(listOf(Season.SUMMER), seasons)
    }

    @Test
    fun `folds typed tags and picked seasons into one column`() {
        assertEquals(
            listOf("vintage", "striped", "summer"),
            mergeStructuredTags(listOf("Vintage", "Striped"), listOf(Season.SUMMER)),
        )
    }

    @Test
    fun `lowercases, trims and drops blanks on the way in`() {
        // The column holds rows written by the app this replaced, so this has to
        // stay as it was or a
        // garment saved by one reads differently in the other.
        assertEquals(
            listOf("linen"),
            mergeStructuredTags(listOf("  LINEN  ", "   ", ""), emptyList()),
        )
    }

    @Test
    fun `keeps one of anything repeated`() {
        assertEquals(
            listOf("gift", "winter"),
            mergeStructuredTags(listOf("Gift", "gift", "WINTER"), listOf(Season.WINTER)),
        )
    }

    @Test
    fun `survives a round trip through the split`() {
        // The pair has to compose: what is folded in comes back out as what went
        // in, or editing a garment would silently rewrite its tags.
        val typed = listOf("vintage", "striped")
        val picked = listOf(Season.SPRING, Season.FALL)

        val (customTags, seasons) = splitStructuredTags(mergeStructuredTags(typed, picked))

        assertEquals(typed, customTags)
        assertEquals(picked, seasons)
    }

    @Test
    fun `does not fold in a value the split would throw away`() {
        // 'formal' is a legacy occasion tag: split drops it. Merging it back in
        // would mean a tag that disappears every time the garment is edited.
        val merged = mergeStructuredTags(listOf("formal", "gift"), emptyList())
        val (customTags, _) = splitStructuredTags(merged)

        assertEquals(listOf("gift"), customTags)
    }

    @Test
    fun `keeps the order it was given, and every repeat`() {
        // Deduplicating is the caller's business: the detail screen puts seasons
        // into the app's own order, which removes repeats as a side effect.
        val (customTags, seasons) = splitStructuredTags(
            listOf("gift", "winter", "gift", "winter")
        )

        assertEquals(listOf("gift", "gift"), customTags)
        assertEquals(listOf(Season.WINTER, Season.WINTER), seasons)
    }

    // ---- materials ------------------------------------------------------------

    @Test
    fun `a material is told apart from what was typed, in the catalogue's order`() {
        val (customTags, _, _, materials) = splitStructuredTags(listOf("polyester", "vintage", "Cotton", "winter"))

        assertEquals(listOf("vintage"), customTags)
        assertEquals(listOf("cotton", "polyester"), materials)
    }

    @Test
    fun `a material written by a reading before the form offered them reads as one`() {
        // What GarmentReading has always written: the bare word, among the tags.
        // No conversion, so a garment saved that way shows its material at once.
        assertEquals(listOf("linen"), splitStructuredTags(listOf("linen")).materials)
    }

    @Test
    fun `materials go back into the tags as the bare word, and come out the same`() {
        val merged = mergeStructuredTags(listOf("vintage"), listOf(Season.SUMMER), materials = listOf("elastane", "cotton"))

        assertEquals(listOf("vintage", "summer", "cotton", "elastane"), merged)
        assertEquals(listOf("cotton", "elastane"), splitStructuredTags(merged).materials)
        assertEquals(listOf("vintage"), splitStructuredTags(merged).customTags)
    }

    @Test
    fun `something that is not a material is not saved as one`() {
        assertEquals(emptyList(), mergeStructuredTags(emptyList(), emptyList(), materials = listOf("bamboo")))
    }
}
