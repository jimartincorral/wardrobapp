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

    // Every fixture carries a type, because a garment without one is now not a
    // duplicate of anything -- so a fixture that omitted it would test the gate
    // rather than whatever the test is named for.
    private fun existing(
        id: String,
        tags: List<String> = emptyList(),
        color: String = "#1F3A93",
        size: String? = null,
        subcategory: String = "T-Shirt",
    ) = Garment(
        id = id,
        category = "tops",
        subcategories = listOf(subcategory),
        tags = tags,
        colorPrimary = color,
        size = size,
    )

    private fun candidate(
        tags: List<String> = emptyList(),
        color: String = "#1F3A93",
        size: String? = null,
        subcategory: String = "T-Shirt",
    ) = DuplicateCandidate(
        category = "tops",
        subcategories = listOf(subcategory),
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

        // And never the colour, which is now the entry requirement rather than a
        // finding: every garment that gets this far is the same colour, so saying
        // so would be a reason given for everything, which is a reason for
        // nothing.
        assertTrue(DuplicateReason.SIMILAR_COLOR !in tagsAndColour.reasons)

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
    @Test
    fun `two kinds of thing are never the same thing`() {
        // The comparison that made the wardrobe-wide sweep unusable. These score
        // 1.0 on everything that is measured -- same category, same colour, same
        // tags, same size -- and no threshold below 1.0 could ever have separated
        // them, because nothing was looking at what they are.
        val matches = findDuplicatesAmong(
            candidate(subcategory = "T-Shirt", tags = listOf("casual")),
            listOf(existing("g1", subcategory = "Jumper", tags = listOf("casual"))),
        )

        assertEquals(emptyList(), matches)
    }

    @Test
    fun `one type in common is enough`() {
        // A garment can be filed under more than one type, and two shirts both
        // filed as T-Shirt are the same kind of thing whatever else either is.
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual")).copy(subcategories = listOf("Top", "T-Shirt")),
            listOf(existing("g1", tags = listOf("casual")).copy(subcategories = listOf("T-Shirt", "Vest"))),
        )

        assertEquals(listOf("g1"), matches.map { it.garment.id })
    }

    @Test
    fun `a garment with no type is not a duplicate of anything`() {
        val untyped = candidate(tags = listOf("casual")).copy(subcategories = emptyList())

        assertEquals(
            emptyList(),
            findDuplicatesAmong(untyped, listOf(existing("g1", tags = listOf("casual")))),
            "a garment with no type matched one that has one",
        )

        // Including another with none. Two unknowns are not a known.
        assertEquals(
            emptyList(),
            findDuplicatesAmong(
                untyped,
                listOf(existing("g1", tags = listOf("casual")).copy(subcategories = emptyList())),
            ),
            "two garments with no type matched each other",
        )
    }

    @Test
    fun `the same shirt in two colours is two shirts`() {
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual", "summer"), color = "#1F3A93"),
            listOf(existing("g1", tags = listOf("casual", "summer"), color = "#B22222")),
        )

        assertEquals(emptyList(), matches)
    }

    @Test
    fun `two blacks a shade apart are the same black`() {
        // The reason the gate is a colour distance and not an equal hex string:
        // these come off two photographs of the same shirt, and equality would
        // throw away exactly the duplicate worth finding.
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual"), color = "#000000"),
            listOf(existing("g1", tags = listOf("casual"), color = "#0A0A0A")),
        )

        assertEquals(listOf("g1"), matches.map { it.garment.id })
    }

    @Test
    fun `a colour nothing can read is not a colour to match on`() {
        // `colorRelationship` answers UNKNOWN rather than guessing, and unknown is
        // not "the same" -- which is the honest reading of a rule that says a
        // duplicate must be the same colour.
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("casual"), color = "not-a-colour"),
            listOf(existing("g1", tags = listOf("casual"), color = "not-a-colour")),
        )

        assertEquals(emptyList(), matches)
    }

    @Test
    fun `a size that differs is still the same shirt`() {
        // The owner's decision, pinned: same type, same colour, and one is an M
        // and the other an L. This scores 0.750, and the threshold sits directly
        // under it -- so a test rather than a comment, because the next person to
        // raise the number needs to be told what it costs.
        val matches = findDuplicatesAmong(
            candidate(size = "M"),
            listOf(existing("g1", size = "L")),
        )

        assertEquals(listOf("g1"), matches.map { it.garment.id })
    }

    @Test
    fun `sharing most of your tags is not being the same garment`() {
        // What raising the bar to 0.74 actually removes: three tags of four in
        // common, everything else alike. 0.733, and now under. Two summer cotton
        // navy tops are a pair of tops, not one top twice.
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("a", "b", "c", "d")),
            listOf(existing("g1", tags = listOf("a", "b", "c", "e"))),
        )

        assertEquals(emptyList(), matches)
    }

    @Test
    fun `sharing half of them is further still from it`() {
        val matches = findDuplicatesAmong(
            candidate(tags = listOf("a", "b", "c"), size = "M"),
            listOf(existing("g1", tags = listOf("a", "b", "d"), size = "M")),
        )

        assertEquals(emptyList(), matches)
    }

}
