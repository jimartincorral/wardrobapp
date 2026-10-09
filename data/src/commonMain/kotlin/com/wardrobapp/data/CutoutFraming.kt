package com.wardrobapp.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Framing a cut-out around the garment in it.
 *
 * A cut-out keeps the canvas of the photo it was cut from: the model hands
 * back the photo with its background made transparent, and nothing moves the
 * garment. So a cape photographed off to the left is a cut-out with the cape
 * off to the left, and a pair of gloves that filled a third of the photo fills a
 * third of every frame that shows them, however the frame is drawn. The frames
 * themselves (GarmentPhoto, in :ui) show a cut-out whole, which is the right
 * choice and makes this visible: a thumbnail of a small garment in a large
 * transparent canvas is a small garment.
 *
 * The answer is to frame the cut-out once, when it is made: find the pixels
 * that are garment, give them a margin, and put that on a 3:4 canvas of its
 * own, centred, with nothing else on it. Every garment then fills its tile the
 * same way. 3:4 rather than the garment's own shape so that the cut-out stays
 * the shape every photo in this app is, which is what the grid and the outfit
 * cards are laid out for -- a wide garment gets transparent room above and
 * below, a tall one at the sides.
 *
 * This is the arithmetic, platform-free so the phone (Android bitmaps) and the
 * server (BufferedImage) frame a cut-out identically and one set of tests
 * covers both. What is left to each is reading the pixels and drawing the
 * result.
 */

/** A rectangle of pixels: [left] and [top] inclusive, [right] and [bottom] exclusive. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where a cut-out should be drawn to frame it: a transparent canvas of [width]
 * by [height], with the cut-out's own top-left corner at ([drawX], [drawY]),
 * which is usually negative -- the canvas is the window onto the cut-out that
 * holds the garment and its margin, and whatever falls outside it was empty.
 */
data class CutoutFraming(val width: Int, val height: Int, val drawX: Int, val drawY: Int) {

    /**
     * Whether drawing this would change a cut-out of [sourceWidth] by
     * [sourceHeight] enough to be worth writing a file for.
     *
     * Within [SETTLED_FRACTION] of the same canvas and the same place it is
     * left alone: a cut-out that has been framed already comes out of the
     * arithmetic a pixel different here and there from rounding, and
     * rewriting it on every pass would be a pass that never finishes its job.
     */
    fun changes(sourceWidth: Int, sourceHeight: Int): Boolean {
        val dx = (sourceWidth * SETTLED_FRACTION).roundToInt()
        val dy = (sourceHeight * SETTLED_FRACTION).roundToInt()
        return abs(width - sourceWidth) > dx || abs(height - sourceHeight) > dy ||
            abs(drawX) > dx || abs(drawY) > dy
    }

    companion object {
        /** How far off, as a fraction of the cut-out's size, still counts as framed. */
        const val SETTLED_FRACTION = 0.02f
    }
}

/** Alpha at or above which a pixel is garment rather than the fringe the model left round it. */
const val CUTOUT_OPAQUE_ALPHA = 32

/** Room round the garment, as a fraction of its longer side. */
const val CUTOUT_MARGIN = 0.04f

/** Width over height of the canvas a cut-out is framed on: the shape of every photo in this app. */
const val CUTOUT_ASPECT = 3f / 4f

/**
 * The least of a cut-out's area the garment must cover for framing to trust it.
 *
 * Below this the model most likely found a speck rather than a garment, and
 * framing a speck would blow it up to fill the tile -- a worse picture than
 * the one the person already looked at and kept.
 */
const val CUTOUT_LEAST_GARMENT = 0.01f

/**
 * The box of garment pixels in [pixels] -- ARGB ints, row by row, [width] by
 * [height] -- or null when nothing in it is opaque enough to be garment.
 */
fun opaqueBounds(pixels: IntArray, width: Int, height: Int, threshold: Int = CUTOUT_OPAQUE_ALPHA): PixelBox? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1

    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            if ((pixels[row + x] ushr 24) < threshold) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }

    return if (right < 0) null else PixelBox(left, top, right + 1, bottom + 1)
}

/**
 * How to frame a cut-out of [width] by [height] whose garment is [bounds]: the
 * garment with [CUTOUT_MARGIN] round it, centred on a [CUTOUT_ASPECT] canvas
 * just big enough. Null when there is no garment to frame, or so little of
 * one that it is not to be trusted; see [CUTOUT_LEAST_GARMENT].
 */
fun cutoutFraming(bounds: PixelBox?, width: Int, height: Int): CutoutFraming? {
    if (bounds == null || width <= 0 || height <= 0) return null
    if (bounds.width.toLong() * bounds.height < width.toLong() * height * CUTOUT_LEAST_GARMENT) return null

    val margin = (maxOf(bounds.width, bounds.height) * CUTOUT_MARGIN).roundToInt().coerceAtLeast(1)
    val contentWidth = bounds.width + 2 * margin
    val contentHeight = bounds.height + 2 * margin

    // Whichever side the content is longest on, relative to the canvas's shape,
    // sets the canvas; the other side is padded out to the shape.
    val wide = contentWidth.toFloat() / contentHeight > CUTOUT_ASPECT
    val canvasWidth = if (wide) contentWidth else (contentHeight * CUTOUT_ASPECT).roundToInt()
    val canvasHeight = if (wide) (contentWidth / CUTOUT_ASPECT).roundToInt() else contentHeight

    // The content box's top-left on the canvas, then the cut-out's own origin
    // relative to it: the content starts `margin` before the garment.
    val contentX = (canvasWidth - contentWidth) / 2
    val contentY = (canvasHeight - contentHeight) / 2
    return CutoutFraming(
        width = canvasWidth,
        height = canvasHeight,
        drawX = contentX + margin - bounds.left,
        drawY = contentY + margin - bounds.top,
    )
}
