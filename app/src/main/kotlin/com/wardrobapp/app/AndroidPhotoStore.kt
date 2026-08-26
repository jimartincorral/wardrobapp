package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
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
import com.wardrobapp.data.unreferencedPhotos
import com.wardrobapp.data.orientedSize
import com.wardrobapp.data.photoFilename
import com.wardrobapp.data.photoOrientation
import com.wardrobapp.data.storedPhotoSize
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * How long a photo is left alone before a sweep may take it.
 *
 * An hour, and the number matters: a cut-out is written the moment the background
 * comes off, and nothing references it until the form is saved. Somebody filling
 * in a garment while the sweep runs must not watch its photo vanish.
 */
private const val SWEEP_GRACE_MILLIS = 60L * 60L * 1000L

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
     * A stored photo as a bitmap, no wider than asked for.
     *
     * For drawing a photo somewhere Compose is not doing the drawing -- the outfit
     * card composed into one image. Sampled down on the way in rather than scaled
     * afterwards, because six full-size garment photos decoded at once is how a
     * card render runs out of memory on the phones that have least of it.
     *
     * Not turned upright, and it does not need to be: everything this draws was
     * written by [store], which turns a photo before it saves it. A photo from
     * outside would need the EXIF read; a stored one has none left to read.
     *
     * Null when the photo cannot be decoded, which is a garment whose file has
     * gone. The caller leaves a gap rather than failing the whole card.
     */
    fun bitmapFor(source: Uri, targetWidth: Int): Bitmap? {
        val bounds = readBounds(source)
        if (bounds.width <= 0 || bounds.height <= 0) return null

        // A square target: the sampler halves only while *both* dimensions would
        // still clear it, so asking for a square of the wanted width errs towards
        // a bitmap slightly too big rather than one too small to draw sharply.
        val target = StoredPhotoSize(targetWidth, targetWidth)

        return decode(source, decodeSampleSize(bounds.width, bounds.height, target))
    }

    /**
     * A photo's pixels, small, as RGBA for [com.wardrobapp.presentation.dominantGarmentColors].
     *
     * Downscaled hard first, the way `detectDominantColor` did: a thumbnail is
     * faster to count and less swayed by a pattern's detail than the full image.
     * Not turned upright, because counting every fourth pixel does not care which
     * way up they are.
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

            // ARGB ints into RGBA bytes, which is the layout `dominantGarmentColors`
            // reads and what every decoder on the TypeScript side produced.
            // `getPixels` hands back non-premultiplied values, so a cut-out's
            // transparent pixels arrive as alpha 0 and are skipped rather than
            // counted as black -- which is what lets detection run on a cut-out and
            // see only the garment.
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

    /**
     * Delete the photos no garment points at any more.
     *
     * Which files those are is :data's call ([unreferencedPhotos]); what is decided
     * here is the one thing it cannot know, which is whether a file is old enough
     * to be safe. A cut-out written for a form that has not been saved yet is
     * referenced by nothing but is not rubbish, so anything touched in the last
     * [SWEEP_GRACE_MILLIS] is left alone -- the pass is safe to run again in an
     * hour, and the alternative is deleting the photo somebody is looking at.
     *
     * [referenced] must come from *every* garment, retired ones included. A
     * retired garment is still a garment and its photos are the point of being able
     * to un-retire it.
     */
    fun deleteUnreferenced(
        referenced: List<String>,
        now: Long = System.currentTimeMillis(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): MaintenanceSummary {
        val files = directory.listFiles()?.filter { it.isFile } ?: return MaintenanceSummary(0, 0, 0)

        val settled = files.filter { now - it.lastModified() >= SWEEP_GRACE_MILLIS }
        val condemned = unreferencedPhotos(settled.map { it.name }, referenced).toSet()

        var freed = 0L
        var deleted = 0

        for ((done, file) in files.filter { it.name in condemned }.withIndex()) {
            val bytes = file.length()

            // A file that will not delete -- gone already, or held open -- is not a
            // failure worth stopping for, and it is not counted as reclaimed.
            if (file.delete()) {
                freed += bytes
                deleted++
            }

            onProgress(done + 1, condemned.size)
        }

        return MaintenanceSummary(
            examined = files.size,
            shrunk = 0,
            bytesSaved = freed,
            deleted = deleted,
        )
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

    /**
     * A photo decoded at the size it is stored at, ready for the segmentation
     * model.
     *
     * Here rather than in [AndroidBackgroundRemover] because opening a photo is
     * this class's job and it already does it correctly. The version that lived
     * over there wrote
     *
     *     contentResolver.openInputStream(photo)?.use { decodeStream(it, null, bounds) }
     *         ?: throw IOException("That photo could not be opened.")
     *
     * with `inJustDecodeBounds = true` in `bounds` -- and a bounds-only decode
     * *always* returns null, by design, since the point is to fill in the options
     * rather than produce a bitmap. So the elvis fired on every photo that opened
     * perfectly, and background removal failed on every photo in the wardrobe with
     * a message about the file being unopenable. One reader, one place, so there is
     * no second copy to get that wrong in.
     *
     * ARGB_8888 because the model hands back an alpha channel and the result has
     * to be able to hold it. Null when the photo cannot be decoded at all; the
     * failure to *open* it throws, which is a different thing and reads
     * differently.
     */
    fun bitmapFor(source: Uri): Bitmap? {
        val bounds = readBounds(source)
        if (bounds.width <= 0 || bounds.height <= 0) return null

        val target = storedPhotoSize(bounds.width, bounds.height)

        return open(source).use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = decodeSampleSize(bounds.width, bounds.height, target)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
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
            bitmap.scale(target.width, target.height)
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
