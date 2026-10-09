package com.wardrobapp.presentation

import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.Duplicates
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.Gaps
import com.wardrobapp.data.Suggestions
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.RecentlyDeleted
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.orphanedImageRefs
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GenerateSuggestionsOptions
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.PageFetcher
import com.wardrobapp.domain.importGarmentFromUrl
import com.wardrobapp.domain.safeImportUrl
import com.wardrobapp.domain.SuggestionPreferences
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.seasonOfMonth
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random
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
    /** Where a delete waits to be undone; the wardrobe's, shared with every source that deletes from it. */
    private val recentlyDeleted: RecentlyDeleted = RecentlyDeleted(),
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
        withContext(io) { outfitWrites.remove(outfitId, nowTimestamp())?.let(recentlyDeleted::put) }
    }

    override suspend fun undoDelete(outfitId: String): Boolean = withContext(io) {
        val deleted = recentlyDeleted.takeOutfit(outfitId) ?: return@withContext false
        outfitWrites.restore(deleted, nowTimestamp())
        true
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
                now = nowTimestamp(),
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

class DatabaseStatisticsSource(
    private val garments: GarmentQueries,
    private val analytics: AnalyticsQueries,
    private val duplicates: Duplicates,
    private val gaps: Gaps,
    /** Where photos live, which the lifespan rows resolve their images against. */
    private val imageDirectory: String,
    private val io: CoroutineDispatcher,
) : StatisticsSource {
    override suspend fun counts() = withContext(io) {
        StatisticsCounts(
            inUse = garments.availableCount(),
            retired = garments.unavailableCount(),
            categories = analytics.byCategory().asDistributions(),
            colors = analytics.byColor().asDistributions(),
            brands = analytics.byBrand().asDistributions(),
            subcategories = analytics.bySubcategory().mapValues { (_, subs) -> subs.asDistributions() },
            lifespans = analytics.lifespans(imageDirectory).map { lifespan ->
                LifespanEntry(
                    garmentId = lifespan.garment.id,
                    category = lifespan.garment.category,
                    subcategories = lifespan.garment.effectiveSubcategories,
                    days = lifespan.days,
                )
            },
        )
    }

    override suspend fun duplicates() = withContext(io) { duplicates.groups() }

    override suspend fun gaps() = withContext(io) {
        // The clock is read here, on the side that holds the wardrobe; the
        // analysis only sees the season.
        gaps.analyze(currentSeason = seasonOfMonth(Calendar.getInstance().get(Calendar.MONTH)))
    }

    /**
     * The queries call every key a `label`; the module calls a key a key -- these
     * distributions hold colours and brands as often as they hold categories.
     */
    private fun List<AnalyticsQueries.Count>.asDistributions(): List<Distribution> =
        map { Distribution(key = it.label, count = it.count) }
}

class DatabaseOutfitsSource(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
    private val outfitWrites: OutfitWrites,
    private val suggestions: Suggestions,
    private val io: CoroutineDispatcher,
    /** Where a delete waits to be undone; the wardrobe's, shared with every source that deletes from it. */
    private val recentlyDeleted: RecentlyDeleted = RecentlyDeleted(),
) : OutfitsSource {
    override suspend fun garment(id: String) = withContext(io) { garments.garment(id) }

    override suspend fun suggest(request: SuggestionRequest) = withContext(io) {
        suggestions.suggest(
            // The season the wardrobe is judged against when none is picked, and
            // the dice: read here because this is the side allowed a clock and a
            // random source. The engine only ever sees the answers.
            currentSeason = seasonOfMonth(Calendar.getInstance().get(Calendar.MONTH)),
            random = { Random.nextDouble() },
            options = GenerateSuggestionsOptions(
                count = request.count,
                preferences = SuggestionPreferences(
                    seasons = request.filters.seasons,
                    occasion = request.filters.occasion,
                ),
                alreadySeen = request.alreadySeen,
                explore = request.explore,
            ),
            seedGarmentId = request.seedGarmentId,
        ).map { Suggestion(id = newRowId(), outfit = it) }
    }

    override suspend fun saved(includeArchived: Boolean) = withContext(io) {
        SavedOutfits(outfits = outfits.all(includeArchived = includeArchived), archivedCount = outfits.archivedCount())
    }

    override suspend fun keep(suggestion: Suggestion) {
        withContext(io) {
            store(suggestion)
            // And un-archived, which is not the same as inserting it: rating this
            // suggestion already wrote the row, archived, so the insert above does
            // nothing and without this the outfit would stay hidden while the card
            // said "Saved". Idempotent either way.
            outfitWrites.setArchived(suggestion.id, false, nowTimestamp())
        }
    }

    override suspend fun rate(suggestion: Suggestion, rating: Int) {
        withContext(io) {
            // A rating is a rating *of* an outfit, so it has to exist first.
            store(suggestion, archived = true)
            outfitWrites.rate(ratingId = newRowId(), outfitId = suggestion.id, rating = rating, now = nowTimestamp())
        }
    }

    override suspend fun unarchive(outfitId: String) {
        withContext(io) { outfitWrites.setArchived(outfitId, false, nowTimestamp()) }
    }

    override suspend fun setPinned(outfitId: String, pinned: Boolean) {
        withContext(io) { outfitWrites.setPinned(outfitId, pinned, nowTimestamp()) }
    }

    override suspend fun delete(outfitId: String) {
        withContext(io) { outfitWrites.remove(outfitId, nowTimestamp())?.let(recentlyDeleted::put) }
    }

    override suspend fun undoDelete(outfitId: String): Boolean = withContext(io) {
        val deleted = recentlyDeleted.takeOutfit(outfitId) ?: return@withContext false
        outfitWrites.restore(deleted, nowTimestamp())
        true
    }

    private fun store(suggestion: Suggestion, archived: Boolean = false) {
        outfitWrites.insertIfAbsent(
            id = suggestion.id,
            name = suggestion.outfit.name,
            garmentIds = suggestion.outfit.garments.map { it.id },
            isSuggested = true,
            isArchived = archived,
            now = nowTimestamp(),
        )
    }
}

/**
 * The garment screen's source over a database, handed the two things that are
 * not the database's: removing a file, and cutting a photo out of its
 * background. On the phone those are the photo store and ML Kit; the server
 * will bring its own.
 */
class DatabaseGarmentDetailSource(
    private val garments: GarmentQueries,
    private val garmentWrites: GarmentWrites,
    /** Where photos live, which a fresh cut-out's reference is resolved against. */
    private val imageDirectory: String,
    /** Delete a stored photo by the name the rows hold. */
    private val deletePhoto: (String) -> Unit,
    /** Cut [photo] out of its background and store it under [id]; the stored name. */
    private val removeBackground: (photo: String, id: String) -> String,
    private val io: CoroutineDispatcher,
    /**
     * Where a delete waits to be undone. The wardrobe's, not this source's: on
     * the phone a source is made per screen, and the screen that deleted a
     * garment has closed by the time Undo is tapped from the one behind it.
     */
    private val recentlyDeleted: RecentlyDeleted = RecentlyDeleted(),
) : GarmentDetailSource {
    override suspend fun garment(id: String) = withContext(io) { garments.garment(id) }

    override suspend fun setInUse(id: String, inUse: Boolean) {
        withContext(io) {
            if (inUse) garmentWrites.markAvailable(id, nowTimestamp()) else garmentWrites.markUnavailable(id, nowTimestamp())
        }
    }

    override suspend fun delete(id: String) {
        withContext(io) {
            // The row goes now; the files wait for discardDeleted, or for an
            // older delete to push this one out of the memory of recent ones,
            // whose files are let go of here. A photo that fails to delete
            // does not fail the whole: the garment is gone either way.
            val deleted = garmentWrites.remove(id, nowTimestamp()) ?: return@withContext
            for (photo in recentlyDeleted.put(deleted)) runCatching { deletePhoto(photo) }
        }
    }

    override suspend fun undoDelete(id: String): Boolean = withContext(io) {
        val deleted = recentlyDeleted.takeGarment(id) ?: return@withContext false
        garmentWrites.restore(deleted, nowTimestamp())
        true
    }

    override suspend fun discardDeleted(id: String) {
        withContext(io) {
            for (photo in recentlyDeleted.discardGarment(id)) runCatching { deletePhoto(photo) }
        }
    }

    override suspend fun cutOut(photo: String) = withContext(io) {
        resolveImageRef(removeBackground(photo, newRowId()), imageDirectory)
    }

    override suspend fun savePhotos(id: String, edit: BackgroundEdit, alsoImages: Boolean) {
        withContext(io) {
            garmentWrites.update(
                id,
                GarmentWrites.GarmentEdit(
                    imageUri = if (alsoImages) edit.images.firstOrNull() ?: "" else null,
                    imageUris = if (alsoImages) edit.images else null,
                    // Written as an empty string rather than NULL when a slot is
                    // cleared; every reader treats the two the same.
                    imageUriNoBg = edit.cutouts.firstOrNull() ?: "",
                    imageUrisNoBg = edit.cutouts,
                ),
                nowTimestamp(),
            )
            edit.discardable?.let { runCatching { deletePhoto(it) } }
        }
    }
}

/**
 * Bulk add's garments, into a database. Handed a way to delete a photo, for the
 * same reason DatabaseGarmentDetailSource is: files are not the database's.
 */
class DatabaseBulkAddSource(
    private val garmentWrites: GarmentWrites,
    private val deletePhoto: (String) -> Unit,
    private val io: CoroutineDispatcher,
) : BulkAddSource {
    override suspend fun save(draft: BulkAddState.Draft) {
        withContext(io) {
            // A cut-out is stored in both columns and the original let go --
            // saving space is the whole point of removing a background, and
            // keeping both would mean every removal costing more storage rather
            // than less. The rule is the form's, delegated rather than restated.
            val images = draft.imagesToStore()

            garmentWrites.insert(
                GarmentWrites.NewGarment(
                    id = newRowId(),
                    imageUri = images.imageUris.first(),
                    imageUriNoBg = images.bgRemovedUris.firstOrNull()?.ifEmpty { null },
                    imageUris = images.imageUris,
                    imageUrisNoBg = images.bgRemovedUris,
                    category = draft.category,
                    subcategories = draft.subcategories,
                    // Seasons are stored as tags, the way the form stores them and
                    // the way every reader downstream expects to find them.
                    tags = mergeStructuredTags(emptyList(), draft.seasons),
                    brand = draft.brand.ifBlank { null },
                    colorPrimary = draft.colorPalette.first(),
                    colorSecondary = draft.colorPalette.getOrNull(1),
                    colorPalette = draft.colorPalette,
                    size = null,
                    now = nowTimestamp(),
                ),
            )

            // Only after the row is written: deleting sooner would break a
            // garment whose write then failed.
            for (orphan in images.discardable) deletePhoto(orphan)
        }
    }
}

/**
 * The garment form's source over a database. Handed a way to delete a photo,
 * for the same reason DatabaseGarmentDetailSource is: files are not the
 * database's.
 */
class DatabaseGarmentFormSource(
    private val garments: GarmentQueries,
    private val garmentWrites: GarmentWrites,
    private val duplicates: Duplicates,
    private val deletePhoto: (String) -> Unit,
    private val io: CoroutineDispatcher,
) : GarmentFormSource {
    override suspend fun garment(id: String) = withContext(io) { garments.garment(id) }

    override suspend fun brands() = withContext(io) { garments.brands() }

    override suspend fun duplicatesOf(candidate: DuplicateCandidate) = withContext(io) { duplicates.matching(candidate) }

    override suspend fun save(garmentId: String?, form: GarmentFormState, previouslyStored: List<String>) {
        withContext(io) {
            val now = nowTimestamp()
            val tags = mergeStructuredTags(form.tags, form.seasons)

            // A slot whose background was removed stores the cut-out in both
            // columns and lets the original go. Decided in GarmentFormState, and
            // shared with the React Native app, because both mistakes are silent
            // ones: discard a file still referenced and the garment shows a gap;
            // miss one and it sits on the device with nothing pointing at it.
            val images = form.imagesToStore()

            if (garmentId == null) {
                garmentWrites.insert(
                    GarmentWrites.NewGarment(
                        id = newRowId(),
                        imageUri = images.imageUris.first(),
                        imageUriNoBg = images.bgRemovedUris.firstOrNull()?.ifEmpty { null },
                        imageUris = images.imageUris,
                        imageUrisNoBg = images.bgRemovedUris,
                        category = form.category,
                        subcategories = form.subcategories,
                        tags = tags,
                        brand = form.brand.ifBlank { null },
                        colorPrimary = form.colorPalette.first(),
                        colorSecondary = form.colorPalette.getOrNull(1),
                        colorPalette = form.colorPalette,
                        size = form.size.ifBlank { null },
                        now = now,
                    ),
                )
            } else {
                garmentWrites.update(
                    garmentId,
                    GarmentWrites.GarmentEdit(
                        imageUri = images.imageUris.first(),
                        imageUriNoBg = images.bgRemovedUris.firstOrNull() ?: "",
                        imageUris = images.imageUris,
                        imageUrisNoBg = images.bgRemovedUris,
                        category = form.category,
                        subcategories = form.subcategories,
                        tags = tags,
                        brand = form.brand,
                        colorPrimary = form.colorPalette.first(),
                        colorSecondary = form.colorPalette.getOrNull(1) ?: "",
                        colorPalette = form.colorPalette,
                        size = form.size,
                    ),
                    now = now,
                )
            }

            // Only after the row is written, and all of it at once: the originals
            // this save collapsed away, plus anything the garment referenced before
            // and no longer does -- a photo removed from the form, or a cut-out
            // undone. Deleting any of it sooner would break a garment whose edit
            // was abandoned.
            val kept = images.imageUris + images.bgRemovedUris
            for (orphan in orphanedImageRefs(previouslyStored + images.discardable, kept)) {
                deletePhoto(orphan)
            }
        }
    }
}

/**
 * URL import on a JVM: the address checks in :domain and a page fetcher that
 * reaches only the addresses they allow -- :net's, on the phone and the server.
 *
 * [openPages] makes a fetcher per import because :domain judges a response's
 * headers before asking for its body and may never ask, so the connection has to
 * be closed by whoever opened it -- which is here, once the import is done.
 */
class FetchingGarmentImporter<P>(
    private val openPages: () -> P,
    /** Downloads a page's photos and stores them, the way a picked photo is. */
    private val images: ImageFetcher,
    private val io: CoroutineDispatcher,
) : GarmentImporter where P : PageFetcher, P : AutoCloseable {

    override fun check(url: String): String = safeImportUrl(url)

    override suspend fun import(url: String): ImportedGarmentPreview = withContext(io) {
        openPages().use { pages -> importGarmentFromUrl(url, pages, images) }
    }
}
