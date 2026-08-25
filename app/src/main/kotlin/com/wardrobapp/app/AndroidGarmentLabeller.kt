package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.wardrobapp.presentation.ImageLabel
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How long to wait for the model before calling it a failure. */
private const val MODEL_TIMEOUT_SECONDS = 60L

/**
 * How sure the model has to be before it says anything at all.
 *
 * Lower than the floor [com.wardrobapp.presentation.suggestGarmentType] applies,
 * deliberately: this one only decides how much of the tail crosses a process
 * boundary, and the decision about what is worth acting on belongs in the module
 * that can be tested. Zero would work and would carry a hundred labels for nothing.
 */
private const val LABELLER_THRESHOLD = 0.3f

/**
 * What a photo of a garment appears to contain.
 *
 * ML Kit's general image labelling model, through Play Services, which is the same
 * arrangement the background removal already uses: the model is not in the APK, and
 * Play Services fetches it the first time it is asked for. So the first tap on a
 * fresh install can be a download and the ones after it are not, and the app is
 * about a megabyte larger rather than several tens.
 *
 * "General" is the honest word for it. This model knows a few hundred everyday
 * things, of which perhaps forty are clothing, so it will say "Footwear" where a
 * garment-specific classifier would say "Chelsea boot". What it says is turned into
 * this app's own vocabulary by [com.wardrobapp.presentation.suggestGarmentType],
 * which is where every judgement about that lives -- this class decodes a photo,
 * waits for a callback and hands over (text, confidence) pairs. Replacing the model
 * with a better one is a change to this file and nothing else.
 */
class AndroidGarmentLabeller(
    private val context: Context,
    private val photos: AndroidPhotoStore,
) {

    /**
     * True while a labelling is running.
     *
     * One shared model and a button that disables itself, so a second request is
     * refused rather than queued -- the same call the background removal makes, for
     * the same reason.
     */
    private val running = AtomicBoolean(false)

    @Volatile
    private var labeller: ImageLabeler? = null

    /**
     * Label a stored photo.
     *
     * Blocking, so callers run it off the main thread: ML Kit's API is callback
     * based and there is nothing useful to do while it works.
     *
     * An empty list means the model ran and recognised nothing it was sure enough
     * about, which is an answer. A failure -- an unreadable photo, a model that
     * never arrived -- is an exception, because those are worth saying out loud.
     */
    fun labelsFor(photo: Uri): List<ImageLabel> {
        if (!running.compareAndSet(false, true)) {
            throw IOException(context.getString(R.string.error_labels_busy))
        }

        try {
            val bitmap = photos.bitmapFor(photo)
                ?: throw IOException(context.getString(R.string.error_photo_unreadable))

            return try {
                label(bitmap)
            } finally {
                bitmap.recycle()
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
    private fun label(bitmap: Bitmap): List<ImageLabel> {
        val client = labeller ?: ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(LABELLER_THRESHOLD).build()
        ).also { labeller = it }

        val done = CountDownLatch(1)
        var labels: List<ImageLabel> = emptyList()
        var failure: Exception? = null

        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                labels = result.map { ImageLabel(it.text, it.confidence) }
                done.countDown()
            }
            .addOnFailureListener { error ->
                failure = error
                done.countDown()
            }

        // Bounded, for the reason the segmentation's wait is: the model arrives
        // through Play Services, and a download that stalls calls neither listener.
        if (!done.await(MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException(context.getString(R.string.error_labels_timed_out))
        }

        failure?.let { throw IOException(describe(it), it) }

        return labels
    }

    /**
     * Say what went wrong in terms worth reading.
     *
     * The first attempt on a fresh install can fail while the model is still
     * downloading, which is a wait rather than a fault. Matched on the message
     * rather than the exception type for the same reason the segmentation does: the
     * exact surface is not something that can be checked without a device.
     */
    private fun describe(error: Exception): String {
        val message = error.message ?: error.javaClass.simpleName
        val notReady = listOf("download", "unavailable", "not available", "module")
            .any { message.contains(it, ignoreCase = true) }

        return if (notReady) {
            context.getString(R.string.error_labels_downloading)
        } else {
            context.getString(R.string.error_labels_failed, message)
        }
    }
}
