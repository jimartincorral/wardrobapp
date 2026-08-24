package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether a garment being added is one the wardrobe already has.
 *
 * The score is a weighted average over the signals that have something to say --
 * tags, colour, size -- renormalised over whichever of them are present. That
 * renormalisation is the interesting part, and the reason the previous version of
 * this could never fire at all: with no tags recorded, the tag term contributed
 * nothing but still consumed 0.6 of the available weight, so an exact duplicate
 * peaked at 0.40 against a 0.81 threshold.
 *
 * Which category a garment belongs to is not asked here; the data layer narrows
 * to one category before calling this, and its own tests cover that.
 */
class DuplicateDetectionTest {

    private fun existing(
        id: String,
        tags: List<String> = emptyList(),
        color: String = "#1F3A93",
        size: String? = null,
    ) = Garment(
        id = id,
        category = "tops",
        tags = tags,
        colorPrimary = color,
        size = size,
    )

    private fun candidate(
        tags: List<String> = emptyList(),
        color: String = "#1F3A93",
        size: String? = null,
    ) = DuplicateCandidate(
        category = "tops",
        tags = tags,
        colorPrimary = color,
        size = size,
    )

    @Test
    fun `an exact duplicate is reported`() {
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual", "summer"), size = "M"),
            listOf(existing("g1", tags = listOf("casual", "summer"), size = "M")),
        )

        assertEquals(listOf("g1"), matches.map { it.garment.id })
        assertEquals(1.0, matches.single().score)
    }

    @Test
    fun `an exact duplicate with nothing recorded but its colour is still reported`() {
        // The case that used to be unreachable. Nothing but a colour is thin
        // evidence, so the score should be a match rather than a certainty -- but
        // it has to clear the bar, because it is the same garment.
        val matches = findDuplicatesAmong(candidate(), listOf(existing("g1")))

        assertEquals(listOf("g1"), matches.map { it.garment.id })
        assertTrue(matches.single().score > DUPLICATE_THRESHOLD)
    }

    @Test
    fun `a garment that shares nothing is not reported`() {
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("formal"), color = "#C0392B", size = "S"),
            listOf(existing("g1", tags = listOf("sport"), color = "#2E8B57", size = "XL")),
        )

        assertEquals(emptyList(), matches.map { it.garment.id })
    }

    @Test
    fun `an unanswered question lowers confidence rather than arguing against a match`() {
        // Same garment, described in less detail: the score falls but the verdict
        // does not flip. An absent signal is missing evidence, not evidence of
        // difference.
        // Absent tags: colour is the only signal, and it agrees, so the score is
        // as high as the evidence allows.
        val tagsAbsent = findDuplicatesAmong(candidate(), listOf(existing("g1"))).single().score

        // Tags present and disagreeing: now there *is* evidence against, and it
        // pulls the score down through the bar.
        val tagsDisagree = findDuplicatesAmong(
            candidate(tags = listOf("formal")),
            listOf(existing("g1", tags = listOf("sport"))),
        )

        assertTrue(tagsAbsent > DUPLICATE_THRESHOLD, "an unanswered question argued against a match")
        assertTrue(tagsDisagree.isEmpty(), "disagreeing tags still matched")
    }

    @Test
    fun `it says why`() {
        val tagsAndColour = findDuplicatesAmong(
            candidate(tags = listOf("casual", "summer")),
            listOf(existing("g1", tags = listOf("casual", "summer"))),
        ).single()

        assertTrue(DuplicateReason.SIMILAR_TAGS in tagsAndColour.reasons)
        assertTrue(DuplicateReason.SIMILAR_COLOR in tagsAndColour.reasons)

        val sameSize = findDuplicatesAmong(
            candidate(tags = listOf("casual"), size = "m"),
            listOf(existing("g1", tags = listOf("casual"), size = "M")),
        ).single()

        // Case and spacing are not a different size.
        assertTrue(DuplicateReason.SAME_SIZE in sameSize.reasons)
    }

    @Test
    fun `a match with no single standout signal still says something`() {
        // Otherwise a warning appears with no reason under it, which reads as a
        // bug in the warning rather than a judgement about the garment.
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("a", "b", "c")),
            listOf(existing("g1", tags = listOf("a", "b", "d"))),
        )

        for (match in matches) {
            assertTrue(match.reasons.isNotEmpty(), "${match.garment.id} was reported with no reason")
        }
    }

    @Test
    fun `the closest is reported first`() {
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual", "summer"), size = "M"),
            listOf(
                existing("weaker", tags = listOf("casual"), size = "L"),
                existing("exact", tags = listOf("casual", "summer"), size = "M"),
            ),
        )

        assertEquals("exact", matches.first().garment.id)
        assertEquals(matches.map { it.score }.sortedDescending(), matches.map { it.score })
    }

    @Test
    fun `an empty wardrobe reports nothing rather than failing`() {
        assertEquals(emptyList(), findDuplicatesAmong(candidate(tags = listOf("casual")), emptyList()))
    }

    @Test
    fun `the threshold is a threshold`() {
        // Passed in, so a caller can be stricter -- and worth checking, because a
        // comparison written the wrong way round is invisible at the default.
        val wardrobe = listOf(existing("g1", tags = listOf("casual")))

        assertTrue(findDuplicatesAmong(candidate(tags = listOf("casual")), wardrobe, threshold = 0.1).isNotEmpty())
        // Strictly above, so an exact match does not clear a threshold of 1.
        assertTrue(findDuplicatesAmong(candidate(tags = listOf("casual")), wardrobe, threshold = 1.0).isEmpty())
    }

    @Test
    fun `two garments that merely default to black are not duplicates of each other`() {
        // '#000000' is the schema's default colour, so it is the value a garment
        // has when nobody chose one. Taking the best match across whole palettes
        // meant a red garment and a blue one that both listed black scored as
        // identical -- which is why the comparison is primary against primary.
        val red = candidate(color = "#C0392B").copy(colorPalette = listOf("#C0392B", "#000000"))
        val blue = Garment(
            id = "blue",
            category = "tops",
            colorPrimary = "#1F3A93",
            colorPalette = listOf("#1F3A93", "#000000"),
        )

        assertEquals(emptyList(), findDuplicatesAmong(red, listOf(blue)))
    }
}
