package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the garments land on an outfit card.
 *
 * The card is drawn twice from this one answer -- on screen, and into the image
 * that gets shared -- so anything wrong here is wrong in two places at once. What
 * matters: the card is filled (a collapsed band must give its height away, not
 * leave a hole), nothing overlaps, and the same outfit always draws the same way.
 */
class OutfitCardTest {

    private fun garment(id: String, category: String, subcategory: String? = null) =
        CardGarment(id = id, imageUri = "$id.jpg", category = category, subcategory = subcategory)

    private val top = garment("top", "tops")
    private val bottoms = garment("bottoms", "bottoms")
    private val shoes = garment("shoes", "shoes")
    private val coat = garment("coat", "outerwear")
    private val bag = garment("bag", "accessories")
    private val dress = garment("dress", "dresses")

    private fun heightOf(placements: List<CardPlacement>, id: String) =
        placements.first { it.garment.id == id }.height

    private fun topOf(placements: List<CardPlacement>, id: String) =
        placements.first { it.garment.id == id }.y

    @Test
    fun `an outfit fills the whole card`() {
        // The reason bands collapse rather than staying put: a top and a pair of
        // trousers must not leave a shoe-shaped hole along the bottom.
        for (outfit in listOf(
            listOf(top, bottoms, shoes),
            listOf(top, bottoms),
            listOf(dress),
            listOf(dress, coat, shoes, bag),
        )) {
            val placements = outfitCardLayout(outfit)
            val covered = placements.sumOf { it.width * it.height }

            assertEquals(1.0, covered, 1e-9, "outfit of ${outfit.size} left the card unfilled")
        }
    }

    @Test
    fun `nothing is drawn on top of anything else`() {
        val placements = outfitCardLayout(listOf(top, coat, bottoms, shoes, bag))

        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                val one = placements[a]
                val other = placements[b]
                val apart = one.x + one.width <= other.x + 1e-9 ||
                    other.x + other.width <= one.x + 1e-9 ||
                    one.y + one.height <= other.y + 1e-9 ||
                    other.y + other.height <= one.y + 1e-9

                assertTrue(apart, "${one.garment.id} overlaps ${other.garment.id}")
            }
        }
    }

    @Test
    fun `worn top to bottom, laid out top to bottom`() {
        val placements = outfitCardLayout(listOf(shoes, bag, bottoms, top, coat))

        assertTrue(topOf(placements, "top") < topOf(placements, "bottoms"))
        assertTrue(topOf(placements, "bottoms") < topOf(placements, "shoes"))
        // The coat is worn over the top, so it shares the top's band rather than
        // taking one above it.
        assertEquals(topOf(placements, "top"), topOf(placements, "coat"))
    }

    @Test
    fun `the garment the outfit is about leads its band`() {
        val placements = outfitCardLayout(listOf(coat, top))
        val leftmost = placements.minBy { it.x }

        assertEquals("top", leftmost.garment.id)
    }

    @Test
    fun `shoes get less of the card than a body does`() {
        val placements = outfitCardLayout(listOf(top, bottoms, shoes))

        assertTrue(heightOf(placements, "shoes") < heightOf(placements, "top"))
    }

    @Test
    fun `a collapsed band gives its height to the others`() {
        // A dress fills the upper band and leaves the lower one empty, so the
        // dress and the shoes have to grow into it.
        val withBottoms = outfitCardLayout(listOf(top, bottoms, shoes))
        val withoutBottoms = outfitCardLayout(listOf(dress, shoes))

        assertTrue(heightOf(withoutBottoms, "dress") > heightOf(withBottoms, "top"))
        assertTrue(heightOf(withoutBottoms, "shoes") > heightOf(withBottoms, "shoes"))
    }

    @Test
    fun `garments in one band share its width`() {
        val placements = outfitCardLayout(listOf(top, coat))

        assertEquals(0.5, placements.first { it.garment.id == "top" }.width, 1e-9)
        assertEquals(0.5, placements.first { it.garment.id == "coat" }.width, 1e-9)
    }

    @Test
    fun `one garment gets the whole card`() {
        val placements = outfitCardLayout(listOf(dress))

        assertEquals(1, placements.size)
        assertEquals(0.0, placements[0].x, 1e-9)
        assertEquals(0.0, placements[0].y, 1e-9)
        assertEquals(1.0, placements[0].width, 1e-9)
        assertEquals(1.0, placements[0].height, 1e-9)
    }

    @Test
    fun `a garment that fills no slot is left out`() {
        // Most underwear belongs to no part of an outfit, and a card is not the
        // place to start showing it.
        val placements = outfitCardLayout(listOf(top, garment("socks", "underwear"), bottoms))

        assertEquals(listOf("top", "bottoms"), placements.map { it.garment.id })
        assertEquals(1.0, placements.sumOf { it.width * it.height }, 1e-9)
    }

    @Test
    fun `an outfit with nothing placeable draws nothing`() {
        // The renderer's cue to show a placeholder rather than guess at an empty
        // card.
        assertTrue(outfitCardLayout(emptyList()).isEmpty())
        assertTrue(outfitCardLayout(listOf(garment("socks", "underwear"))).isEmpty())
    }

    @Test
    fun `the same outfit always draws the same way`() {
        // Two garments in one band and one order between them, whichever order
        // they arrive in: a card that reshuffled itself between the screen and the
        // shared image would be two cards.
        val one = outfitCardLayout(listOf(top, coat, bottoms, shoes, bag))
        val other = outfitCardLayout(listOf(bag, shoes, bottoms, coat, top))

        assertEquals(one, other)
    }
}
