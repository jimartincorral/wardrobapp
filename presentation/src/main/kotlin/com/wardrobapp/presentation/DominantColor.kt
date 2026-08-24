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
 * Held to the TypeScript by `dominant-color.jsonl`. What the fixture compares is
 * this arithmetic and not the decoding: the app this replaced re-encodes a 64px
 * thumbnail as JPEG and averages that, while this decodes the original with
 * Android's own decoder, so the two never see identical pixels for one photograph.
 * The fixture therefore carries the pixels the TypeScript actually saw -- JPEG
 * artefacts and all -- and asks whether the same bytes give the same answer.
 */

/**
 * How much of the image to look at.
 *
 * Every fourth pixel, which for an RGBA array is a stride of 16 bytes. Averaging a
 * quarter of a thumbnail is indistinguishable from averaging all of it, and this
 * runs while someone is waiting for a form to fill itself in.
 */
private const val SAMPLE_STRIDE = 16

/**
 * The alpha below which a pixel does not count.
 *
 * Not zero: a cut-out's edge is antialiased, so the pixels just outside the garment
 * carry a little of its colour and a lot of nothing, and counting those pulls every
 * average towards black.
 *
 * On the TypeScript side this is unreachable -- it averages a decoded JPEG, and
 * JPEG has no alpha channel. Here it is live, because this app hands over PNG
 * cut-outs too: the whole point of removing a background is that what is left is
 * transparent.
 */
private const val MINIMUM_ALPHA = 16

/**
 * The average colour of the pixels worth counting.
 *
 * Black when nothing was worth counting, which is what a fully transparent image
 * comes to. A colour rather than null on purpose: the caller's next move is to snap
 * it to the palette either way, and black is the honest answer for an image with
 * nothing in it.
 *
 * [pixels] is RGBA, four bytes per pixel, as every decoder on both sides produces.
 */
fun averageOpaqueColor(pixels: ByteArray): String {
    var red = 0L
    var green = 0L
    var blue = 0L
    var counted = 0

    var index = 0
    // `+ 3` so a trailing partial pixel is skipped rather than read past: an odd
    // byte count is a decoder bug, not something to crash on.
    while (index + 3 < pixels.size) {
        if (pixels.byteAt(index + 3) >= MINIMUM_ALPHA) {
            red += pixels.byteAt(index)
            green += pixels.byteAt(index + 1)
            blue += pixels.byteAt(index + 2)
            counted++
        }
        index += SAMPLE_STRIDE
    }

    if (counted == 0) return "#000000"

    return rgbToHex(
        divideRounded(red, counted),
        divideRounded(green, counted),
        divideRounded(blue, counted),
    )
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

/** The palette colour a photo's pixels average out to. */
fun dominantGarmentColor(pixels: ByteArray): String =
    nearestGarmentColor(averageOpaqueColor(pixels))

/** A byte as the unsigned value it represents, which is what a channel is. */
private fun ByteArray.byteAt(index: Int): Int = this[index].toInt() and 0xff

/**
 * Rounded division, matching `Math.round`.
 *
 * Kotlin's `Math.round` and JavaScript's agree on halves -- both round up -- so
 * this is `(total + half) / count` for positive numbers, which these always are.
 */
private fun divideRounded(total: Long, count: Int): Int =
    ((total * 2 + count) / (count * 2L)).toInt()

private fun rgbToHex(red: Int, green: Int, blue: Int): String =
    "#%02X%02X%02X".format(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
