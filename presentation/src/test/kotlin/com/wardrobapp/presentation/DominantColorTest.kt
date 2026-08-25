package com.wardrobapp.presentation

import com.wardrobapp.domain.MULTI_COLOR
import com.wardrobapp.domain.colorDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which colours a garment's pixels come to.
 *
 * The interesting cases are the two that were reported from a phone, because both
 * of them the old mean got wrong by inventing a colour: a striped garment averaged
 * to a shade appearing nowhere in it, and a garment photographed on a pale
 * background averaged towards the background. A mode cannot invent -- whatever it
 * returns, that many pixels really are that colour -- so what is left to test is
 * the counting, the tie, the pixels that must not be counted at all, and where the
 * line between "the garment's second colour" and "the shadows in its folds" falls.
 */
class DominantColorTest {

    @Test
    fun `the colour most of the garment is comes first, not the average of it`() {
        // The striped shirt. Two thirds white, one third black: the mean is a grey
        // that neither stripe is, and the answer is white -- then black, because a
        // third of the garment being black is the garment being two colours.
        val striped = sampled(
            Pixel(255, 255, 255, 255),
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 255),
        )

        assertEquals(listOf("#FFFFFF", "#000000"), dominantGarmentColors(striped))
    }

    @Test
    fun `a majority of one colour is not diluted by a spread of others`() {
        // Four navy pixels against one each of four other colours. A mean would be
        // dragged somewhere between all five; a mode counts navy four times. And
        // none of the other four is a fifth of the garment, so navy is all of the
        // answer -- which is the case that matters, because this is what a plain
        // garment photographed in a real room looks like.
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

        assertEquals(listOf("#000080"), dominantGarmentColors(navy))
    }

    @Test
    fun `a second colour has to be a fifth of the garment to be one`() {
        // Either side of the line, with the same two colours. One white pixel in
        // six is a sixth and is not the garment's colour; one in five is.
        val speck = sampled(
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(255, 255, 255, 255),
        )
        assertEquals(listOf("#000080"), dominantGarmentColors(speck))

        val panel = sampled(
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(0, 0, 128, 255),
            Pixel(255, 255, 255, 255),
        )
        assertEquals(listOf("#000080", "#FFFFFF"), dominantGarmentColors(panel))
    }

    @Test
    fun `only two colours are ever reported, however many the garment has`() {
        // A print has more than two and there is no useful answer past the second:
        // a palette pre-filled with five colours is a palette to empty out.
        val print = sampled(
            Pixel(255, 0, 0, 255),
            Pixel(255, 0, 0, 255),
            Pixel(255, 255, 255, 255),
            Pixel(0, 0, 0, 255),
            Pixel(255, 215, 0, 255),
        )

        assertEquals(2, dominantGarmentColors(print).size)
    }

    @Test
    fun `two colours in equal measure go to the palette's order`() {
        // Black is the palette's first entry and white its second, so an even split
        // is black then white -- not whichever pixel the loop happened to reach
        // first.
        val even = sampled(Pixel(255, 255, 255, 255), Pixel(0, 0, 0, 255))

        assertEquals(listOf("#000000", "#FFFFFF"), dominantGarmentColors(even))
        assertEquals(
            listOf("#000000", "#FFFFFF"),
            dominantGarmentColors(sampled(Pixel(0, 0, 0, 255), Pixel(255, 255, 255, 255))),
        )
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

        assertEquals(listOf("#FFFFFF"), dominantGarmentColors(transparentWhite))
    }

    @Test
    fun `a barely-opaque pixel is still ignored`() {
        // The edge of a cut-out is antialiased, so the pixels just outside the
        // garment carry a trace of it. 15 is under the gate and 16 is not.
        assertEquals(emptyList(), dominantGarmentColors(pixels(Pixel(255, 255, 255, 15))))
        assertEquals(listOf("#FFFFFF"), dominantGarmentColors(pixels(Pixel(255, 255, 255, 16))))
    }

    @Test
    fun `an image with nothing in it reads as no colours rather than as black`() {
        // Which is the distinction the form needs: "nothing was read" leaves the
        // palette alone, and "the garment is black" replaces it. Returning black
        // for an unreadable photo would paint garments black.
        assertEquals(emptyList(), dominantGarmentColors(pixels(Pixel(9, 9, 9, 0))))
        assertEquals(emptyList(), dominantGarmentColors(ByteArray(0)))
    }

    @Test
    fun `a byte array that ends mid-pixel is not read past`() {
        // A decoder handing back an odd length is a bug somewhere else; it should
        // not become an exception here.
        assertEquals(
            listOf("#FFFFFF"),
            dominantGarmentColors(pixels(Pixel(255, 255, 255, 255)) + byteArrayOf(1, 2)),
        )
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

        assertEquals(listOf("#000000", "#FFFFFF"), dominantGarmentColors(nearlyBlack))
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
