package com.wardrobapp.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "GeminiNano"

/**
 * Gemini Nano, where the phone has it: a question about a photo, or about
 * some text, answered on the phone.
 *
 * Through ML Kit's Prompt API, which reaches the model Android's AICore keeps
 * on the phone; nothing is sent anywhere. Only some phones have it -- recent
 * Pixels and a list of others Google keeps -- and on the rest every question is
 * answered null at once, which every caller takes as "read nothing". The app
 * asks AICore to fetch the model only from a button that says so (the look
 * screen's): a phone can have Gemini Nano for its own features and still
 * answer DOWNLOADABLE to an app that has not asked for it before, and nothing
 * on a garment form should start a download nobody tapped for.
 *
 * The answers are only ever text, and only ever trusted as far as the parsers
 * that read them allow -- each one is held to the app's own vocabulary
 * (parseGarmentReading), so a model that rambles, or answers in another
 * language, fills in nothing rather than something wrong.
 *
 * The questions are put in English whatever the app's language, because the
 * values they ask for are stored in English and the Prompt API is surest of
 * it; Google had not said it understands Spanish when this was written.
 *
 * Structured output (a `@Generable` class and its KSP schema compiler) was the
 * other way to get a typed answer back, and was left: it brings KSP into a
 * build with none, for answers of a few short lines the parser checks anyway.
 *
 * One question at a time: the model is a single shared thing on the phone, and
 * a second request while one is running would only queue inside AICore with no
 * way to give up on it.
 */
class GeminiNano {

    private val lock = Mutex()

    /**
     * What AICore said, kept only when it is final for this process: here
     * (AVAILABLE) or never here (UNAVAILABLE, no client). DOWNLOADABLE and
     * DOWNLOADING are asked again every time, because they change while the
     * app is open; remembering them is what left looks unopenable on a phone
     * that had Gemini Nano but had not been given it for this app yet.
     */
    private var known: Status? = null

    /** Where Gemini Nano stands for this app on this phone. */
    enum class Status { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    private val model: GenerativeModel? by lazy {
        // The Prompt API needs Android 8; the app runs from 7. The manifest
        // lets the library in (tools:overrideLibrary) and this keeps it from
        // being touched below 8, where its classes would fail to load.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            null
        } else {
            try {
                Generation.getClient()
            } catch (e: Throwable) {
                Log.w(TAG, "No Prompt API on this phone: $e")
                null
            }
        }
    }

    /** Whether Gemini Nano can answer here now. */
    suspend fun isAvailable(): Boolean = status() == Status.AVAILABLE

    /** Where Gemini Nano stands, asking AICore unless the answer is final. */
    suspend fun status(): Status = lock.withLock { statusLocked() }

    private suspend fun statusLocked(): Status {
        known?.let { return it }
        val client = model ?: return Status.UNAVAILABLE.also { known = it }
        val status = try {
            when (client.checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNAVAILABLE.also { known = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AICore not finished setting itself up (606), not installed, not
            // bound (601): "not now", and asked again next time.
            Log.w(TAG, "Gemini Nano's status could not be read: $e")
            Status.UNAVAILABLE
        }
        if (status == Status.AVAILABLE) known = status
        Log.i(TAG, "Gemini Nano is $status")
        return status
    }

    /**
     * Ask AICore to fetch Gemini Nano for this app, and wait for it; where it
     * stands afterwards. AICore does the downloading and decides when and over
     * what network, which is why this is only ever asked for from a button
     * that says so. Not under the lock: it can take minutes, and nothing else
     * here can answer meanwhile anyway.
     */
    suspend fun download(): Status {
        val client = model ?: return Status.UNAVAILABLE
        try {
            client.download().collect { Log.i(TAG, "Gemini Nano download: $it") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano could not be downloaded: $e")
        }
        return status()
    }

    /**
     * [prompt] asked about [image], or about nothing but itself when there is
     * no image; the answer's text, or null when Gemini Nano is not here or
     * could not answer.
     *
     * A temperature of nothing, since every question asked here has one right
     * answer and a model encouraged to vary it would only be wrong more often,
     * and a short cap on the answer, since every answer asked for is a few
     * lines. No model preference is set: beta 4 of the library fails with one
     * set on the newest Gemini Nano, and the default is the right one anyway.
     */
    suspend fun ask(image: Bitmap?, prompt: String, maxTokens: Int): String? = lock.withLock {
        if (statusLocked() != Status.AVAILABLE) return@withLock null
        val client = model ?: return@withLock null
        try {
            val request = if (image != null) {
                generateContentRequest(ImagePart(image), TextPart(prompt)) {
                    temperature = 0f
                    maxOutputTokens = maxTokens
                }
            } else {
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0f
                    maxOutputTokens = maxTokens
                }
            }
            client.generateContent(request).candidates.firstOrNull()?.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Asked from the background, over a quota, or simply failed: in
            // every case the form is left as it was, and the log says why.
            Log.w(TAG, "Gemini Nano did not answer: $e")
            null
        }
    }
}

/** How big a picture Gemini Nano is shown; see pictureForNano. */
private const val NANO_PICTURE_SIZE = 768

/**
 * [photo] as Gemini Nano is shown it: at most [NANO_PICTURE_SIZE] on its
 * longer side, laid on white. Smaller because the model sees a few hundred
 * pixels whatever it is handed, and a full photo is only memory. On white
 * because a cut-out is mostly transparent, and transparency handed to a model
 * is black or undefined: a garment on white is the product photo it has seen
 * most, and a photo with no transparency is unchanged by it. [photo] is
 * recycled; the picture is new.
 */
internal fun pictureForNano(photo: Bitmap): Bitmap {
    val scale = minOf(1f, NANO_PICTURE_SIZE.toFloat() / maxOf(photo.width, photo.height))
    val width = (photo.width * scale).toInt().coerceAtLeast(1)
    val height = (photo.height * scale).toInt().coerceAtLeast(1)
    val picture = createBitmap(width, height)
    Canvas(picture).apply {
        drawColor(Color.WHITE)
        drawBitmap(photo, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    photo.recycle()
    return picture
}

