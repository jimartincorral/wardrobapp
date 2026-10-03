package com.wardrobapp.app

import android.net.Uri
import androidx.core.net.toUri
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.presentation.PhotoWork
import com.wardrobapp.presentation.dominantGarmentColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's photo work: the photo store for files and colours, and ML Kit for
 * cutting garments out, each off the main thread as the ViewModels ran them.
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
}

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
