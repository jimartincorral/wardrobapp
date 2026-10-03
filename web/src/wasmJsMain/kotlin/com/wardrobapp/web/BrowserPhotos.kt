package com.wardrobapp.web

import com.wardrobapp.api.HttpPhotos
import com.wardrobapp.data.PHOTO_JPEG_QUALITY
import com.wardrobapp.data.storedPhotoSize
import com.wardrobapp.presentation.PhotoWork
import com.wardrobapp.presentation.dominantGarmentColors
import io.ktor.http.ContentType
import kotlin.coroutines.resume
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8ClampedArray
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

/**
 * The browser's photo work, over the server's photo store.
 *
 * The same steps as the phone's, done with what a page has. A picked photo is
 * decoded by the browser, turned upright by it -- browsers apply a JPEG's
 * orientation when they draw one -- scaled to the size the phone stores
 * ([storedPhotoSize]) and encoded as a JPEG at the phone's quality, then
 * uploaded. So a photo added here is the same shape on the server's disk as one
 * added on the phone, which is what keeps a wardrobe moved between them the same
 * wardrobe, and it is the browser rather than the server that does the work,
 * which is where the phone does it too: on the device that picked the photo.
 *
 * Colours are read the phone's way: the photo drawn as a 64-pixel-wide
 * thumbnail and handed to :presentation's dominantGarmentColors.
 *
 * Removing a background is not here; see PhotoTools.
 */
class BrowserPhotoWork(private val photos: HttpPhotos) : PhotoWork<File> {

    override suspend fun store(photo: File): String {
        val image = decode(photo)
        val size = storedPhotoSize(image.naturalWidth, image.naturalHeight)
        val canvas = canvasOf(size.width, size.height)
        canvas.context().drawImage(image, 0.0, 0.0, size.width.toDouble(), size.height.toDouble())
        val jpeg = bytesOf(canvas.encode("image/jpeg", PHOTO_JPEG_QUALITY / 100.0))
        return photos.upload(jpeg, ContentType.Image.JPEG)
    }

    override suspend fun delete(photo: String) = photos.delete(photo)

    override suspend fun colors(photo: String): List<String>? = try {
        val image = load(photo)
        val width = minOf(COLOR_SAMPLE_WIDTH, image.naturalWidth).coerceAtLeast(1)
        val height = (image.naturalHeight * width / image.naturalWidth.coerceAtLeast(1)).coerceAtLeast(1)
        val canvas = canvasOf(width, height)
        val context = canvas.context()
        context.drawImage(image, 0.0, 0.0, width.toDouble(), height.toDouble())
        dominantGarmentColors(context.getImageData(0.0, 0.0, width.toDouble(), height.toDouble()).data.toByteArray())
    } catch (_: Exception) {
        // As on the phone: a colour that could not be read is not worth stopping for.
        null
    }

    override suspend fun cutOut(photo: String): String =
        throw UnsupportedOperationException("Removing a background is not available in the browser yet.")

    private companion object {
        /** The phone's COLOR_SAMPLE_WIDTH, for the reason given there. */
        const val COLOR_SAMPLE_WIDTH = 64
    }
}

/**
 * Ask the reader for photos with the browser's own file picker; what they chose,
 * or nothing if they closed it.
 *
 * [capture] asks a phone's browser for its camera instead of its gallery, which
 * is what the form's "take a photo" means there; a desktop browser ignores it and
 * shows the picker.
 */
suspend fun pickPhotos(multiple: Boolean, capture: Boolean = false): List<File> =
    suspendCancellableCoroutine { continuation ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/*"
        input.multiple = multiple
        if (capture) input.setAttribute("capture", "environment")

        input.onchange = {
            val files = input.files
            val chosen = if (files == null) emptyList() else (0 until files.length).mapNotNull { files[it] }
            if (continuation.isActive) continuation.resume(chosen)
            null
        }
        // Closing the picker without choosing. Not every browser says so; one
        // that does not leaves this waiting until the screen is left, which
        // costs nothing.
        input.addEventListener("cancel", {
            if (continuation.isActive) continuation.resume(emptyList())
        })
        input.click()
    }

/** A picked file, decoded into an image the page can draw. */
private suspend fun decode(file: File): HTMLImageElement {
    val url = URL.createObjectURL(file)
    try {
        return load(url)
    } finally {
        URL.revokeObjectURL(url)
    }
}

/** An image loaded from [source], which may be a stored photo's reference. */
private suspend fun load(source: String): HTMLImageElement = suspendCancellableCoroutine { continuation ->
    val image = document.createElement("img") as HTMLImageElement
    image.onload = {
        if (continuation.isActive) continuation.resume(image)
        null
    }
    image.onerror = { _, _, _, _, _ ->
        if (continuation.isActive) continuation.cancel(IllegalStateException("That photo could not be read."))
        null
    }
    image.src = source
}

private fun canvasOf(width: Int, height: Int): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = width
    canvas.height = height
    return canvas
}

private fun HTMLCanvasElement.context(): CanvasRenderingContext2D = getContext("2d") as CanvasRenderingContext2D

private suspend fun HTMLCanvasElement.encode(type: String, quality: Double): Blob = suspendCancellableCoroutine { continuation ->
    toBlob({ blob ->
        if (blob != null) continuation.resume(blob)
        else continuation.cancel(IllegalStateException("That photo could not be encoded."))
    }, type, quality.toJsNumber())
}

private suspend fun bytesOf(blob: Blob): ByteArray = suspendCancellableCoroutine { continuation ->
    val reader = FileReader()
    reader.onload = {
        val buffer = reader.result as ArrayBuffer
        val view = Int8Array(buffer)
        continuation.resume(ByteArray(view.length) { view[it] })
        null
    }
    reader.onerror = {
        continuation.cancel(IllegalStateException("That photo could not be read."))
        null
    }
    reader.readAsArrayBuffer(blob)
}

private fun Uint8ClampedArray.toByteArray(): ByteArray = ByteArray(length) { this[it] }
