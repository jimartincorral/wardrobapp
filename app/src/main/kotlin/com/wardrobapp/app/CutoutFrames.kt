package com.wardrobapp.app

import android.graphics.Bitmap
import android.graphics.Canvas
import com.wardrobapp.data.cutoutFraming
import com.wardrobapp.data.opaqueBounds

/**
 * This cut-out framed around its garment, or null when framing it would change
 * nothing worth writing: it is framed already, or there is no garment in it
 * to frame. The arithmetic and the reasons are in :data's CutoutFraming; this
 * is the reading of pixels and the drawing back that need Android.
 *
 * A new bitmap rather than this one changed in place, since the canvas is a
 * different size; the caller recycles both.
 */
fun Bitmap.framedAroundGarment(): Bitmap? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val framing = cutoutFraming(opaqueBounds(pixels, width, height), width, height) ?: return null
    if (!framing.changes(width, height)) return null

    // ARGB, transparent where nothing is drawn: a new bitmap starts out all
    // zero, which in ARGB is transparent black.
    val framed = Bitmap.createBitmap(framing.width, framing.height, Bitmap.Config.ARGB_8888)
    Canvas(framed).drawBitmap(this, framing.drawX.toFloat(), framing.drawY.toFloat(), null)
    return framed
}
