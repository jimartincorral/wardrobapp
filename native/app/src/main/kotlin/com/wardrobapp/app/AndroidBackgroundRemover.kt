package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.wardrobapp.data.decodeSampleSize
import com.wardrobapp.data.storedPhotoSize
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cutting a garment out of its background.
 *
 * ML Kit's subject segmentation, which is what the React Native app's native
 * module uses too -- so the same model, and the same cut-outs. What is *not*
 * carried over from that module:
 *
 * - Its trimming pass, including a parallel four-quadrant `getPixel` scan, is
 *   dead code for this app: every call site passes `trim: false`.
 * - Its out-of-memory retry loop retries with identical parameters (its own
 *   comments admit as much), so it is three identical attempts rather than a
 *   strategy. Photos here are already capped at 800px on the longest side when
 *   they are imported, so roughly 640k pixels reach the model.
 * - Its capability probe calls the segmenter with a fake URI and treats any error
 *   but the iOS-only one as "supported", so on Android it always says yes.
 *
 * It also wrote the result to the app's files root for the JavaScript side to copy
 * into place, and nothing ever deleted that intermediate. This writes the cut-out
 * where it belongs, once.
 */
class AndroidBackgroundRemover(
    private val context: Context,
    private val photos: AndroidPhotoStore,
) {

    /**
     * True while a removal is running.
     *
     * The model is one shared resource and segmentation is the heaviest thing this
     * app does, so a second request is refused rather than queued -- the button is
     * disabled anyway, and refusing says something a queue would hide.
     */
    private val running = AtomicBoolean(false)

    @Volatile
    private var segmenter: SubjectSegmenter? = null

    /**
     * Remove the background from a stored photo, returning the cut-out's filename.
     *
     * Blocking, so callers run it off the main thread: ML Kit's API is callback
     * based and there is nothing useful to do while it works.
     */
    fun removeBackground(photo: Uri, id: String): String {
        if (!running.compareAndSet(false, true)) {
            throw IOException(context.getString(R.string.error_background_busy))
        }

        try {
            val bitmap = decode(photo) ?: throw IOException(context.getString(R.string.error_photo_unreadable))
            val foreground = try {
                segment(bitmap)
            } finally {
                bitmap.recycle()
            }

            try {
                return photos.writeCutout(foreground, id)
            } finally {
                foreground.recycle()
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * Run the model and wait for it.
     *
     * `InputImage.fromBitmap(bitmap, 0)` -- zero degrees, because the photo was
     * turned the right way up when it was imported, so its pixels are already
     * upright and telling the model otherwise would rotate it twice.
     */
    private fun segment(bitmap: Bitmap): Bitmap {
        val client = segmenter ?: SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        ).also { segmenter = it }

        val done = CountDownLatch(1)
        var foreground: Bitmap? = null
        var failure: Exception? = null

        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                foreground = result.foregroundBitmap
                done.countDown()
            }
            .addOnFailureListener { error ->
                failure = error
                done.countDown()
            }

        done.await()

        failure?.let { throw IOException(describe(it), it) }

        return foreground ?: throw IOException(context.getString(R.string.error_no_subject))
    }

    /**
     * Say what went wrong in terms worth reading.
     *
     * The model arrives through Play Services and is fetched on demand, so the
     * first attempt on a fresh install can fail while it is still downloading --
     * which is a wait, not a fault, and worth saying differently. The exact
     * exception surface is not something I can check without a device, so this
     * matches on what the message says and falls through to it otherwise.
     */
    private fun describe(error: Exception): String {
        val message = error.message ?: error.javaClass.simpleName
        val notReady = listOf("download", "unavailable", "not available", "module")
            .any { message.contains(it, ignoreCase = true) }

        return if (notReady) {
            context.getString(R.string.error_background_downloading)
        } else {
            context.getString(R.string.error_background_failed, message)
        }
    }

    /**
     * Decode the photo, sub-sampled towards the size it is stored at.
     *
     * The arithmetic comes from :data rather than a second copy of it here.
     * ARGB_8888 because the model hands back an alpha channel and the result has to
     * be able to hold it.
     */
    private fun decode(photo: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(photo)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IOException(context.getString(R.string.error_photo_unopenable))

        val target = storedPhotoSize(bounds.outWidth, bounds.outHeight)

        return context.contentResolver.openInputStream(photo)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, target)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }

}
