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
        subcategories: List<String> = listOf("T-Shirt"),
        color: String = "#1F3A93",
    ) = Garment(
        id = id,
        category = category,
        subcategories = subcategories,
        colorPrimary = color,
        colorPalette = listOf(color),
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
                garment("g1", color = "#000000"),
                garment("g2", color = "#FFD700"),
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
        // Sharing a type is not transitive: a is a T-Shirt, c is a Vest, and b is
        // filed as both. Anchored, a and b group and c is left alone. Chained, all
        // three arrive as one garment, and a T-Shirt is offered as a Vest.
        val a = garment("a", subcategories = listOf("T-Shirt"))
        val b = garment("b", subcategories = listOf("T-Shirt", "Vest"))
        val c = garment("c", subcategories = listOf("Vest"))

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
    @Test
    fun `a shirt and a jumper are not each other, however alike the rest of them is`() {
        // What the sweep was reporting before there was a gate: same category,
        // same colour, same tags, same size, and one of them is a jumper.
        val groups = duplicateGroups(
            listOf(
                garment("shirt1"),
                garment("shirt2"),
                garment("jumper", subcategories = listOf("Jumper")),
            ),
        )

        assertEquals(
            listOf(listOf("shirt1", "shirt2")),
            groups.map { group -> group.garments.map { it.id }.sorted() },
        )
    }

    @Test
    fun `garments with no type recorded are left out entirely`() {
        // Not an empty group and not a group of one: they never enter. The cost of
        // the rule, and the reason a wardrobe with no types recorded reports
        // nothing at all.
        val groups = duplicateGroups((1..3).map { garment("g$it", subcategories = emptyList()) })

        assertEquals(emptyList(), groups)
    }

}
