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
            listOf("cotton", "winter", "striped", "all-season")
        )

        assertEquals(listOf("cotton", "striped"), customTags)
        assertEquals(listOf(Season.WINTER, Season.ALL_SEASON), seasons)
    }

    @Test
    fun `normalizes case and drops blanks, whatever it is handed`() {
        val (customTags, seasons) = splitStructuredTags(
            listOf("Cotton", " SUMMER ", "   ", "", "Formal", "STRIPED")
        )

        assertEquals(listOf("cotton", "striped"), customTags)
        assertEquals(listOf(Season.SUMMER), seasons)
    }

    @Test
    fun `discards weather and occasion values left over from older versions`() {
        // Restoring an old backup puts them straight back, so a one-time
        // migration is not enough -- without this they resurface as if someone
        // had typed them.
        val (customTags, seasons) = splitStructuredTags(
            listOf("hot", "casual", "wool", "summer", "party", "travel", "rainy")
        )

        assertEquals(listOf("wool"), customTags)
        assertEquals(listOf(Season.SUMMER), seasons)
    }

    @Test
    fun `folds typed tags and picked seasons into one column`() {
        assertEquals(
            listOf("cotton", "striped", "summer"),
            mergeStructuredTags(listOf("Cotton", "Striped"), listOf(Season.SUMMER)),
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
            listOf("wool", "winter"),
            mergeStructuredTags(listOf("Wool", "wool", "WINTER"), listOf(Season.WINTER)),
        )
    }

    @Test
    fun `survives a round trip through the split`() {
        // The pair has to compose: what is folded in comes back out as what went
        // in, or editing a garment would silently rewrite its tags.
        val typed = listOf("cotton", "striped")
        val picked = listOf(Season.SPRING, Season.FALL)

        val (customTags, seasons) = splitStructuredTags(mergeStructuredTags(typed, picked))

        assertEquals(typed, customTags)
        assertEquals(picked, seasons)
    }

    @Test
    fun `does not fold in a value the split would throw away`() {
        // 'formal' is a legacy occasion tag: split drops it. Merging it back in
        // would mean a tag that disappears every time the garment is edited.
        val merged = mergeStructuredTags(listOf("formal", "wool"), emptyList())
        val (customTags, _) = splitStructuredTags(merged)

        assertEquals(listOf("wool"), customTags)
    }

    @Test
    fun `keeps the order it was given, and every repeat`() {
        // Deduplicating is the caller's business: the detail screen puts seasons
        // into the app's own order, which removes repeats as a side effect.
        val (customTags, seasons) = splitStructuredTags(
            listOf("wool", "winter", "wool", "winter")
        )

        assertEquals(listOf("wool", "wool"), customTags)
        assertEquals(listOf(Season.WINTER, Season.WINTER), seasons)
    }
}
