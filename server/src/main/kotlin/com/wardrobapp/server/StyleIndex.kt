package com.wardrobapp.server

import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.splitStructuredTags
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeping one wardrobe's vectors up to date, and its attributes filled in.
 *
 * Asked to [refresh] after anything that could leave a garment or a look
 * without a vector -- a save, a photo change, a sync, a look added -- and it
 * does the work later, on a thread of its own, one wardrobe at a time:
 * embedding takes a second or two a photo and a bulk add of thirty would
 * otherwise hold the request that saved them for a minute. A refresh asked
 * for while one is pending is the same refresh; one asked for while one is
 * running is a second run after it, since the running one may have listed
 * the garments before the new one was saved.
 *
 * A garment with no attributes of its own gets the ones the model reads off
 * its photo, written as the tags GarmentAttributes describes and stamped as
 * an edit, so the sync carries them to the phone. One somebody has set
 * anything on is left entirely alone: a person's word beats the model's,
 * and half a guess beside a choice would read as the person's.
 *
 * Nothing here is remembered across a restart but the tables: a server that
 * stopped partway picks up where the vectors end.
 */
class StyleIndex(
    private val encoder: StyleEncoder,
    private val photos: PhotoFiles,
    private val style: StyleQueries,
    private val garments: GarmentQueries,
    private val garmentWrites: GarmentWrites,
) : AutoCloseable {

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "style-index").apply { isDaemon = true }
    }
    private val pending = AtomicBoolean(false)

    /** Embed whatever lacks a vector, soon, on the index's thread. */
    fun refresh() {
        if (!pending.compareAndSet(false, true)) return
        worker.execute {
            pending.set(false)
            try {
                pass()
            } catch (e: Exception) {
                println("Embedding the wardrobe's photos failed: ${e.message}")
            }
        }
    }

    /**
     * Embed whatever lacks a vector and wait for it; how many photos this
     * pass embedded. On the index's own thread, behind any pass already
     * queued, so two passes never embed the same photo at once; for the
     * tests, and for anything that wants the vectors before going on.
     */
    fun refreshNow(): Int = try {
        worker.submit(Callable { pass() }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun pass(): Int {
        var embedded = 0
        val computed = style.embeddedPhotos()
        for (garment in garments.allGarments(GarmentQueries.Filters(availableOnly = false))) {
            val photo = toStoredImageRef(garment.displayImage)
            if (photo.isEmpty() || computed[garment.id] == photo) continue
            val file = photos.file(photo) ?: continue
            val vector = try {
                encoder.embed(file.readBytes())
            } catch (e: Exception) {
                // A photo the model cannot read is left for the next time; a
                // vector of nothing would be worse than none. Said in the
                // log, since a photo that never embeds is otherwise silent.
                println("Could not embed $photo for garment ${garment.id}: ${e.message}")
                continue
            }
            style.putEmbedding(garment.id, photo, encoder.model, vector, now())
            embedded++

            val (customTags, seasons, set) = splitStructuredTags(garment.tags)
            if (set.isEmpty) {
                val read = encoder.anchors.attributesFor(vector)
                if (!read.isEmpty) {
                    garmentWrites.update(
                        garment.id,
                        GarmentWrites.GarmentEdit(tags = mergeStructuredTags(customTags, seasons, read)),
                        now = now(),
                    )
                }
            }
        }
        for (look in style.inspirationsToEmbed()) {
            val file = photos.file(toStoredImageRef(look.imageUri)) ?: continue
            val vector = try {
                encoder.embed(file.readBytes())
            } catch (e: Exception) {
                println("Could not embed look ${look.id}: ${e.message}")
                continue
            }
            style.putInspirationVector(look.id, encoder.model, vector)
            embedded++
        }
        return embedded
    }

    private fun now() = isoTimestamp(System.currentTimeMillis())

    override fun close() {
        worker.shutdownNow()
    }
}
