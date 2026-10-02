package com.wardrobapp.data

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How a garment photo is stored: how big, and under what name.
 *
 * The arithmetic and the naming are here because they are the parts that can be
 * wrong without anything failing -- a photo stored four times larger than
 * intended still displays, and it is the backups that get heavy. Decoding and
 * writing pixels needs the platform and lives in :app.
 */

/**
 * The longest side a stored photo is allowed.
 *
 * 800, as the React Native app uses -- but applied to the longest side rather
 * than to the width. Resizing by width alone means a tall photo keeps its
 * height: a 1000x4000 import became 800x3200, which is four times the pixels the
 * cap implies, and the cost lands in every backup from then on.
 */
const val MAX_PHOTO_DIMENSION = 800

/** JPEG quality for stored photos, as a percentage. 0.7 in the TypeScript. */
const val PHOTO_JPEG_QUALITY = 70

/** The pixel size a photo should be stored at. */
data class StoredPhotoSize(val width: Int, val height: Int)

/**
 * Fit a photo inside the cap, without ever enlarging it.
 *
 * The TypeScript passes a target width to expo-image-manipulator, which scales
 * *to* that width -- so importing a thumbnail produced a bigger file than the
 * thumbnail. Nothing is gained by inventing pixels, so a photo already inside
 * the cap is stored as it is.
 */
fun storedPhotoSize(
    width: Int,
    height: Int,
    maxDimension: Int = MAX_PHOTO_DIMENSION,
): StoredPhotoSize {
    // A decoder reporting nothing is a photo that will fail to decode; that is
    // the caller's problem to report, not something to invent a size for.
    if (width <= 0 || height <= 0) return StoredPhotoSize(width, height)

    val longest = max(width, height)
    if (longest <= maxDimension) return StoredPhotoSize(width, height)

    val scale = maxDimension.toDouble() / longest.toDouble()

    // At least one pixel each way: a panorama scaled by its long side can round
    // its short side to zero, and a zero-width bitmap cannot be created at all.
    return StoredPhotoSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * The name a photo is stored under.
 *
 * [id] is supplied by the caller -- a UUID in the app, a fixed string in tests --
 * so nothing here reaches for a source of randomness.
 */
fun photoFilename(id: String): String = "$id.jpg"

/**
 * The name a background-removed photo is stored under.
 *
 * PNG because a cut-out has transparency and JPEG has no alpha channel, so
 * saving one as JPEG fills the removed background with black.
 *
 * The `_nobg` suffix is a cross-app contract, not decoration: the React Native
 * app finds cut-outs by that suffix when it recompresses the ones saved before
 * they were downscaled. A cut-out written under any other name would be missed.
 */
fun cutoutFilename(id: String): String = "${id}_nobg.png"

/** True for a name written by [cutoutFilename], in either app. */
fun isCutoutFilename(name: String): Boolean = name.endsWith("_nobg.png")

/**
 * How a photo has to be turned before it is the right way up.
 *
 * A photo from a camera roll usually records its orientation in EXIF rather than
 * in the pixels, and a decoder that ignores the tag hands back a garment lying on
 * its side. Modelled over the raw EXIF values rather than the platform's
 * constants so the mapping -- the part that is easy to get wrong, since half the
 * eight cases also mirror -- can be tested without an emulator.
 */
data class PhotoOrientation(val rotationDegrees: Int, val mirrored: Boolean) {
    val isUpright: Boolean get() = rotationDegrees == 0 && !mirrored

    /** True when the turn swaps the photo's width and height. */
    val swapsSides: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
}

/**
 * Read an EXIF orientation tag.
 *
 * The eight values are the standard ones; anything else -- absent, zero, or
 * nonsense -- means "as stored", which is what a photo with no tag is.
 */
fun photoOrientation(exifOrientation: Int): PhotoOrientation = when (exifOrientation) {
    2 -> PhotoOrientation(0, mirrored = true)
    3 -> PhotoOrientation(180, mirrored = false)
    4 -> PhotoOrientation(180, mirrored = true)
    5 -> PhotoOrientation(90, mirrored = true)
    6 -> PhotoOrientation(90, mirrored = false)
    7 -> PhotoOrientation(270, mirrored = true)
    8 -> PhotoOrientation(270, mirrored = false)
    else -> PhotoOrientation(0, mirrored = false)
}

/**
 * The size a photo has once it is the right way up.
 *
 * Turning has to come before capping, not after: a 4000x1000 photo tagged
 * "rotate 90" is really 1000x4000, and capping it as stored would produce
 * 800x200 -- the cap applied to the wrong side, and the photo still sideways.
 */
fun orientedSize(width: Int, height: Int, orientation: PhotoOrientation): StoredPhotoSize =
    if (orientation.swapsSides) StoredPhotoSize(height, width) else StoredPhotoSize(width, height)

/**
 * How much to sub-sample while decoding.
 *
 * A full-resolution photo from a modern phone is tens of megabytes decoded, and
 * decoding one only to scale it down is how an import runs the app out of
 * memory. Android's decoder takes a power-of-two divisor, so this picks the
 * largest one that still leaves every side at or above the target: sub-sampling
 * past the target would throw away detail the stored photo is meant to keep.
 */
fun decodeSampleSize(sourceWidth: Int, sourceHeight: Int, target: StoredPhotoSize): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    if (target.width <= 0 || target.height <= 0) return 1

    var sample = 1
    while (
        sourceWidth / (sample * 2) >= target.width &&
        sourceHeight / (sample * 2) >= target.height
    ) {
        sample *= 2
    }

    return sample
}
