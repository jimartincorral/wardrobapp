package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.wardrobapp.data.GARMENT_IMAGE_DIRNAME
import com.wardrobapp.data.PHOTO_JPEG_QUALITY
import com.wardrobapp.data.PhotoOrientation
import com.wardrobapp.data.StoredPhotoSize
import com.wardrobapp.data.cutoutFilename
import com.wardrobapp.data.decodeSampleSize
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
     * Store a background-removed photo, as PNG.
     *
     * Kept lossless because a cut-out has transparency, and JPEG has no alpha
     * channel: saving one as JPEG fills the removed background with black, which
     * is the opposite of what removing it was for.
     */
    fun storeCutout(source: Uri, id: String): String {
        val name = cutoutFilename(id)
        write(source, File(directory.also { it.mkdirs() }, name), Bitmap.CompressFormat.PNG)
        return name
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
            ?: throw IOException("That image could not be read.")

        try {
            bitmap = turnUpright(bitmap, orientation)
            bitmap = scaledTo(bitmap, target)

            // Written through a temporary file and moved into place, so a failure
            // partway leaves nothing behind for the database to point at.
            val staging = File(destination.parentFile, "${destination.name}.incoming")
            try {
                staging.outputStream().use { out ->
                    if (!bitmap.compress(format, PHOTO_JPEG_QUALITY, out)) {
                        throw IOException("That image could not be saved.")
                    }
                }
                if (!staging.renameTo(destination)) {
                    throw IOException("That image could not be saved.")
                }
            } finally {
                staging.delete()
            }
        } finally {
            bitmap.recycle()
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
            ?: throw IOException("That image could not be opened.")
}
