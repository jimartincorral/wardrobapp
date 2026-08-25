package com.wardrobapp.presentation

import com.wardrobapp.domain.MULTI_COLOR
import com.wardrobapp.domain.colorDistance

/**
 * The colour a garment photo suggests, from its pixels.
 *
 * A port of `src/utils/dominant-color.ts`. Here rather than in :domain because the
 * palette it snaps to lives here, and here rather than in :app because pixels in
 * and a palette entry out is arithmetic -- the kind that is wrong by a shade and
 * looks fine, so it is worth being able to ask it questions without a device.
 *
 * The form is prefilled from this when a photo arrives, exactly as
 * `useGarmentForm` does it: a suggestion that goes to the front of the palette
 * rather than a replacement for a choice already made. `GarmentFormState.
 * withDetectedColor` is the transition, and it was already ported.
 *
 * This is where the port stops matching the app it came from, deliberately. That
 * app averaged the pixels and snapped the average; the average of a red-and-white
 * striped shirt is pink, a colour appearing nowhere in it, and the average of a
 * navy shirt on a white duvet is pale blue. Both were reported from a phone. So
 * the rule is now "snap first, then count": the palette colour covering the most
 * of the garment wins. The fixture that held the two apps to the same answers went
 * with the corpus, and this would have broken it on purpose.
 */

/**
 * How much of the image to look at.
 *
 * Every fourth pixel, which for an RGBA array is a stride of 16 bytes. A quarter of
 * a thumbnail's pixels put the same colour in front on any garment large enough to
 * photograph, and this runs while someone is waiting for a form to fill itself in.
 */
private const val SAMPLE_STRIDE = 16

/**
 * The alpha below which a pixel does not count.
 *
 * Not zero: a cut-out's edge is antialiased, so the pixels just outside the garment
 * carry a little of its colour and a lot of nothing. There are a great many of them
 * around a complicated outline, and counted they are a vote for black.
 *
 * On the TypeScript side this is unreachable -- it averaged a decoded JPEG, and
 * JPEG has no alpha channel. Here it is live, because this app hands over PNG
 * cut-outs too: the whole point of removing a background is that what is left is
 * transparent.
 */
private const val MINIMUM_ALPHA = 16

/**
 * The answer when there was nothing to count.
 *
 * Black, which is also the palette's first entry, so a caller never has to handle a
 * colour the palette does not contain.
 */
private const val NO_COLOUR = "#000000"

/**
 * The palette colour the most pixels of a garment are.
 *
 * Every sampled pixel is snapped to its nearest palette entry and the entry
 * holding the most pixels wins. That is the whole difference from the mean this
 * replaces: a mean invents a colour that is in none of the pixels, and a mode
 * cannot -- whatever it returns, that much of the garment really is that colour.
 *
 * Ties go to the palette's own order rather than to whichever pixel was read
 * first, so the same photo always gives the same answer.
 *
 * Black when nothing was worth counting, which is what a fully transparent image
 * comes to: a colour rather than null, because black is the honest answer for an
 * image with nothing in it and the caller has a palette entry to show either way.
 *
 * [pixels] is RGBA, four bytes per pixel, as every decoder produces.
 */
fun dominantGarmentColor(pixels: ByteArray): String {
    val counts = HashMap<String, Int>()

    // Snapping is 24 distance comparisons over parsed hex, and a photograph repeats
    // its colours enormously, so identical pixels are snapped once. Same answers,
    // a fraction of the work.
    val snapped = HashMap<Int, String>()

    var index = 0
    // `+ 3` so a trailing partial pixel is skipped rather than read past: an odd
    // byte count is a decoder bug, not something to crash on.
    while (index + 3 < pixels.size) {
        if (pixels.byteAt(index + 3) >= MINIMUM_ALPHA) {
            val red = pixels.byteAt(index)
            val green = pixels.byteAt(index + 1)
            val blue = pixels.byteAt(index + 2)

            val colour = snapped.getOrPut((red shl 16) or (green shl 8) or blue) {
                nearestGarmentColor(rgbToHex(red, green, blue))
            }
            counts[colour] = (counts[colour] ?: 0) + 1
        }
        index += SAMPLE_STRIDE
    }

    val most = counts.values.maxOrNull() ?: return NO_COLOUR

    return GARMENT_COLORS.firstOrNull { counts[it.second] == most }?.second ?: NO_COLOUR
}

/**
 * The palette entry closest to a colour.
 *
 * "Multi" is excluded because it is not a colour -- it stands for a garment with
 * several, and nothing should be snapped to it by accident.
 */
fun nearestGarmentColor(hex: String): String {
    val palette = GARMENT_COLORS.filter { it.second != MULTI_COLOR }
    var nearest = palette.firstOrNull()?.second ?: "#000000"
    var nearestDistance = Double.POSITIVE_INFINITY

    for ((_, candidate) in palette) {
        // A colour that will not parse is skipped rather than compared: on the
        // TypeScript side `null < Infinity` is true, so a bare comparison there
        // would pick an unreadable entry as the nearest one, and this is the same
        // guard written where it cannot be forgotten.
        val distance = colorDistance(hex, candidate) ?: continue
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearest = candidate
        }
    }

    return nearest
}

/** A byte as the unsigned value it represents, which is what a channel is. */
private fun ByteArray.byteAt(index: Int): Int = this[index].toInt() and 0xff

/** A pixel's channels as the hex the palette and [colorDistance] are written in. */
private fun rgbToHex(red: Int, green: Int, blue: Int): String =
    "#%02X%02X%02X".format(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
