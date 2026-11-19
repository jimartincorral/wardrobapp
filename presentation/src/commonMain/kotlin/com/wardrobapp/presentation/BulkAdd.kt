package com.wardrobapp.presentation

import com.wardrobapp.domain.GarmentReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Adding a wardrobe, rather than a garment.
 *
 * Cataloguing clothes already owned is many garments in a row, each starting
 * from a photo already taken. So bulk add is a queue of photos, and each photo
 * in turn opens the ordinary garment form -- the whole of it, the same one adding
 * a single garment uses -- with that photo already in it. See BulkAddScreenModel.
 *
 * This is the queue alone: the photos still waiting, and counters over what has
 * happened. The photo being filled in is not in it. It is the form's from the
 * moment its form opens, and can be cropped, cut out, removed or joined by a
 * second photo there, none of which the queue has any business keeping track of.
 * [total], [added] and [skipped] are counters rather than positions into
 * anything, which is what makes "3 of 12" and "12 garments added" answerable
 * after the queue has drained.
 *
 * It was once a list of drafts, each with a category, a type, a brand and
 * colours: a second, smaller garment form, with the first one's rules restated
 * for it. Every field the real form gained after that was a field bulk add did
 * not have, which is how it came to ask for three things where the form asks
 * for eleven -- so there is no second form any more, only the queue in front
 * of the first.
 */
data class BulkAddState(
    /** Stored photos waiting their turn, in the order they were picked. Not the one on screen. */
    val waiting: List<String> = emptyList(),
    /** How many photos have ever entered the queue, the one on screen and those dealt with included. */
    val total: Int = 0,
    /** How many were saved as garments. */
    val added: Int = 0,
    /** How many were thrown away rather than saved. */
    val skipped: Int = 0,
) {
    /** How many photos have been dealt with, one way or the other. */
    val done: Int get() = added + skipped

    /** Which photo is on screen, counting from one, for "3 of 12". */
    val position: Int get() = done + 1

    /**
     * Whether the queue has been worked through.
     *
     * False before any photo has been picked, so an untouched screen does not
     * report a finished job it never started.
     */
    val isFinished: Boolean get() = total > 0 && done >= total

    /**
     * Take on more photos.
     *
     * Appended rather than replacing, so a second helping of photos joins the
     * queue instead of discarding what is left of the first.
     */
    fun withPhotosAdded(photos: List<String>): BulkAddState = copy(
        waiting = waiting + photos,
        total = total + photos.size,
    )

    /**
     * The next photo to open a form on, and the queue without it; null when
     * nothing is waiting.
     */
    fun next(): Pair<String, BulkAddState>? =
        waiting.firstOrNull()?.let { photo -> photo to copy(waiting = waiting.drop(1)) }

    /** The garment on screen was saved. */
    fun advanced(): BulkAddState = copy(added = added + 1)

    /** The garment on screen was thrown away. */
    fun skipped(): BulkAddState = copy(skipped = skipped + 1)

    companion object {
        /**
         * How many photos one pass may take on.
         *
         * Each one is stored, decoded and read as it arrives, so a hundred at
         * once is a long wait with nothing to show. A limit that is generous for
         * the job -- a wardrobe is catalogued a drawer at a time -- and small
         * enough to stay responsive.
         */
        const val MAX_PHOTOS = 30
    }
}

/**
 * Photo work that reads each photo once, however often it is asked, and can be
 * asked before anybody needs the answer.
 *
 * Bulk add's. Every photo is read -- its colours, and what it is -- the moment
 * it lands in the queue, so that by the time its form opens the category is
 * already filled in rather than changing under the reader's thumb a few seconds
 * later. A reading on Gemini Nano takes seconds, and over a drawerful those are
 * the seconds this saves. The form, which knows nothing of it, asks for the same
 * photo when it opens and is handed the reading already under way, or already
 * finished, rather than starting a second one.
 *
 * Never fails. A photo that cannot be read reads as null, which is all the form
 * makes of a failure anyway; and a Deferred that failed would take the screen's
 * scope down with it, every form on it included.
 *
 * Not thread-safe, and need not be: like every screen model's own bookkeeping,
 * it is only touched from the screen's scope, which runs on one thread.
 */
internal class ReadAhead<Picked>(
    private val photos: PhotoWork<Picked>,
    private val scope: CoroutineScope,
) : PhotoWork<Picked> by photos {

    private val colors = mutableMapOf<String, Deferred<List<String>?>>()
    private val readings = mutableMapOf<String, Deferred<GarmentReading?>>()

    /** Start reading [photo] now, for whoever asks later. */
    fun readAhead(photo: String) {
        colorsOf(photo)
        readingOf(photo)
    }

    override suspend fun colors(photo: String): List<String>? = colorsOf(photo).await()

    override suspend fun read(photo: String): GarmentReading? = readingOf(photo).await()

    private fun colorsOf(photo: String) = colors.getOrPut(photo) {
        scope.async { attempt { photos.colors(photo) }.getOrNull() }
    }

    private fun readingOf(photo: String) = readings.getOrPut(photo) {
        scope.async { attempt { photos.read(photo) }.getOrNull() }
    }
}
