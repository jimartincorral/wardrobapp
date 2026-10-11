package com.wardrobapp.data

import com.wardrobapp.domain.Formality
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The style model's shared half: the anchors, the arithmetic that prepares a
 * picture, and the pass that keeps a wardrobe embedded -- what the phone and
 * the Home Assistant app both run, tested here once without either's model.
 */
class StyleModelTest {

    private val driver = JdbcSqlDriver.fresh()
    private val style = StyleQueries(driver)
    private val garments = GarmentQueries(driver, "")
    private val writes = GarmentWrites(driver)

    @AfterTest
    fun close() = driver.close()

    private val anchors = StyleAnchors.parse(
        """{"model": "test", "imageSize": 2, "mean": [0.5, 0.5, 0.5], "deviation": [0.25, 0.25, 0.25], "dimensions": 2,
            "anchors": {"formality": {"formal": [1, 0], "casual": [0, 1]}, "pattern": {"solid": [1, 0]}}}""",
    )

    @Test
    fun `the nearest anchor names the attribute, and a toss-up names nothing`() {
        assertEquals(Formality.FORMAL, anchors.attributesFor(floatArrayOf(0.9f, 0.1f)).formality)
        assertEquals(Formality.CASUAL, anchors.attributesFor(floatArrayOf(0.1f, 0.9f)).formality)
        assertNull(anchors.attributesFor(floatArrayOf(0.5f, 0.5f)).formality, "a tie was called")
        // One anchor is no choice at all.
        assertNull(anchors.attributesFor(floatArrayOf(1f, 0f)).pattern)
    }

    @Test
    fun `the square is the middle of the picture, its shorter side scaled to the model's`() {
        val (landscape, landscapeSquare) = centreSquare(400, 200, 224)
        assertEquals(PixelRect(0, 0, 448, 224), landscape)
        assertEquals(PixelRect(112, 0, 224, 224), landscapeSquare)

        val (portrait, portraitSquare) = centreSquare(300, 400, 224)
        assertEquals(PixelRect(0, 0, 224, 299), portrait)
        assertEquals(PixelRect(0, 37, 224, 224), portraitSquare)

        // A pixel from square loses nothing to rounding, and the square is never short.
        assertEquals(PixelRect(0, 0, 225, 224) to PixelRect(0, 0, 224, 224), centreSquare(225, 224, 224))
    }

    @Test
    fun `pixels become three normalised planes, and transparency is black, as the server draws it`() {
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        val clear = 0x00FFFFFF
        val halfWhite = 0x80FFFFFF.toInt()
        val input = clipInput(intArrayOf(white, red, clear, halfWhite), size = 2, anchors = anchors)

        // Red plane, then green, then blue; (value - 0.5) / 0.25.
        assertEquals(listOf(2f, 2f, 2f), listOf(input[0], input[4], input[8]), "white")
        assertEquals(listOf(2f, -2f, -2f), listOf(input[1], input[5], input[9]), "red")
        assertEquals(listOf(-2f, -2f, -2f), listOf(input[2], input[6], input[10]), "transparent")
        assertEquals(0.008f, input[3], 0.01f, "half-transparent white is mid-grey")
    }

    private fun garment(id: String, photo: String, tags: List<String> = emptyList()) = writes.insert(
        GarmentWrites.NewGarment(
            id = id,
            imageUri = photo,
            imageUris = listOf(photo),
            category = "tops",
            colorPrimary = "#112233",
            colorPalette = listOf("#112233"),
            tags = tags,
            now = "2026-01-01T00:00:00.000Z",
        ),
    )

    @Test
    fun `a pass embeds each photo once, fills in attributes nobody set, and embeds the looks`() {
        garment("plain", "plain.jpg")
        garment("chosen", "chosen.jpg", tags = listOf("formality:lounge"))
        garment("unreadable", "broken.jpg")
        style.addInspiration("look", "look.jpg", "2026-01-01T00:00:00.000Z")

        val asked = mutableListOf<String>()
        val indexer = StyleIndexer(
            style = style,
            garments = garments,
            garmentWrites = writes,
            model = anchors.model,
            anchors = anchors,
            embedPhoto = { photo ->
                asked += photo
                if (photo == "broken.jpg") null else floatArrayOf(1f, 0f)
            },
            now = { "2026-01-02T00:00:00.000Z" },
        )

        assertEquals(3, indexer.pass())
        assertEquals(setOf("plain", "chosen"), style.embeddings().keys)
        assertEquals(1, style.inspirationVectors().size)
        assertTrue("formality:formal" in garments.garment("plain")!!.tags, "the model's reading was not written")
        assertEquals(listOf("formality:lounge"), garments.garment("chosen")!!.tags, "a person's choice was overwritten")

        // The second pass asks only for the photo that failed, and embeds nothing.
        asked.clear()
        assertEquals(0, indexer.pass())
        assertEquals(listOf("broken.jpg"), asked)
        assertFalse(style.embeddings().containsKey("unreadable"))
    }
}
