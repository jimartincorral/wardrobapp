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
 * The form is prefilled from this when a photo arrives, and again when its
 * background is removed. `GarmentFormState.withDetectedColors` is the transition,
 * and it is where the rule about not overwriting a chosen palette lives -- so what
 * this function owes is an honest reading of the pixels, nothing more.
 *
 * This is where the port stops matching the app it came from, deliberately. That
 * app averaged the pixels and snapped the average; the average of a red-and-white
 * striped shirt is pink, a colour appearing nowhere in it, and the average of a
 * navy shirt on a white duvet is pale blue. Both were reported from a phone. So
 * the rule is now "snap first, then count": the palette colour covering the most
 * of the garment wins, and a runner-up covering enough of it is the garment's
 * second colour. The fixture that held the two apps to the same answers went with
 * the corpus, and this would have broken it on purpose.
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
 * How much of the garment a second colour has to cover to be one.
 *
 * A fifth. Every photograph of a plain garment has a tail of other palette
 * entries in it -- a fold in shadow snaps to black, a lit edge to white -- and
 * listing those as the garment's colours would make every garment two-coloured.
 * A pattern is not shy: the pale half of a striped shirt is a third of it, a
 * contrast panel or a print's ground is a quarter. A fifth sits between the two.
 */
private const val SECONDARY_SHARE = 0.2

/**
 * The palette colours a garment's pixels are, most of it first.
 *
 * Every sampled pixel is snapped to its nearest palette entry and the entry
 * holding the most pixels wins. That is the whole difference from the mean this
 * replaces: a mean invents a colour that is in none of the pixels, and a mode
 * cannot -- whatever it returns, that much of the garment really is that colour.
 *
 * One or two entries. The second is the runner-up, and only when it covers at
 * least [SECONDARY_SHARE] of what was counted: a striped shirt and a garment with
 * a contrast panel really are two colours, and saying so saves a tap; a plain
 * navy shirt whose folds snap to black is one colour, and listing black would be
 * a correction to undo.
 *
 * Ties go to the palette's own order rather than to whichever pixel was read
 * first, so the same photo always gives the same answer.
 *
 * Empty when nothing was worth counting, which is what a fully transparent image
 * comes to. Empty rather than black: "no colours were read" and "the garment is
 * black" are different answers, and the caller is the one that knows what to do
 * with the first.
 *
 * [pixels] is RGBA, four bytes per pixel, as every decoder produces.
 */
fun dominantGarmentColors(pixels: ByteArray): List<String> {
    val counts = HashMap<String, Int>()

    // Snapping is 24 distance comparisons over parsed hex, and a photograph repeats
    // its colours enormously, so identical pixels are snapped once. Same answers,
    // a fraction of the work.
    val snapped = HashMap<Int, String>()

    var index = 0
    var counted = 0
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
            counted++
        }
        index += SAMPLE_STRIDE
    }

    if (counted == 0) return emptyList()

    // Palette order, then count: `sortedByDescending` is stable, so what is left
    // after it is the palette's order among equal counts rather than a hash map's.
    val ranked = GARMENT_COLORS
        .mapNotNull { entry -> counts[entry.second]?.let { entry.second to it } }
        .sortedByDescending { it.second }

    val primary = ranked.firstOrNull() ?: return emptyList()
    val secondary = ranked.drop(1).firstOrNull { it.second >= counted * SECONDARY_SHARE }

    return listOfNotNull(primary.first, secondary?.first)
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
