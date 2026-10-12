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
 * never asks AICore to fetch the model: a phone that has Gemini Nano has it for
 * its own features already, and one that would have to download it for a field
 * on a form is better served by the style model the app downloads itself.
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
     * Whether the model can answer on this phone; null until asked. Asked once
     * per process, since whether a phone has Gemini Nano does not change
     * while the app is open -- and when it does, a restart finds it.
     */
    private var available: Boolean? = null

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

    /** Whether Gemini Nano can answer here, asking AICore the first time. */
    suspend fun isAvailable(): Boolean = lock.withLock { availableLocked() }

    private suspend fun availableLocked(): Boolean {
        available?.let { return it }
        val client = model ?: return false.also { available = false }
        val answer = try {
            client.checkStatus() == FeatureStatus.AVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AICore not finished setting itself up (606), not installed, not
            // bound (601): all of them mean "not now", and not now is no.
            Log.w(TAG, "Gemini Nano's status could not be read: $e")
            false
        }
        available = answer
        return answer
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
        if (!availableLocked()) return@withLock null
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

