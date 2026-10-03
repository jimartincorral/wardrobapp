package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the outfits screen asks the engine for. */
@Serializable
data class SuggestionRequest(
    val filters: OutfitFilters,
    /**
     * What is on screen right now, as each outfit's garment ids, so a second tap
     * does not hand back the same three outfits. Read from the state rather than
     * kept in a field of its own: the batch on screen *is* the memory.
     */
    val alreadySeen: List<List<String>>,
    /** A garment every suggestion must be built around, or null for the whole wardrobe. */
    val seedGarmentId: String?,
    val count: Int,
)

/** The saved outfits, and how many are archived whether or not they are shown. */
@Serializable
data class SavedOutfits(val outfits: List<OutfitRecord>, val archivedCount: Long)

/** Where the outfits screen's suggestions and saved outfits come from, and where its changes go. */
interface OutfitsSource {
    suspend fun garment(id: String): GarmentRecord?

    /**
     * New suggestions, each with the id it will be saved under if it is kept.
     *
     * The source rolls the dice and reads the clock -- the season the wardrobe is
     * judged against when none is picked -- because both belong to the side that
     * holds the wardrobe; the engine only ever sees their answers.
     */
    suspend fun suggest(request: SuggestionRequest): List<Suggestion>

    suspend fun saved(includeArchived: Boolean): SavedOutfits

    /**
     * Save a suggestion to wear: stored if it is not yet, and un-archived if
     * rating it already stored it archived. Idempotent either way.
     */
    suspend fun keep(suggestion: Suggestion)

    /**
     * Rate a suggestion, which stores it archived first -- a rating is a rating
     * *of* an outfit, so the outfit has to exist -- and out of the way until
     * somebody asks to keep it.
     */
    suspend fun rate(suggestion: Suggestion, rating: Int)

    /** Bring a rated outfit out of the archive. */
    suspend fun unarchive(outfitId: String)

    suspend fun setPinned(outfitId: String, pinned: Boolean)

    /**
     * Delete an outfit and its ratings, together. The garments are untouched: an
     * outfit is a grouping of them, not a thing that owns them.
     */
    suspend fun delete(outfitId: String)
}

/** Suggestions and saved outfits: what OutfitsViewModel did, in common code. See ScreenModels.kt. */
class OutfitsScreenModel(
    private val scope: CoroutineScope,
    private val source: OutfitsSource,
) {
    private val _state = MutableStateFlow(OutfitsScreenState())
    val state: StateFlow<OutfitsScreenState> = _state.asStateFlow()

    init {
        loadSaved()
    }

    fun onSeasonTapped(season: Season?) {
        _state.update { it.copy(filters = it.filters.withSeasonToggled(season)) }
    }

    fun onOccasionTapped(occasion: Occasion?) {
        _state.update { it.copy(filters = it.filters.withOccasionSelected(occasion)) }
    }

    /**
     * Build every suggestion around one garment.
     *
     * The engine has supported this from the start and nothing ever asked it to:
     * "what goes with this?" is the question somebody holding a garment actually
     * has, and it was reachable only from a test.
     */
    fun onSeedRequested(garmentId: String) {
        scope.launch {
            attempt { source.garment(garmentId) }
                .onSuccess { garment ->
                    _state.update { it.copy(seed = garment) }
                    generate()
                }
                .onFailure { e -> showError(e) }
        }
    }

    /** Back to suggesting from the whole wardrobe. */
    fun onSeedCleared() {
        _state.update { it.copy(seed = null) }
        generate()
    }

    fun generate() {
        val request = _state.value.let { state ->
            SuggestionRequest(
                filters = state.filters,
                alreadySeen = state.suggestions.map { s -> s.outfit.garments.map { it.id } },
                seedGarmentId = state.seed?.id,
                count = SUGGESTION_COUNT,
            )
        }
        clearError()
        _state.update { it.copy(generating = true) }

        scope.launch {
            attempt { source.suggest(request) }
                .onSuccess { suggested ->
                    _state.update { it.copy(generating = false, hasGenerated = true, suggestions = suggested) }
                }
                .onFailure { e ->
                    _state.update { it.copy(generating = false, hasGenerated = true) }
                    showError(e)
                }
        }
    }

    fun onSaveRequested(suggestion: Suggestion) {
        scope.launch {
            // Marked saved only if it was: saying so after a failed write would
            // hide the outfit that is not there.
            if (write { source.keep(suggestion) }) markSaved(suggestion.id)
            loadSaved()
        }
    }

    /**
     * Record a rating, and ask whether the outfit is worth keeping.
     *
     * Rating used to save. That is the whole of what a rating could do, so the only
     * way to teach the engine anything was to put an outfit you had just called
     * two stars into the list of outfits you intend to wear -- which is a good
     * reason never to rate anything.
     *
     * So a rating archives instead: stored, learned from, and out of the way. The
     * prompt that follows offers to keep it. Written *before* the prompt rather
     * than in answer to it, deliberately: the rating is the part that must not be
     * lost, and a dialog dismissed by a stray tap or a rotation would lose it.
     */
    fun onRated(suggestion: Suggestion, rating: Int) {
        // Shown immediately: the stars are the user's own input and should not
        // wait on a write to appear.
        _state.update { current ->
            current.copy(
                suggestions = current.suggestions.map { if (it.id == suggestion.id) it.copy(rating = rating) else it },
            )
        }

        scope.launch {
            // Only asked once the rating is safely down, and only if it is: a
            // prompt offering to keep an outfit whose rating failed to write would
            // be offering to keep nothing.
            if (write { source.rate(suggestion, rating) }) {
                _state.update { it.copy(keeping = suggestion.copy(rating = rating)) }
            }
            loadSaved()
        }
    }

    /** Keep the rated outfit in the list of outfits to wear. */
    fun onKeepRequested() {
        val suggestion = _state.value.keeping ?: return
        _state.update { it.copy(keeping = null) }

        scope.launch {
            if (write { source.unarchive(suggestion.id) }) markSaved(suggestion.id)
            loadSaved()
        }
    }

    /**
     * Leave it archived.
     *
     * Nothing to write: rating already put it there. Dismissing the prompt any
     * other way means the same thing, which is why this is the safe default.
     */
    fun onKeepDismissed() = _state.update { it.copy(keeping = null) }

    /** Show or hide the outfits that were rated but not kept. */
    fun onArchivedToggled() {
        _state.update { it.copy(showingArchived = !it.showingArchived) }
        loadSaved()
    }

    fun onPinToggled(outfit: OutfitRecord) {
        scope.launch {
            write { source.setPinned(outfit.id, !outfit.isPinned) }
            loadSaved()
        }
    }

    fun refresh() = loadSaved()

    fun onDeleteRequested(outfit: OutfitRecord) {
        _state.update { it.copy(deleting = outfit) }
    }

    fun onDeleteDismissed() {
        _state.update { it.copy(deleting = null) }
    }

    /** Delete the outfit being asked about. */
    fun onDeleteConfirmed() {
        val outfit = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }

        scope.launch {
            write { source.delete(outfit.id) }
            loadSaved()
        }
    }

    private fun markSaved(id: String) {
        _state.update { current ->
            current.copy(suggestions = current.suggestions.map { if (it.id == id) it.copy(saved = true) else it })
        }
    }

    /**
     * Whether the error on screen is a failure to read the saved outfits, which a
     * read that then succeeds has answered.
     *
     * Every write is followed by a re-read, and the re-read used to clear any
     * error at all on success -- so a write that failed set its error and the
     * read right behind it wiped it out, and a failed save, rating, pin or delete
     * was never seen. A write's error now stays until the next action starts.
     */
    private var errorFromLoad = false

    private fun loadSaved() {
        scope.launch {
            val showArchived = _state.value.showingArchived
            attempt { source.saved(includeArchived = showArchived) }
                .onSuccess { saved ->
                    val clear = errorFromLoad
                    errorFromLoad = false
                    _state.update {
                        it.copy(
                            saved = saved.outfits,
                            archivedCount = saved.archivedCount,
                            error = if (clear) null else it.error,
                        )
                    }
                }
                // Reported, not swallowed: the React Native screen logged this and
                // left the list at its previous value, so a failed read was
                // indistinguishable from having saved nothing.
                .onFailure { e -> showError(e, fromLoad = true) }
        }
    }

    private fun showError(e: Throwable, fromLoad: Boolean = false) {
        errorFromLoad = fromLoad
        _state.update { it.copy(error = e.readableMessage()) }
    }

    /** A new action: whatever went wrong before it has been superseded. */
    private fun clearError() {
        errorFromLoad = false
        _state.update { it.copy(error = null) }
    }

    /**
     * Run a write, surfacing a failure rather than logging it. Returns whether it
     * got through, so callers do not report a change that did not happen.
     */
    private suspend fun write(block: suspend () -> Unit): Boolean {
        clearError()
        return attempt { block() }.onFailure { e -> showError(e) }.isSuccess
    }

    private companion object {
        const val SUGGESTION_COUNT = 3
    }
}
