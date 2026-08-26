package com.wardrobapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.CARD_ASPECT_HEIGHT
import com.wardrobapp.presentation.CARD_ASPECT_WIDTH
import com.wardrobapp.presentation.CardGarment
import com.wardrobapp.presentation.CardPlacement
import com.wardrobapp.presentation.outfitCardLayout
import java.io.File
import java.io.IOException

/**
 * The outfit card as a file, for sharing.
 *
 * Drawn from the same [outfitCardLayout] the on-screen card uses, so what gets
 * shared is a picture of the card rather than a second arrangement that happens
 * to hold the same clothes. Drawn onto an Android canvas rather than captured
 * from the composition, for two reasons: the size is fixed, so the file does not
 * depend on the phone that made it, and none of it needs the main thread.
 *
 * Everything here is blocking. Callers run it off the main thread.
 */

/**
 * How wide the shared image is.
 *
 * Wide enough to look sharp full-screen on a phone and in a chat app, small
 * enough that six decoded garment photos and the canvas all fit in memory at
 * once on a cheap device.
 */
private const val CARD_WIDTH = 1080

/** Derived from the shared ratio, so the file cannot be a different shape from the card. */
private const val CARD_HEIGHT = CARD_WIDTH * CARD_ASPECT_HEIGHT / CARD_ASPECT_WIDTH

/** Where shared cards are written. Cleared on the way in: one card at a time. */
private const val CARD_DIRNAME = "cards"

private const val CARD_FILENAME = "outfit.jpg"

/** JPEG quality for the shared image, matching what stored photos are written at. */
private const val CARD_QUALITY = 90

/**
 * Compose the card into a bitmap.
 *
 * A garment whose photo cannot be decoded leaves its share of the card empty
 * rather than failing the whole image: one missing file out of five is a gap, not
 * a reason to refuse to draw anything. Returns null only when there was nothing
 * to draw at all.
 */
internal fun outfitCardBitmap(
    photos: AndroidPhotoStore,
    garments: List<CardGarment>,
    background: Int,
): Bitmap? {
    val placements = outfitCardLayout(garments)
    if (placements.isEmpty()) return null

    val card = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(card)
    canvas.drawColor(background)

    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    for (placement in placements) {
        val target = placement.toRect()
        if (target.isEmpty) continue

        val photo = try {
            photos.bitmapFor(placement.garment.imageUri.toUri(), target.width())
        } catch (_: Exception) {
            null
        } ?: continue

        canvas.drawBitmap(photo, photo.centreCropTo(target), target, paint)
        photo.recycle()
    }

    return card
}

/**
 * Write the card and hand back an address other apps may read.
 *
 * One file, overwritten: a share sheet holds the address only until the receiving
 * app has read it, and keeping a card per outfit would grow the cache forever for
 * files nobody looks at twice.
 */
internal fun writeOutfitCard(context: Context, card: Bitmap): Uri {
    val directory = File(context.cacheDir, CARD_DIRNAME).also { it.mkdirs() }
    val file = File(directory, CARD_FILENAME)
    val staging = File(directory, "$CARD_FILENAME.incoming")

    try {
        staging.outputStream().use { out ->
            if (!card.compress(Bitmap.CompressFormat.JPEG, CARD_QUALITY, out)) {
                throw IOException(context.getString(R.string.error_image_unsaveable))
            }
        }
        // Renamed into place so a share cannot pick up a half-written file, which
        // is the same reason stored photos are written this way.
        if (!staging.renameTo(file)) {
            throw IOException(context.getString(R.string.error_image_unsaveable))
        }
    } finally {
        staging.delete()
    }

    return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
}

/** The placement, in the pixels of the image being drawn. */
private fun CardPlacement.toRect() = Rect(
    (x * CARD_WIDTH).toInt(),
    (y * CARD_HEIGHT).toInt(),
    ((x + width) * CARD_WIDTH).toInt(),
    ((y + height) * CARD_HEIGHT).toInt(),
)

/**
 * The part of the photo to draw, so it fills the target without stretching.
 *
 * The centre of the largest rectangle of the photo that has the target's shape --
 * which is what `ContentScale.Crop` does on screen, and the card has to agree
 * with itself.
 */
private fun Bitmap.centreCropTo(target: Rect): Rect {
    val wanted = target.width().toDouble() / target.height()
    val mine = width.toDouble() / height

    return if (mine > wanted) {
        // Wider than the target: trim the sides.
        val keep = (height * wanted).toInt()
        val inset = (width - keep) / 2
        Rect(inset, 0, inset + keep, height)
    } else {
        // Taller than the target: trim top and bottom.
        val keep = (width / wanted).toInt()
        val inset = (height - keep) / 2
        Rect(0, inset, width, inset + keep)
    }
}

/**
 * Composing an outfit into a shareable image.
 *
 * An interface so the outfit screen's model can ask for one without holding a
 * Context, and so a test can hand it something that writes nothing.
 */
fun interface OutfitCards {
    /**
     * Write the card, returning where it went, or null when the outfit has nothing
     * that belongs on a card.
     */
    fun write(garments: List<GarmentRecord>, background: Int): String?
}

/** The real one: draw, encode, and hand back an address other apps may read. */
class AndroidOutfitCards(
    private val context: Context,
    /** The same store the rest of the app reads photos through. */
    private val photos: AndroidPhotoStore,
) : OutfitCards {

    override fun write(garments: List<GarmentRecord>, background: Int): String? {
        val card = outfitCardBitmap(photos, garments.map { it.asCardGarment() }, background)
            ?: return null

        return try {
            writeOutfitCard(context, card).toString()
        } finally {
            // The file is the deliverable; the bitmap was the means, and it is a
            // 1080x1440 one.
            card.recycle()
        }
    }
}
