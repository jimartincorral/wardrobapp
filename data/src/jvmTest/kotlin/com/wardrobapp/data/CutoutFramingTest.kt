package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing a cut-out around its garment.
 *
 * The pixels are made here rather than read from files: a canvas of
 * transparent ints with an opaque block where the garment is, which is all
 * the arithmetic looks at. The phone's and the server's tests cover reading a
 * real image into that shape and drawing the answer back out.
 */
class CutoutFramingTest {

    private val opaque = 0xFF000000.toInt()

    /** A [width] by [height] transparent canvas with an opaque [box] on it, at [alpha]. */
    private fun canvas(width: Int, height: Int, box: PixelBox, alpha: Int = 255): IntArray {
        val pixels = IntArray(width * height)
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                pixels[y * width + x] = (alpha shl 24) or 0x102030
            }
        }
        return pixels
    }

    @Test
    fun `the garment's box is found, and nothing else`() {
        val box = PixelBox(10, 5, 50, 65)
        assertEquals(box, opaqueBounds(canvas(120, 80, box), 120, 80))
    }

    @Test
    fun `a canvas with nothing opaque on it has no bounds`() {
        assertNull(opaqueBounds(IntArray(120 * 80), 120, 80))
    }

    @Test
    fun `the faint fringe the model leaves round a garment is not garment`() {
        // Alpha 20 everywhere but the garment: a half-transparent haze of
        // floor, which would otherwise put the garment's box at the edges.
        val haze = IntArray(120 * 80) { (20 shl 24) or 0x404040 }
        val box = PixelBox(30, 20, 60, 60)
        for (y in box.top until box.bottom) for (x in box.left until box.right) haze[y * 120 + x] = opaque

        assertEquals(box, opaqueBounds(haze, 120, 80))
    }

    @Test
    fun `a garment off to one side is centred on a three-to-four canvas with a margin`() {
        // A 40 by 60 garment at the far left of a 120 by 80 cut-out: tall, so
        // its height sets the canvas and the width is padded out to 3:4.
        val framing = cutoutFraming(PixelBox(0, 10, 40, 70), 120, 80)!!

        // Margin: 4% of 60, rounded, is 2. Content 44 by 64; canvas 48 by 64.
        assertEquals(64, framing.height)
        assertEquals(48, framing.width)
        // The garment's left edge lands at (48 - 44) / 2 + 2 = 4, so the
        // cut-out's origin, 0 to the garment's left, is drawn at 4.
        assertEquals(4, framing.drawX)
        // And its top edge at 0 + 2, with the cut-out's origin 10 above it.
        assertEquals(2 - 10, framing.drawY)
        assertTrue(framing.changes(120, 80))
    }

    @Test
    fun `a wide garment gets its room above and below`() {
        val framing = cutoutFraming(PixelBox(20, 30, 100, 50), 120, 80)!!

        // 80 by 20, margin 3: content 86 by 26, wider than 3:4, so the width
        // sets the canvas and the height is 86 / 0.75.
        assertEquals(86, framing.width)
        assertEquals(115, framing.height)
        assertEquals(3 - 20, framing.drawX)
        assertEquals((115 - 26) / 2 + 3 - 30, framing.drawY)
    }

    @Test
    fun `a cut-out already framed is left alone`() {
        // Frame once, then ask what framing the result would get: the same
        // canvas, give or take rounding, which is not a change.
        val first = cutoutFraming(PixelBox(0, 10, 40, 70), 120, 80)!!
        val framedBounds = PixelBox(
            0 + first.drawX,
            10 + first.drawY,
            40 + first.drawX,
            70 + first.drawY,
        )
        val second = cutoutFraming(framedBounds, first.width, first.height)!!

        assertFalse(second.changes(first.width, first.height), "a framed cut-out was framed again: $second")
    }

    @Test
    fun `a canvas the model found almost nothing on is not framed`() {
        // Four pixels of 9,600: a speck, which blown up to fill a tile would be
        // worse than the empty-looking cut-out the person already has.
        assertNull(cutoutFraming(PixelBox(50, 40, 52, 42), 120, 80))
        assertNull(cutoutFraming(null, 120, 80))
    }
}
