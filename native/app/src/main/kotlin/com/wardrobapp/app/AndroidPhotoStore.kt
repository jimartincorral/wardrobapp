package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.wardrobapp.data.GARMENT_IMAGE_DIRNAME
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.StoredCutout
import com.wardrobapp.data.PHOTO_JPEG_QUALITY
import com.wardrobapp.data.PhotoOrientation
import com.wardrobapp.data.StoredPhotoSize
import com.wardrobapp.data.cutoutFilename
import com.wardrobapp.data.cutoutsToShrink
import com.wardrobapp.data.decodeSampleSize
import com.wardrobapp.data.isCutoutFilename
import com.wardrobapp.data.maintenanceSummary
import com.wardrobapp.data.orientedSize
import com.wardrobapp.data.photoFilename
import com.wardrobapp.data.photoOrientation
import com.wardrobapp.data.storedPhotoSize
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Getting a picked photo onto disk.
 *
 * The decisions -- how big, which way up, what to call it -- are all in :data and
 * tested there. What is here is the platform work: decoding, turning, scaling and
 * writing, none of which can be done without Android.
 *
 * Photos are stored small on purpose. Every one of them ends up in every backup,
 * and a backup that will not fit anywhere is a wardrobe that cannot be moved to
 * a new phone.
 */
class AndroidPhotoStore(private val context: Context) {

    private val directory: File get() = File(context.filesDir, GARMENT_IMAGE_DIRNAME)

    /**
     * Import a photo, returning the filename it was stored under.
     *
     * The id is supplied rather than minted here so a caller can retry without
     * accumulating half-written files under new names.
     */
    fun store(source: Uri, id: String): String {
        val name = photoFilename(id)
        write(source, File(directory.also { it.mkdirs() }, name), Bitmap.CompressFormat.JPEG)
        return name
    }

    /**
     * Store a background-removed photo, as PNG, from a bitmap already in hand.
     *
     * A bitmap rather than a URI because that is what segmentation produces: there
     * is no file to read, and writing one only to read it back would be two
     * copies of a large image for nothing.
     *
     * PNG because a cut-out is transparent where the background was, and JPEG has
     * no alpha channel -- saving one as JPEG fills the removed background with
     * black, which is the opposite of removing it.
     */
    fun writeCutout(cutout: Bitmap, id: String): String {
        val name = cutoutFilename(id)
        writeBitmap(cutout, File(directory.also { it.mkdirs() }, name), Bitmap.CompressFormat.PNG)
        return name
    }

    /**
     * A photo's pixels, small, as RGBA for [com.wardrobapp.presentation.dominantGarmentColor].
     *
     * Downscaled hard first, the way `detectDominantColor` does before it averages:
     * a thumbnail is faster to average and less swayed by a pattern's detail than
     * the full image. Not turned upright, because averaging every fourth pixel does
     * not care which way up they are.
     *
     * Null when the photo cannot be decoded -- a missing file, or something that is
     * not an image. The caller says nothing rather than guessing a colour.
     */
    fun pixelsFor(source: Uri, targetWidth: Int): ByteArray? {
        val bounds = readBounds(source)
        if (bounds.width <= 0 || bounds.height <= 0) return null

        val bitmap = decode(source, (bounds.width / targetWidth).coerceAtLeast(1)) ?: return null

        return try {
            val packed = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(packed, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            // ARGB ints into RGBA bytes, which is the layout every decoder on the
            // TypeScript side produces and what the fixture is recorded in.
            // `getPixels` hands back non-premultiplied values, so a cut-out's
            // transparent pixels arrive as alpha 0 and are skipped rather than
            // averaged in as black.
            val bytes = ByteArray(packed.size * 4)
            for ((index, colour) in packed.withIndex()) {
                bytes[index * 4] = (colour shr 16 and 0xff).toByte()
                bytes[index * 4 + 1] = (colour shr 8 and 0xff).toByte()
                bytes[index * 4 + 2] = (colour and 0xff).toByte()
                bytes[index * 4 + 3] = (colour ushr 24 and 0xff).toByte()
            }
            bytes
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Shrink cut-outs that an older build stored at full resolution.
     *
     * Which files and whether each one needs it are :data's call
     * ([cutoutsToShrink]); this does the decoding, scaling and writing, and reports
     * what it came to.
     *
     * Rewritten in place, over the same filename, because the database stores that
     * name and every row referencing it has to keep working. That is also why a
     * failure on one file is swallowed and the pass continues: a cut-out that will
     * not decode is left exactly as it was, which is worse than shrinking it and
     * far better than losing it.
     *
     * [onProgress] is called with how many of the oversized files are done.
     */
    fun shrinkOversizedCutouts(onProgress: (Int, Int) -> Unit = { _, _ -> }): MaintenanceSummary {
        val files = directory.listFiles()?.toList() ?: return MaintenanceSummary(0, 0, 0)

        val cutouts = files
            .filter { it.isFile && isCutoutFilename(it.name) }
            .map { file ->
                val bounds = readBounds(Uri.fromFile(file))
                StoredCutout(
                    name = file.name,
                    width = bounds.width,
                    height = bounds.height,
                    bytes = file.length(),
                )
            }

        val oversized = cutoutsToShrink(cutouts)
        val savings = mutableListOf<Long>()

        for ((done, cutout) in oversized.withIndex()) {
            val file = File(directory, cutout.name)
            val before = file.length()

            try {
                // Through the same write path a picked photo takes, so the result
                // is the shape this app would have stored in the first place --
                // including PNG, which a cut-out has to stay: it is transparent
                // where the background was.
                write(Uri.fromFile(file), file, Bitmap.CompressFormat.PNG)
                savings += before - file.length()
            } catch (_: Exception) {
                // Any failure: an unreadable file, no room to stage a copy. The
                // original is untouched and the next file is not this one's
                // problem.
            }

            onProgress(done + 1, oversized.size)
        }

        return maintenanceSummary(examined = cutouts.size, savings = savings)
    }

    /** Remove a stored photo. A file already gone is not a failure. */
    fun delete(filename: String) {
        if (filename.isEmpty()) return
        File(directory, filename.substringAfterLast('/')).delete()
    }

    private fun write(source: Uri, destination: File, format: Bitmap.CompressFormat) {
        val bounds = readBounds(source)
        val orientation = readOrientation(source)

        // Turned first, then capped: a landscape photo tagged "rotate 90" is
        // really a portrait one, and capping it as stored puts the limit on the
        // wrong side.
        val oriented = orientedSize(bounds.width, bounds.height, orientation)
        val target = storedPhotoSize(oriented.width, oriented.height)

        var bitmap = decode(source, decodeSampleSize(bounds.width, bounds.height, target))
            ?: throw IOException(context.getString(R.string.error_image_unreadable))

        try {
            bitmap = turnUpright(bitmap, orientation)
            bitmap = scaledTo(bitmap, target)
            writeBitmap(bitmap, destination, format)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Write a bitmap to its final place.
     *
     * Through a temporary file and moved in, so a failure partway leaves nothing
     * behind for the database to point at.
     */
    private fun writeBitmap(bitmap: Bitmap, destination: File, format: Bitmap.CompressFormat) {
        val staging = File(destination.parentFile, "${destination.name}.incoming")

        try {
            staging.outputStream().use { out ->
                if (!bitmap.compress(format, PHOTO_JPEG_QUALITY, out)) {
                    throw IOException(context.getString(R.string.error_image_unsaveable))
                }
            }
            if (!staging.renameTo(destination)) {
                throw IOException(context.getString(R.string.error_image_unsaveable))
            }
        } finally {
            staging.delete()
        }
    }

    private fun readBounds(source: Uri): StoredPhotoSize = open(source).use { stream ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        StoredPhotoSize(options.outWidth, options.outHeight)
    }

    private fun readOrientation(source: Uri): PhotoOrientation = try {
        open(source).use { stream ->
            photoOrientation(
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            )
        }
    } catch (_: IOException) {
        // A photo with unreadable EXIF is still a photo. Treated as upright,
        // which is what one with no EXIF at all is.
        photoOrientation(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun decode(source: Uri, sampleSize: Int): Bitmap? = open(source).use { stream ->
        BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun turnUpright(bitmap: Bitmap, orientation: PhotoOrientation): Bitmap {
        if (orientation.isUpright) return bitmap

        val matrix = Matrix().apply {
            if (orientation.rotationDegrees != 0) {
                postRotate(orientation.rotationDegrees.toFloat())
            }
            if (orientation.mirrored) postScale(-1f, 1f)
        }

        return replacing(bitmap) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    private fun scaledTo(bitmap: Bitmap, target: StoredPhotoSize): Bitmap {
        if (bitmap.width == target.width && bitmap.height == target.height) return bitmap
        if (target.width <= 0 || target.height <= 0) return bitmap

        return replacing(bitmap) {
            Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
        }
    }

    /**
     * Swap one bitmap for another, freeing the old one.
     *
     * A full-resolution photo is tens of megabytes decoded, so holding two while
     * turning and then scaling is how an import runs out of memory on the phones
     * most likely to have large photos on them. Both operations can also hand
     * back the same instance, which must not then be recycled.
     */
    private inline fun replacing(original: Bitmap, produce: () -> Bitmap): Bitmap {
        val produced = produce()
        if (produced !== original) original.recycle()
        return produced
    }

    private fun open(source: Uri): InputStream =
        context.contentResolver.openInputStream(source)
            ?: throw IOException(context.getString(R.string.error_image_unopenable))
}
