package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A look's pieces as Gemini Nano lists them, and the wardrobe's answer to
 * each: read strictly, matched sensibly, and never one garment twice.
 */
class LookRecreationTest {

    private fun garment(id: String, category: String, type: String?, colour: String, available: Boolean = true) =
        testGarment(id, category, type).copy(colorPrimary = colour, colorPalette = listOf(colour), isAvailable = available)

    private val navy = "#000080"
    private val white = "#FFFFFF"
    private val brown = "#8B4513"
    private val black = "#000000"

    @Test
    fun `a list is read line by line, whatever a model wraps it in`() {
        val pieces = parseLookPieces(
            """
            Here is what I can see:
            1. Blazer | navy
            - **T-Shirt** | White
            * Jeans | light blue
            Loafers | brown
            Boxers | grey
            Kilt | red
            Hat | chartreuse
            Blazer | navy
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LookPiece("midlayer", "Blazer", navy),
                LookPiece("tops", "T-Shirt", white),
                LookPiece("bottoms", "Jeans", "#87CEEB"),
                LookPiece("shoes", "Loafers", brown),
                LookPiece("accessories", "Hat", null),
            ),
            pieces,
            "underwear, a type the catalogue lacks and a repeat are dropped; an unknown colour is unknown",
        )
    }

    @Test
    fun `a look is read as no more pieces than an outfit has`() {
        val many = (1..20).joinToString("\n") { "Ring | gold" } + "\n" +
            GARMENT_COLORS.take(10).joinToString("\n") { "Necklaces | ${it.first}" }
        assertEquals(MAX_LOOK_PIECES, parseLookPieces(many).size)
    }

    @Test
    fun `each piece is answered by the closest garment of its kind, and a missing one by none`() {
        val wardrobe = listOf(
            garment("blazer", "midlayer", "Blazer", "#0066CC"),
            garment("cardigan", "outerwear", "Cardigan", navy),
            garment("tee", "tops", "T-Shirt", "#FFFDD0"),
            garment("boots", "shoes", "Boots", black),
        )
        val rows = recreateLook(
            listOf(
                LookPiece("midlayer", "Blazer", navy),
                LookPiece("tops", "T-Shirt", white),
                LookPiece("shoes", "Loafers", brown),
            ),
            wardrobe,
        )

        assertEquals("blazer", rows[0].garment?.id, "a blue blazer for a navy one, not a navy cardigan")
        assertEquals("tee", rows[1].garment?.id, "cream for white")
        assertNull(rows[2].garment, "black boots are not brown loafers")
        assertEquals(PhantomGarmentOf("shoes", "Loafers", brown), rows[2].piece.asWanted())
    }

    @Test
    fun `the same type wins over the same colour, and clothing stands in for clothing but a belt is no hat`() {
        val wardrobe = listOf(
            garment("navy-chinos", "bottoms", "Chinos", navy),
            garment("blue-pants", "bottoms", "Pants", "#0066CC"),
            garment("belt", "accessories", "Belt", navy),
        )
        val rows = recreateLook(
            listOf(LookPiece("bottoms", "Pants", navy), LookPiece("accessories", "Hat", navy)),
            wardrobe,
        )

        assertEquals("blue-pants", rows[0].garment?.id, "trousers of a near colour before chinos of the exact one")
        assertNull(rows[1].garment)

        val onlyChinos = recreateLook(listOf(LookPiece("bottoms", "Pants", navy)), wardrobe.take(1))
        assertEquals("navy-chinos", onlyChinos.single().garment?.id, "chinos stand in for trousers")
    }

    @Test
    fun `a garment answers one piece, retired ones none, and the look's style breaks a tie`() {
        val wardrobe = listOf(
            garment("a", "tops", "Shirt", white),
            garment("b", "tops", "Shirt", white),
            garment("retired", "tops", "Shirt", white, available = false),
        )
        val pieces = listOf(LookPiece("tops", "Shirt", white), LookPiece("tops", "Shirt", white))

        val rows = recreateLook(pieces, wardrobe, floatArrayOf(0f, 1f), mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f)))

        assertEquals(listOf("b", "a"), rows.map { it.garment?.id }, "b looks like the look; a is what is left")
        assertTrue(rows.none { it.garment?.id == "retired" })
    }

    @Test
    fun `the prompt offers every type and colour the answer is checked against`() {
        assertTrue("Midi Dress" in LOOK_PIECES_PROMPT)
        assertTrue("light blue" in LOOK_PIECES_PROMPT)
        assertTrue("type | colour" in LOOK_PIECES_PROMPT)
    }

    @Suppress("TestFunctionName")
    private fun PhantomGarmentOf(category: String, subcategory: String?, colour: String) =
        com.wardrobapp.domain.PhantomGarment(category, subcategory, colour)

    @Test
    fun `a piece nothing answers shows the nearest thing there is, which is not saved as the look`() {
        val wardrobe = listOf(
            garment("black-skirt", "bottoms", "Skirt", black),
            garment("jeans", "bottoms", "Jeans", "#87CEEB"),
            garment("glasses", "accessories", "Eyewear", black),
            garment("belt", "accessories", "Belt", black),
        )
        val rows = recreateLook(
            listOf(
                LookPiece("bottoms", "Skirt", "#FF69B4"),
                LookPiece("accessories", "Sunglasses", black),
                LookPiece("accessories", "Hat", black),
            ),
            wardrobe,
        )

        assertNull(rows[0].garment, "black is no stand-in for pink")
        assertEquals("black-skirt", rows[0].closest?.id, "but a skirt is nearer a skirt than jeans are")
        assertEquals("glasses", rows[1].closest?.id, "glasses are the nearest thing to sunglasses")
        assertNull(rows[2].closest, "a belt is not the nearest thing to a hat")
    }

    @Test
    fun `a garment answering one piece is not the nearest thing to another`() {
        val wardrobe = listOf(garment("navy-skirt", "bottoms", "Skirt", navy))
        val rows = recreateLook(
            listOf(LookPiece("bottoms", "Skirt", navy), LookPiece("bottoms", "Skirt", "#FF69B4")),
            wardrobe,
        )

        assertEquals("navy-skirt", rows[0].garment?.id)
        assertNull(rows[1].garment)
        assertNull(rows[1].closest)
    }

    @Test
    fun `the question asks for what is clearly there`() {
        assertTrue("clearly see" in LOOK_PIECES_PROMPT)
    }
}

