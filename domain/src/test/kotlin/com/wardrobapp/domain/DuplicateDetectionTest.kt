package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether a garment is one the wardrobe already has.
 *
 * The rule is the whole of it: the same kind of thing, in the same colours. There
 * is no score and no threshold to tune, so what is worth testing is where the two
 * conditions draw their lines -- and, as much, that the things which used to matter
 * no longer do.
 */
class DuplicateDetectionTest {

    private fun existing(
        id: String,
        subcategories: List<String> = listOf("T-Shirt"),
        colors: List<String> = listOf("#1F3A93"),
        tags: List<String> = emptyList(),
        size: String? = null,
    ) = Garment(
        id = id,
        category = "tops",
        subcategories = subcategories,
        tags = tags,
        colorPrimary = colors.first(),
        colorPalette = colors,
        size = size,
    )

    private fun candidate(
        subcategories: List<String> = listOf("T-Shirt"),
        colors: List<String> = listOf("#1F3A93"),
    ) = DuplicateCandidate(
        category = "tops",
        subcategories = subcategories,
        colorPrimary = colors.first(),
        colorPalette = colors,
    )

    private fun matchIds(candidate: DuplicateCandidate, vararg existing: Garment) =
        findDuplicatesAmong(candidate, existing.toList()).map { it.garment.id }

    @Test
    fun `the same kind of thing in the same colour is the same garment`() {
        assertEquals(listOf("g1"), matchIds(candidate(), existing("g1")))
    }

    @Test
    fun `two kinds of thing are never the same thing`() {
        assertEquals(emptyList(), matchIds(candidate(), existing("g1", subcategories = listOf("Jumper"))))
    }

    @Test
    fun `one type in common is enough`() {
        // A garment can be filed under more than one type, and two shirts both
        // filed as T-Shirt are the same kind of thing whatever else either is.
        assertEquals(
            listOf("g1"),
            matchIds(
                candidate(subcategories = listOf("Top", "T-Shirt")),
                existing("g1", subcategories = listOf("T-Shirt", "Vest")),
            ),
        )
    }

    @Test
    fun `a garment with no type is not a duplicate of anything`() {
        assertEquals(
            emptyList(),
            matchIds(candidate(subcategories = emptyList()), existing("g1")),
            "a garment with no type matched one that has one",
        )

        // Including another with none. Two unknowns are not a known.
        assertEquals(
            emptyList(),
            matchIds(candidate(subcategories = emptyList()), existing("g1", subcategories = emptyList())),
            "two garments with no type matched each other",
        )
    }

    @Test
    fun `the same shirt in two colours is two shirts`() {
        assertEquals(emptyList(), matchIds(candidate(), existing("g1", colors = listOf("#B22222"))))
    }

    @Test
    fun `two blacks a shade apart are the same black`() {
        // The reason colour is a distance and not an equal hex string: a
        // hand-entered or imported colour need not land on a palette entry.
        assertEquals(
            listOf("g1"),
            matchIds(candidate(colors = listOf("#000000")), existing("g1", colors = listOf("#0A0A0A"))),
        )
    }

    @Test
    fun `a colour nothing can read is not a colour to match on`() {
        assertEquals(
            emptyList(),
            matchIds(candidate(colors = listOf("not-a-colour")), existing("g1", colors = listOf("not-a-colour"))),
        )
    }

    @Test
    fun `a black and red shirt is not a red shirt`() {
        val twoTone = listOf("#B22222", "#000000")

        assertEquals(
            emptyList(),
            matchIds(candidate(colors = twoTone), existing("g1", colors = listOf("#B22222"))),
        )

        // And the other way round, which is the same mistake with the arguments
        // swapped -- easy to fix in one direction only.
        assertEquals(
            emptyList(),
            matchIds(candidate(colors = listOf("#B22222")), existing("g1", colors = twoTone)),
        )
    }

    @Test
    fun `two black and red shirts are each other`() {
        val twoTone = listOf("#B22222", "#000000")

        assertEquals(listOf("g1"), matchIds(candidate(colors = twoTone), existing("g1", colors = twoTone)))
    }

    @Test
    fun `which colour dominates is a fact about the photograph, not the garment`() {
        assertEquals(
            listOf("g1"),
            matchIds(
                candidate(colors = listOf("#B22222", "#000000")),
                existing("g1", colors = listOf("#000000", "#B22222")),
            ),
        )
    }

    @Test
    fun `a red and black shirt is not a red and white one`() {
        assertEquals(
            emptyList(),
            matchIds(
                candidate(colors = listOf("#B22222", "#000000")),
                existing("g1", colors = listOf("#B22222", "#FFFFFF")),
            ),
        )
    }

    @Test
    fun `a colour cannot answer for two`() {
        // Without spending a partner once claimed, a garment in two shades of the
        // same red would match one that is that red twice over, and two palettes
        // of two would agree while sharing a single colour.
        assertEquals(
            emptyList(),
            matchIds(
                candidate(colors = listOf("#B22222", "#B4241F")),
                existing("g1", colors = listOf("#B22222", "#000000")),
            ),
        )
    }

    @Test
    fun `tags have nothing to say about it`() {
        // They used to carry six tenths of the score. Two garments sharing not one
        // word are the same garment if they are the same thing in the same colour.
        assertEquals(
            listOf("g1"),
            matchIds(candidate(), existing("g1", tags = listOf("formal", "wool", "winter"))),
        )
    }

    @Test
    fun `a size is not part of what a garment is`() {
        // The same shirt in an M and an L is the same shirt.
        assertEquals(listOf("g1"), matchIds(candidate(), existing("g1", size = "XXL")))
    }

    @Test
    fun `an empty wardrobe has nothing to match`() {
        assertEquals(emptyList(), findDuplicatesAmong(candidate(), emptyList()))
    }

    @Test
    fun `matches come back in the order they were given`() {
        // Nothing left to rank by: every match satisfies the same rule to the same
        // degree, and an order would imply one is more of a duplicate than another.
        assertEquals(
            listOf("first", "second", "third"),
            matchIds(candidate(), existing("first"), existing("second"), existing("third")),
        )
    }

    @Test
    fun `a candidate built from a garment compares by the colours the form would use`() {
        val garment = Garment(
            id = "g1",
            category = "tops",
            subcategories = listOf("T-Shirt"),
            colorPrimary = "#000000",
            colorPalette = listOf("#1F3A93", "#000000"),
        )

        assertEquals(listOf("#1F3A93", "#000000"), garment.asDuplicateCandidate().colorPalette)
        assertEquals("#1F3A93", garment.asDuplicateCandidate().colorPrimary)
    }
}
