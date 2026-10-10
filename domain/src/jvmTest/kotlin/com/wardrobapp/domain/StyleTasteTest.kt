package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taste as a direction, and outfits scored by how close they point.
 *
 * Three dimensions stand in for five hundred and twelve: the arithmetic is
 * the same and the pictures are legible.
 */
class StyleTasteTest {

    private val north = floatArrayOf(0f, 1f, 0f)
    private val east = floatArrayOf(1f, 0f, 0f)
    private val south = floatArrayOf(0f, -1f, 0f)

    @Test
    fun `nothing saved and nothing rated is no taste`() {
        assertNull(tasteVector(emptyList(), emptyList()))
    }

    @Test
    fun `a saved look points the taste its way, and a badly rated outfit points it away`() {
        val taste = tasteVector(listOf(north), listOf(listOf(east, east) to 1))!!

        // North, less east: north-west, at unit length.
        assertTrue(taste[1] > 0 && taste[0] < 0, "the taste went ${taste.toList()}")
        assertEquals(1.0, taste.map { it * it }.sum().toDouble(), absoluteTolerance = 1e-6)
    }

    @Test
    fun `an outfit scores by how close its garments' mean comes to the taste`() {
        val taste = tasteVector(listOf(north), emptyList())!!

        assertEquals(1.0, styleScore(listOf(north, north), taste), absoluteTolerance = 1e-6)
        assertEquals(0.0, styleScore(listOf(east), taste), absoluteTolerance = 1e-6)
        assertEquals(-1.0, styleScore(listOf(south), taste), absoluteTolerance = 1e-6)
        // Half north, half east: the mean points between them.
        assertTrue(styleScore(listOf(north, east), taste) in 0.6..0.8)
    }

    @Test
    fun `a garment the model has not seen is left out, and an outfit of none is no opinion`() {
        val taste = tasteVector(listOf(north), emptyList())!!

        assertEquals(1.0, styleScore(listOf(north, null), taste), absoluteTolerance = 1e-6)
        assertEquals(0.0, styleScore(listOf(null, null), taste))
    }

    @Test
    fun `opposites cancel to no taste rather than to a direction`() {
        assertNull(tasteVector(listOf(north, south), emptyList()))
    }
}
