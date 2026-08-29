package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sweeping a wardrobe for garments that are much the same as each other.
 *
 * The scoring is [DuplicateDetectionTest]'s. What is tested here is the shape of
 * the answer, which is where a sweep can be wrong in ways a pairwise score cannot:
 * five alike have to arrive as one group of five rather than ten pairs, a garment
 * must not turn up in two places at once, and a chain of resemblances must not be
 * presented as one garment.
 */
class DuplicateGroupsTest {

    private fun garment(
        id: String,
        category: String = "tops",
        tags: List<String> = listOf("casual", "summer"),
        color: String = "#1F3A93",
        size: String? = "M",
    ) = Garment(
        id = id,
        category = category,
        tags = tags,
        colorPrimary = color,
        size = size,
    )

    @Test
    fun `five alike are one group of five, not ten pairs`() {
        // The whole reason this exists rather than a list of matches: "you own
        // five near-identical black t-shirts" is one thing to say, and ten
        // sentences saying it about two shirts at a time is not that.
        val groups = duplicateGroups((1..5).map { garment("g$it") })

        assertEquals(1, groups.size)
        assertEquals(
            listOf("g1", "g2", "g3", "g4", "g5"),
            groups.single().garments.map { it.id }.sorted(),
        )
    }

    @Test
    fun `the anchor is in its own group`() {
        // Easy to lose: the garment everything was measured against is a member
        // too, and a group of "the four others" would be missing the shirt the
        // reader is most likely to recognise.
        val groups = duplicateGroups((1..3).map { garment("g$it") })

        assertTrue("g1" in groups.single().garments.map { it.id })
    }

    @Test
    fun `a wardrobe where nothing resembles anything reports nothing`() {
        val groups = duplicateGroups(
            listOf(
                garment("g1", tags = listOf("formal"), color = "#000000", size = "S"),
                garment("g2", tags = listOf("beachwear"), color = "#FFD700", size = "XL"),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `an empty wardrobe reports nothing rather than failing`() {
        assertEquals(emptyList(), duplicateGroups(emptyList()))
    }

    @Test
    fun `a lone garment is not a duplicate of itself`() {
        assertEquals(emptyList(), duplicateGroups(listOf(garment("g1"))))
    }

    @Test
    fun `garments in different categories never join the same group`() {
        // The trap this guards: `findDuplicatesAmong` scores tags, colour and
        // size and never looks at the category, so identical socks and an
        // identical shirt score the same as two identical shirts. Bucketing is
        // the only thing keeping them apart.
        val groups = duplicateGroups(
            listOf(
                garment("shirt1", category = "tops"),
                garment("shirt2", category = "tops"),
                garment("sock1", category = "socks"),
                garment("sock2", category = "socks"),
            ),
        )

        assertEquals(2, groups.size)
        for (group in groups) {
            assertEquals(1, group.garments.map { it.category }.distinct().size, "a group spans categories")
        }
    }

    @Test
    fun `no garment appears in two groups`() {
        val wardrobe = (1..6).map { garment("g$it", color = if (it <= 3) "#1F3A93" else "#B22222") }

        val seen = duplicateGroups(wardrobe).flatMap { it.garments }.map { it.id }

        assertEquals(seen.distinct(), seen, "a garment was reported in more than one group")
    }

    @Test
    fun `a group is everything like its anchor, not a chain of resemblances`() {
        // The design decision, held by a test because nothing else records it: A
        // resembling B and B resembling C does not make A resemble C. Chained,
        // this wardrobe would be one group of three and would be telling the
        // reader that a garment sharing no tag with another is the same garment.
        // These three really do chain, which the first version of this test did
        // not: a and b share three tags of five, so do b and c, but a and c share
        // only two of six and score below the bar. Anchored, a and b group and c
        // is left alone; chained, all three arrive as one garment.
        val a = garment("a", tags = listOf("casual", "summer", "cotton", "blue"))
        val b = garment("b", tags = listOf("summer", "cotton", "blue", "linen"))
        val c = garment("c", tags = listOf("cotton", "blue", "linen", "holiday"))

        val groups = duplicateGroups(listOf(a, b, c))

        val withA = groups.single { group -> "a" in group.garments.map { it.id } }
        assertEquals(
            listOf("a", "b"),
            withA.garments.map { it.id }.sorted(),
            "a chain put a garment in a group with one it does not resemble",
        )
    }

    @Test
    fun `the same wardrobe reports the same groups twice running`() {
        // A list that reshuffles itself between visits reads as finding different
        // things each time, which is worse than finding nothing.
        val wardrobe = (1..5).map { garment("g$it") }

        assertEquals(
            duplicateGroups(wardrobe).map { group -> group.garments.map { it.id } },
            duplicateGroups(wardrobe).map { group -> group.garments.map { it.id } },
        )
    }

    @Test
    fun `a reason is only given when it holds for the whole group`() {
        // A heading saying "same size" over a group where one garment is an XL
        // is a heading that lies about most of what is under it.
        val groups = duplicateGroups(
            listOf(
                garment("g1", size = "M"),
                garment("g2", size = "M"),
                garment("g3", size = "XL"),
            ),
        )

        val group = groups.single()

        // Pinned rather than guarded with an `if`: were the scoring to stop
        // grouping all three, the assertion below would hold for a reason that
        // has nothing to do with what this test is about, and pass saying nothing.
        assertEquals(3, group.garments.size, "these three no longer group, so the rest proves nothing")
        assertTrue(
            DuplicateReason.SAME_SIZE !in group.reasons,
            "a reason true of one pair was claimed for the group",
        )
    }

    @Test
    fun `a group always says something about why`() {
        // Never an empty line under the photos: when the members agree on nothing
        // nameable, the honest answer is that they are simply alike overall.
        for (group in duplicateGroups((1..3).map { garment("g$it") })) {
            assertTrue(group.reasons.isNotEmpty(), "a group gave no reason at all")
        }
    }

    @Test
    fun `a candidate built from a garment compares by the colour the form would use`() {
        // The add form builds its candidate from the head of the palette. A sweep
        // that used the stored primary instead would disagree with the warning
        // about the same garment.
        val garment = Garment(
            id = "g1",
            category = "tops",
            colorPrimary = "#000000",
            colorPalette = listOf("#1F3A93", "#000000"),
        )

        assertEquals("#1F3A93", garment.asDuplicateCandidate().colorPrimary)
    }
}
