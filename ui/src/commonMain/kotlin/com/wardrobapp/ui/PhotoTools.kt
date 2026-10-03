package com.wardrobapp.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the platform can do to a photo, for the screens that offer it.
 *
 * The phone removes a background with ML Kit, which is Android's; the browser,
 * whose photos are worked on by the Home Assistant server, has nothing to remove
 * one with yet. A screen asks this rather than being told by each caller, so a
 * button for something the platform cannot do is not drawn at all -- one that
 * was drawn and then failed would be a promise broken on every tap.
 *
 * Putting a background back is not in question: it only swaps one stored photo
 * for another, and any platform can do that, including to a cut-out the phone
 * made before the wardrobe moved.
 *
 * Cropping is the phone's crop screen, an Android activity; the browser stores a
 * photo as it was picked.
 */
data class PhotoTools(
    val removesBackgrounds: Boolean = true,
    val crops: Boolean = true,
)

val LocalPhotoTools = staticCompositionLocalOf { PhotoTools() }
