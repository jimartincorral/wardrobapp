package com.wardrobapp.presentation

import com.wardrobapp.domain.MULTI_COLOR
import com.wardrobapp.domain.colorDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which colour a garment's pixels come to.
 *
 * The interesting cases are the two that were reported from a phone, because both
 * of them the old mean got wrong by inventing a colour: a striped garment averaged
 * to a shade appearing nowhere in it, and a garment photographed on a pale
 * background averaged towards the background. A mode cannot invent -- whatever it
 * returns, that many pixels really are that colour -- so what is left to test is
 * the counting, the tie, and the pixels that must not be counted at all.
 */
class DominantColorTest {

    @Test
    fun `the colour most of the garment is wins, not the average of it`() {
        // The striped shirt. Two thirds white, one third black: the mean is a grey
        // that neither stripe is, and the answer is white.
        val striped = sampled(
            Pixel(255, 255, 255, 255),
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 255),
        )

        assertEquals("#FFFFFF", dominantGarmentColor(striped))
    }

    @Test
    fun `a majority of one colour is not diluted by a spread of others`() {
        // Four navy pixels against one each of four other colours. A mean would be
        // dragged somewhere between all five; a mode counts navy four times.
        val navy = sampled(
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(255, 255, 255, 255),
            Pixel(255, 0, 0, 255),
            Pixel(255, 215, 0, 255),
            Pixel(34, 139, 34, 255),
        )

        assertEquals("#000080", dominantGarmentColor(navy))
    }

    @Test
    fun `two colours in equal measure go to the palette's order`() {
        // Black is the palette's first entry and white its second, so an even split
        // is black -- not whichever pixel the loop happened to reach first.
        val even = sampled(Pixel(255, 255, 255, 255), Pixel(0, 0, 0, 255))

        assertEquals("#000000", dominantGarmentColor(even))
        assertEquals("#000000", dominantGarmentColor(sampled(Pixel(0, 0, 0, 255), Pixel(255, 255, 255, 255))))
    }

    @Test
    fun `transparent pixels do not count`() {
        // A white garment on a transparent background reads as white. Counting the
        // background would make the background the majority and win it outright,
        // which is worse than the mean ever was.
        val transparentWhite = sampled(
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 0),
            Pixel(0, 0, 0, 0),
            Pixel(0, 0, 0, 0),
        )

        assertEquals("#FFFFFF", dominantGarmentColor(transparentWhite))
    }

    @Test
    fun `a barely-opaque pixel is still ignored`() {
        // The edge of a cut-out is antialiased, so the pixels just outside the
        // garment carry a trace of it. 15 is under the gate and 16 is not.
        assertEquals("#000000", dominantGarmentColor(pixels(Pixel(255, 255, 255, 15))))
        assertEquals("#FFFFFF", dominantGarmentColor(pixels(Pixel(255, 255, 255, 16))))
    }

    @Test
    fun `an image with nothing in it comes out black rather than nothing`() {
        assertEquals("#000000", dominantGarmentColor(pixels(Pixel(9, 9, 9, 0))))
        assertEquals("#000000", dominantGarmentColor(ByteArray(0)))
    }

    @Test
    fun `a byte array that ends mid-pixel is not read past`() {
        // A decoder handing back an odd length is a bug somewhere else; it should
        // not become an exception here.
        assertEquals("#FFFFFF", dominantGarmentColor(pixels(Pixel(255, 255, 255, 255)) + byteArrayOf(1, 2)))
    }

    @Test
    fun `a shade off the palette is counted as the entry it snaps to`() {
        // Nothing in a photograph is exactly a palette hex. Two near-blacks and one
        // near-white are two votes for black and one for white, not three colours
        // with one vote each.
        val nearlyBlack = sampled(
            Pixel(4, 4, 6, 255),
            Pixel(7, 5, 3, 255),
            Pixel(250, 252, 249, 255),
        )

        assertEquals("#000000", dominantGarmentColor(nearlyBlack))
    }

    @Test
    fun `multi cannot win, which is why the guard is a guard`() {
        // `nearestGarmentColor` filters "multi" out of the palette. That filter
        // changes no answer today, and this is the reason: `colorDistance` reports
        // a fixed distance for multi, and no colour in the whole cube is further
        // than that from a real palette entry. So the filter is a guard, not a
        // decision.
        //
        // Asserted rather than assumed, because it stops being true the moment
        // either the constant shrinks or the palette thins out -- at which point
        // the guard is suddenly load-bearing and whoever changed the constant
        // should be told, not surprised.
        val multiDistance = colorDistance("#000000", MULTI_COLOR)
            ?: error("multi has no distance, so the guard is doing something else now")

        var furthest = -1.0
        for (red in 0..255 step 16) {
            for (green in 0..255 step 16) {
                for (blue in 0..255 step 16) {
                    val hex = "#%02X%02X%02X".format(red, green, blue)
                    val nearest = GARMENT_COLORS
                        .filter { it.second != MULTI_COLOR }
                        .mapNotNull { colorDistance(hex, it.second) }
                        .min()
                    if (nearest > furthest) furthest = nearest
                }
            }
        }

        assertTrue(
            furthest < multiDistance,
            "a colour is $furthest from the palette but only $multiDistance from multi, " +
                "so multi can now win and the filter in nearestGarmentColor is load-bearing",
        )
        assertTrue(GARMENT_COLORS.any { it.second == MULTI_COLOR }, "multi is not in the palette")
    }

    @Test
    fun `only every fourth pixel is looked at`() {
        // The stride is a performance decision and also an observable one: six blue
        // pixels lose to two red ones, because only the red ones are on the stride.
        // Worth pinning, because changing it silently changes every answer above.
        val sampledRed = pixels(
            Pixel(255, 0, 0, 255),   // sampled
            Pixel(0, 0, 255, 255),
            Pixel(0, 0, 255, 255),
            Pixel(0, 0, 255, 255),
            Pixel(255, 0, 0, 255),   // sampled
            Pixel(0, 0, 255, 255),
            Pixel(0, 0, 255, 255),
            Pixel(0, 0, 255, 255),
        )

        assertEquals("#CC0000", dominantGarmentColor(sampledRed))
    }

    /**
     * Pixels that all land on the stride.
     *
     * Every fourth pixel is looked at, so a case about *counting* has to space its
     * pixels out or three of them are one vote. The filler is a colour no palette
     * entry is near enough to matter, and it is never counted anyway.
     */
    private fun sampled(vararg values: Pixel): ByteArray {
        val spaced = values.flatMap { listOf(it, FILLER, FILLER, FILLER) }
        return pixels(*spaced.toTypedArray())
    }

    private data class Pixel(val red: Int, val green: Int, val blue: Int, val alpha: Int)

    /** Transparent, so it is skipped even if the stride ever changes under this. */
    private val FILLER = Pixel(0, 0, 0, 0)

    private fun pixels(vararg values: Pixel): ByteArray {
        val bytes = ByteArray(values.size * 4)
        for ((index, pixel) in values.withIndex()) {
            bytes[index * 4] = pixel.red.toByte()
            bytes[index * 4 + 1] = pixel.green.toByte()
            bytes[index * 4 + 2] = pixel.blue.toByte()
            bytes[index * 4 + 3] = pixel.alpha.toByte()
        }
        return bytes
    }
}
