package com.wardrobapp.server

import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.StyleIndexer
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.data.isoTimestamp
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

    /** The pass itself is :data's, which the phone runs too; this side brings the photos and the thread. */
    private val indexer = StyleIndexer(
        style = style,
        garments = garments,
        garmentWrites = garmentWrites,
        model = encoder.model,
        anchors = encoder.anchors,
        embedPhoto = { photo ->
            photos.file(photo)?.let { file ->
                try {
                    encoder.embed(file.readBytes())
                } catch (e: Exception) {
                    // Said in the log, since a photo that never embeds is
                    // otherwise silent; the pass leaves it for the next time.
                    println("Could not embed $photo: ${e.message}")
                    null
                }
            }
        },
        now = ::now,
    )

    private fun pass(): Int = indexer.pass()

    private fun now() = isoTimestamp(System.currentTimeMillis())

    override fun close() {
        worker.shutdownNow()
    }
}
