package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.isoTimestamp
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Each screen's source, over a wardrobe database this process holds itself.
 *
 * The phone's, today, and the Home Assistant server's, later: both are JVMs
 * holding the same schema, so both answer a screen the same way by going through
 * the same queries. The browser has no database of its own and will implement
 * these interfaces over HTTP instead -- against a server that answers with these.
 *
 * The queries block, so every source does its work on [io] -- Dispatchers.IO
 * where these run -- which is the `withContext(Dispatchers.IO)` each ViewModel
 * used to write for itself.
 *
 * Anything a write invents is invented here, not in the screen model: a new
 * row's id and the moment it was written are the storing side's to decide, and
 * in the browser that will be the server.
 */

/** A new row's id, as every write in this app has always made one. */
internal fun newRowId(): String = UUID.randomUUID().toString()

/** Now, in the one timestamp shape the rows hold. */
internal fun nowTimestamp(): String = isoTimestamp(System.currentTimeMillis())

class DatabaseHomeSource(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
    private val io: CoroutineDispatcher,
) : HomeSource {
    override suspend fun counts(): HomeCounts = withContext(io) {
        HomeCounts(
            items = garments.availableCount(),
            archived = garments.unavailableCount(),
            rated = outfits.ratedCount(),
        )
    }
}

class DatabaseOutfitDetailSource(
    private val outfits: OutfitQueries,
    private val outfitWrites: OutfitWrites,
    private val garments: GarmentQueries,
    private val io: CoroutineDispatcher,
) : OutfitDetailSource {
    override suspend fun outfit(id: String): OutfitDetailContent? = withContext(io) {
        val outfit = outfits.outfit(id) ?: return@withContext null
        OutfitDetailContent(
            outfit = outfit,
            garments = outfit.garmentIds.mapNotNull { garments.garment(it) },
            rating = outfits.rating(id)?.rating,
        )
    }

    override suspend fun rate(outfitId: String, rating: Int) {
        withContext(io) {
            outfitWrites.rate(ratingId = newRowId(), outfitId = outfitId, rating = rating, now = nowTimestamp())
        }
    }

    override suspend fun delete(outfitId: String) {
        withContext(io) { outfitWrites.delete(outfitId) }
    }
}

class DatabaseOutfitEditSource(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
    private val outfitWrites: OutfitWrites,
    private val io: CoroutineDispatcher,
) : OutfitEditSource {
    // The default filters are available-only, which is what this wants.
    override suspend fun wardrobe() = withContext(io) { garments.allGarments() }

    override suspend fun outfit(id: String): OutfitRecord? = withContext(io) { outfits.outfit(id) }

    override suspend fun create(draft: OutfitDraft) {
        withContext(io) {
            outfitWrites.insert(
                id = newRowId(),
                name = draft.name,
                garmentIds = draft.garmentIds,
                occasion = draft.occasion?.id,
                season = draft.season?.tag,
                isSuggested = false,
                now = nowTimestamp(),
            )
        }
    }

    override suspend fun update(id: String, draft: OutfitDraft) {
        withContext(io) {
            outfitWrites.update(
                id = id,
                name = draft.name,
                garmentIds = draft.garmentIds,
                occasion = draft.occasion?.id,
                season = draft.season?.tag,
            )
        }
    }
}

class DatabaseWardrobeSource(
    private val garments: GarmentQueries,
    private val io: CoroutineDispatcher,
) : WardrobeSource {
    override suspend fun garments(query: WardrobeQuery) = withContext(io) {
        // The database applies what it can express; :presentation applies the
        // rest and the ordering.
        garments
            .allGarments(
                GarmentQueries.Filters(
                    category = query.category,
                    // Null would mean available-only. Only asked for when the list
                    // is showing retired garments too.
                    availableOnly = if (query.includeRetired) false else null,
                    search = query.searchTerm,
                ),
            )
            .filterBy(query.garmentFilter())
            .orderedBy(query.sort)
    }
}
