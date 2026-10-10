package com.wardrobapp.server

import com.wardrobapp.api.Routes
import com.wardrobapp.api.ServerException
import com.wardrobapp.api.ServerFeatures
import com.wardrobapp.domain.Formality
import com.wardrobapp.domain.GarmentAttributes
import com.wardrobapp.presentation.GarmentFormState
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.ContentType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seeing a garment's style: the encoder around a stand-in model, the anchors
 * that turn a vector into attributes, the index that keeps a wardrobe
 * embedded and filled in, and the looks a reader saves -- and, once, the real
 * model, when the build has it.
 */
class StyleTest {

    /** A photo of a flat [colour], as a JPEG the server can decode. */
    private fun photo(colour: Color, width: Int = 60, height: Int = 80): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = colour; fillRect(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(image, "jpg", it); it.toByteArray() }
    }

    /**
     * A model that answers with the picture's average colour, as a vector of
     * four: red, green, blue and a constant. The anchors below are written in
     * the same space, so a red photo is "formal" and a green one "casual".
     */
    private class ColourEncoder(override val size: Int = 8) : ImageEncoder {
        var closed = false
        override fun embed(input: FloatArray): FloatArray {
            val plane = size * size
            fun mean(channel: Int) = (0 until plane).sumOf { input[channel * plane + it].toDouble() } / plane
            // Undo the normalisation enough to keep the sign: brighter than the
            // mean is positive.
            return floatArrayOf(mean(0).toFloat() + 2f, mean(1).toFloat() + 2f, mean(2).toFloat() + 2f, 0.5f)
        }
        override fun close() { closed = true }
    }

    private val anchorsJson = """
        {"model": "colour-test", "imageSize": 8, "mean": [0.48, 0.46, 0.41], "deviation": [0.27, 0.26, 0.28],
         "dimensions": 4,
         "anchors": {
           "formality": {"formal": [1, 0, 0, 0], "casual": [0, 1, 0, 0], "lounge": [0, 0, 1, 0]},
           "pattern": {"solid": [0, 0, 0, 1], "print": [0, 0, 0, 1]},
           "weight": {"light": [0.1, 0.1, 0.1, 0.9]}
         }}
    """.trimIndent()

    private fun encoder() = StyleEncoder(StyleAnchors.parse(anchorsJson)) { ColourEncoder() }

    private fun unitLength(vector: FloatArray) = vector.sumOf { (it * it).toDouble() }

    @Test
    fun `the anchors parse, and the nearest one wins only when it wins clearly`() {
        val anchors = StyleAnchors.parse(anchorsJson)

        assertEquals("colour-test", anchors.model)
        assertEquals(3, anchors.formality.size)
        val read = anchors.attributesFor(floatArrayOf(1f, 0f, 0f, 0f))
        assertEquals(Formality.FORMAL, read.formality)
        // Two anchors that say the same thing decide nothing; one anchor alone
        // cannot be nearest than another.
        assertNull(read.pattern, "a tie was read as an answer")
        assertNull(read.weight, "a single anchor was read as an answer")
        assertNull(read.fit, "an attribute with no anchors was read as an answer")
    }

    @Test
    fun `a photo becomes a unit vector that says what colour it was`() {
        encoder().use { encoder ->
            val red = encoder.embed(photo(Color(220, 20, 20)))
            val green = encoder.embed(photo(Color(20, 220, 20)))

            assertEquals(4, red.size)
            assertEquals(1.0, unitLength(red), absoluteTolerance = 1e-5)
            assertTrue(red[0] > red[1] && green[1] > green[0], "the encoder did not see the colours")
            assertEquals(Formality.FORMAL, encoder.anchors.attributesFor(red).formality)
            assertEquals(Formality.CASUAL, encoder.anchors.attributesFor(green).formality)
        }
    }

    @Test
    fun `the model is let go when idle`() {
        val model = ColourEncoder()
        val encoder = StyleEncoder(StyleAnchors.parse(anchorsJson), idleSeconds = 0) { model }
        encoder.embed(photo(Color.RED))
        val deadline = System.currentTimeMillis() + 5_000
        while (encoder.loaded) {
            check(System.currentTimeMillis() < deadline) { "the model was never let go" }
            Thread.sleep(10)
        }
        assertTrue(model.closed)
        encoder.close()
    }

    @Test
    fun `the index embeds each garment once per photo, and fills in attributes nobody set`() = serverTest(style = encoder()) {
        val red = addGarment(photo = uploadPhoto(photo(Color(220, 20, 20))))
        val chosen = garmentForm.save(
            garmentId = null,
            form = GarmentFormState(
                imageUris = listOf(uploadPhoto(photo(Color(20, 220, 20)))),
                bgRemovedUris = listOf(""),
                attributes = GarmentAttributes(formality = Formality.LOUNGE),
                colorPalette = listOf("#00FF00"),
                colorsChosen = true,
            ),
            previouslyStored = emptyList(),
        ).let { everything().single { it.id != red.id } }

        // The saves queued a pass of their own; waiting for one more is
        // waiting for them, and the pass after that finds nothing to do.
        wardrobe.style!!.refreshNow()
        assertEquals(0, wardrobe.style!!.refreshNow(), "a second pass embedded something again")

        val vectors = wardrobe.styleQueries.embeddings()
        assertEquals(setOf(red.id, chosen.id), vectors.keys)
        assertEquals(1.0, unitLength(vectors.getValue(red.id)), absoluteTolerance = 1e-5)

        val garments = everything().associateBy { it.id }
        assertTrue("formality:formal" in garments.getValue(red.id).tags, "the red garment was not read as formal: ${garments.getValue(red.id).tags}")
        // The one somebody set keeps what they set, whatever the model thinks.
        assertTrue("formality:lounge" in garments.getValue(chosen.id).tags)
        assertFalse(garments.getValue(chosen.id).tags.any { it == "formality:casual" })
    }

    @Test
    fun `a look is kept, listed, embedded into the taste, and let go with its photo`() = serverTest(style = encoder()) {
        assertTrue(http.get(Routes.FEATURES).body<ServerFeatures>().learnsStyle)

        val look = inspirations.add(uploadPhoto(photo(Color(220, 20, 20))))
        assertEquals(listOf(look.id), inspirations.looks().map { it.id })
        assertTrue(look.imageUri.startsWith("p/main/photos/"), look.imageUri)

        wardrobe.style!!.refreshNow()
        assertEquals(1, wardrobe.styleQueries.inspirationVectors().size)

        inspirations.delete(look.id)
        assertTrue(inspirations.looks().isEmpty())
        assertTrue(wardrobe.styleQueries.inspirationVectors().isEmpty())
        assertFalse(File(photoDirectory, look.imageUri.substringAfterLast('/')).exists(), "the look's photo stayed")
    }

    @Test
    fun `a server without the model says so, and keeps no looks`() = serverTest {
        assertFalse(http.get(Routes.FEATURES).body<ServerFeatures>().learnsStyle)
        val failure = assertFailsWith<ServerException> { inspirations.add(uploadPhoto(photo(Color.RED))) }
        assertEquals(501, failure.status)
    }

    @Test
    fun `an embedded garment lifts outfits that look like the saved looks`() = serverTest(style = encoder()) {
        // Two reds and a green across tops and bottoms; a saved red look. The
        // red outfit is what the taste points at.
        // All one neutral grey as far as the engine's own colour terms go, so
        // the photos -- which only the style model sees -- are what differ.
        val red = addGarment(category = "tops", subcategory = "Shirt", colour = "#808080", photo = uploadPhoto(photo(Color(220, 20, 20))))
        val green = addGarment(category = "tops", subcategory = "T-Shirt", colour = "#808080", photo = uploadPhoto(photo(Color(20, 220, 20))))
        addGarment(category = "bottoms", subcategory = "Jeans", colour = "#808080", photo = uploadPhoto(photo(Color(220, 20, 20))))
        addGarment(category = "shoes", subcategory = "Sneakers", colour = "#808080", photo = uploadPhoto(photo(Color(220, 20, 20))))
        inspirations.add(uploadPhoto(photo(Color(220, 20, 20))))
        wardrobe.style!!.refreshNow()

        val suggestions = outfits.suggest(com.wardrobapp.presentation.SuggestionRequest(filters = com.wardrobapp.presentation.OutfitFilters(), alreadySeen = emptyList(), seedGarmentId = null, count = 4))
        val best = suggestions.first().outfit
        assertTrue(best.garments.any { it.id == red.id } && best.garments.none { it.id == green.id }, "the green top led: ${best.garments.map { it.id }}")
        assertTrue(suggestions.any { com.wardrobapp.domain.OutfitReason.STYLE in it.outfit.reasons }, "no suggestion says it looks like the saved look")
    }

    /**
     * The real model, when the build downloaded it: nothing about what a
     * synthetic picture means, since a flat colour means nothing to CLIP,
     * only that it answers a unit vector of the advertised size, the same
     * one twice.
     */
    @Test
    fun `the real model answers a stable unit vector`() {
        val model = System.getProperty("styleModel")?.let(::File) ?: return
        val anchors = System.getProperty("styleAnchors")?.let(::File) ?: return
        assertTrue(model.isFile && anchors.isFile, "the build did not download the style model to $model")
        StyleEncoder.at(model, anchors)!!.use { encoder ->
            val once = encoder.embed(photo(Color(120, 60, 200), 400, 300))
            val twice = encoder.embed(photo(Color(120, 60, 200), 400, 300))
            assertEquals(encoder.anchors.dimensions, once.size)
            assertEquals(1.0, unitLength(once), absoluteTolerance = 1e-4)
            assertTrue(once.indices.all { abs(once[it] - twice[it]) < 1e-5 }, "the model is not deterministic")
        }
    }
}
