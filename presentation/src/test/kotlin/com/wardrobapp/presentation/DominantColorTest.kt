package com.wardrobapp.presentation

import com.wardrobapp.domain.MULTI_COLOR
import com.wardrobapp.domain.colorDistance
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port reads the same colour out of the same pixels.
 *
 * The fixture carries real JPEG output -- each case was encoded and decoded again
 * on the TypeScript side -- so the artefacts are the ones a photograph actually
 * picks up, not a clean synthetic ramp. What it deliberately does *not* compare is
 * the decoding: the app this replaced averages a 64px JPEG thumbnail and this decodes
 * the original, so the two never see identical pixels for one photograph. Feeding
 * both the same bytes is what isolates the part that can be compared.
 *
 * The alpha gate has no fixture because it cannot have one: JPEG has no alpha
 * channel, so the TypeScript never exercises it. It is tested directly below, and
 * it is live here -- this app averages PNG cut-outs as well.
 */
class DominantColorTest {

    @Test
    fun `transparent pixels do not count towards the average`() {
        // The case the TypeScript cannot reach. A white garment on a transparent
        // background has to read as white; counting the background would drag it
        // to grey and then snap it somewhere else entirely.
        val transparentWhite = pixels(
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 0),
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 0),
        )

        assertEquals("#FFFFFF", averageOpaqueColor(transparentWhite))
    }

    @Test
    fun `a barely-opaque pixel is still ignored`() {
        // The edge of a cut-out is antialiased, so the pixels just outside the
        // garment carry a trace of it. 15 is under the gate and 16 is not.
        assertEquals("#000000", averageOpaqueColor(pixels(Pixel(255, 255, 255, 15))))
        assertEquals("#FFFFFF", averageOpaqueColor(pixels(Pixel(255, 255, 255, 16))))
    }

    @Test
    fun `an image with nothing in it comes out black rather than nothing`() {
        assertEquals("#000000", averageOpaqueColor(pixels(Pixel(9, 9, 9, 0))))
        assertEquals("#000000", averageOpaqueColor(ByteArray(0)))
    }

    @Test
    fun `a byte array that ends mid-pixel is not read past`() {
        // A decoder handing back an odd length is a bug somewhere else; it should
        // not become an exception here.
        assertEquals("#FFFFFF", averageOpaqueColor(pixels(Pixel(255, 255, 255, 255)) + byteArrayOf(1, 2)))
    }

    @Test
    fun `an average is rounded, not truncated`() {
        // Only visible before the snap: the palette is 25 colours, so a shade out
        // by one lands on the same entry and no fixture case can tell the two
        // apart. It still has to be right -- this is the number the snap is fed --
        // so it is pinned where it is observable.
        // Both halves have to land *on* the stride to be counted -- the filler
        // between them is skipped, which is the whole point of the stride.
        val halfway = pixels(
            Pixel(0, 0, 0, 255),
            Pixel(99, 99, 99, 255),
            Pixel(99, 99, 99, 255),
            Pixel(99, 99, 99, 255),
            Pixel(255, 255, 255, 255),
            Pixel(99, 99, 99, 255),
            Pixel(99, 99, 99, 255),
            Pixel(99, 99, 99, 255),
        )

        // 127.5 each way. Rounded is 128; truncated would be 127, which is #7F7F7F.
        assertEquals("#808080", averageOpaqueColor(halfway))
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
        // The stride is a performance decision, but it is also observable: an
        // image whose sampled pixels differ from its unsampled ones averages to
        // the sampled ones. Worth pinning, because changing the stride silently
        // changes every answer above.
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

        assertEquals("#FF0000", averageOpaqueColor(sampledRed))
    }

    private data class Pixel(val red: Int, val green: Int, val blue: Int, val alpha: Int)

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
