package com.wardrobapp.app

import android.util.Log
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.StyleIndexer
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.data.isoTimestamp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/**
 * Keeping the phone's vectors up to date, and its garments' style filled in.
 *
 * The pass is :data's StyleIndexer, the one the Home Assistant app runs: a
 * garment whose photo has no vector gets one, a garment nobody has set a
 * style on gets the one its photo suggests, and every look gets a vector.
 * What is the phone's own is when it runs and where: asked for after
 * anything that could leave a photo without a vector -- the app coming to
 * the front, a sync that brought something, a look added, the garment form
 * closing -- and done in the app's scope, one pass at a time, so a pass is
 * never cut short by the screen that asked for it going away.
 *
 * A request while one is waiting is the same request; one while a pass is
 * running is one more pass after it, since the running one may have listed
 * the garments before the new one was saved. A pass with nothing to do is
 * two queries, so asking often is cheap, and spares every save path from
 * having to know about the model.
 *
 * The first pass on a phone that has just updated embeds the whole wardrobe,
 * a fraction of a second a photo, in the background. Suggestions made
 * meanwhile simply have fewer vectors to go on.
 */
class PhoneStyleIndex(
    private val scope: CoroutineScope,
    private val encoder: AndroidStyleEncoder,
    private val style: StyleQueries,
    private val garments: GarmentQueries,
    private val garmentWrites: GarmentWrites,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val oneAtATime = Dispatchers.IO.limitedParallelism(1)

    private val pending = AtomicBoolean(false)

    /** Embed whatever lacks a vector, soon, in the background. */
    fun refresh() {
        if (!pending.compareAndSet(false, true)) return
        scope.launch(oneAtATime) {
            pending.set(false)
            val anchors = encoder.anchors ?: return@launch
            try {
                val embedded = StyleIndexer(
                    style = style,
                    garments = garments,
                    garmentWrites = garmentWrites,
                    model = anchors.model,
                    anchors = anchors,
                    embedPhoto = encoder::embed,
                    now = { isoTimestamp(System.currentTimeMillis()) },
                ).pass()
                if (embedded > 0) Log.i("StyleIndex", "Embedded $embedded photos")
            } catch (e: Exception) {
                Log.w("StyleIndex", "Embedding the wardrobe's photos failed: $e")
            }
        }
    }
}
