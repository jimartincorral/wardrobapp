package com.wardrobapp.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.domain.GARMENT_READING_PROMPT
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.parseGarmentReading
import com.wardrobapp.presentation.PhotoWork
import com.wardrobapp.presentation.dominantGarmentColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's photo work: the photo store for files and colours, ML Kit for
 * cutting garments out, and Gemini Nano or the style model for reading what a
 * photo shows, each off the main thread as the ViewModels ran them.
 *
 * A stored photo's reference is resolved against the image directory, which is
 * the form a garment row's photo slots hold and the form every screen draws.
 */
class PhonePhotoWork(private val container: AppContainer) : PhotoWork<Uri> {

    override suspend fun store(photo: Uri): String = withContext(Dispatchers.IO) {
        resolveImageRef(container.photos.store(photo, UUID.randomUUID().toString()), container.imageDirectory)
    }

    override suspend fun delete(photo: String) {
        withContext(Dispatchers.IO) { container.photos.delete(photo) }
    }

    override suspend fun colors(photo: String): List<String>? = withContext(Dispatchers.IO) {
        container.photos.pixelsFor(photo.toUri(), COLOR_SAMPLE_WIDTH)?.let { dominantGarmentColors(it) }
    }

    override suspend fun cutOut(photo: String): String = withContext(Dispatchers.IO) {
        resolveImageRef(
            container.backgrounds.removeBackground(photo.toUri(), UUID.randomUUID().toString()),
            container.imageDirectory,
        )
    }

    /**
     * Gemini Nano's reading where the phone has it, with whatever it left open
     * filled in by the style model where that is downloaded -- and the style
     * model's alone on a phone without Nano. Nano goes first because it reads
     * more: a material, a brand on a label, and seventy types told apart by a
     * model that has seen clothes described rather than one placing a picture
     * near a sentence. The style model is then asked only for what is still
     * missing, which on a phone with Nano is usually nothing.
     */
    override suspend fun read(photo: String): GarmentReading? = withContext(Dispatchers.IO) {
        val byNano = readWithNano(photo)
        if (byNano != null && byNano.type != null && !byNano.attributes.isEmpty) return@withContext byNano
        val byModel = container.styleEncoder.anchors?.let { anchors ->
            container.styleEncoder.embed(toStoredImageRef(photo))?.let(anchors::readingFor)
        }
        (byNano ?: GarmentReading.NONE).orElse(byModel).takeUnless { it.isEmpty }
    }

    private suspend fun readWithNano(photo: String): GarmentReading? {
        val nano = container.geminiNano
        if (!nano.isAvailable()) return null
        val picture = container.photos.bitmapFor(photo.toUri())?.let(::onWhite) ?: return null
        return nano.ask(picture, GARMENT_READING_PROMPT, READING_TOKENS)?.let(::parseGarmentReading)
    }

    /**
     * [photo] at most [NANO_PICTURE_SIZE] on its longer side, laid on white.
     *
     * Smaller because the model sees a few hundred pixels whatever it is
     * handed, and a full photo is only memory. On white because a cut-out is
     * mostly transparent, and transparency handed to a model is black or
     * undefined: a garment on white is the product photo it has seen most.
     */
    private fun onWhite(photo: Bitmap): Bitmap {
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
}

/** How big a picture Gemini Nano is shown; see PhonePhotoWork.onWhite. */
private const val NANO_PICTURE_SIZE = 768

/** Room for the seven short lines GARMENT_READING_PROMPT asks for, and not for an essay. */
private const val READING_TOKENS = 80

/**
 * How wide a photo is decoded to before its colour is read.
 *
 * The same 64 pixels `detectDominantColor` resized to on the other side. The
 * exact number is not what matters -- two decoders never see identical pixels --
 * but reading a thumbnail rather than a photograph is, because it is what makes
 * the answer about the garment rather than about its weave.
 *
 * One number for both screens that add garments, which is why it lives here:
 * two would mean the same photo being given two answers depending on which
 * screen added it.
 */
internal const val COLOR_SAMPLE_WIDTH = 64
