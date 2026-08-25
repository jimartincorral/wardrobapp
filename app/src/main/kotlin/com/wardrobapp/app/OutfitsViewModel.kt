package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.domain.GenerateSuggestionsOptions
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.SuggestionPreferences
import com.wardrobapp.domain.seasonOfMonth
import com.wardrobapp.presentation.OutfitFilters
import com.wardrobapp.presentation.withOccasionSelected
import com.wardrobapp.presentation.withSeasonToggled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random

/**
 * Outfit suggestions, and the ones that were kept.
 *
 * Decides nothing about scoring or about what the filter chips mean: the engine
 * is in :domain, the loading in :data, the chips in :presentation. What lives
 * here is the clock, the ids, and which of those results is on screen.
 */
class OutfitsViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * A suggestion with the id it will be saved under.
     *
     * Minted when the batch is produced rather than when it is saved, which is
     * what makes saving idempotent: tapping "save" and rating it -- which saves
     * it first -- are the same request, and the second one writes nothing.
     */
    data class Suggestion(
        val id: String,
        val outfit: SuggestedOutfit,
        /** The rating given in this session, if any. */
        val rating: Int? = null,
        val saved: Boolean = false,
    )

    data class State(
        val filters: OutfitFilters = OutfitFilters(),
        val suggestions: List<Suggestion> = emptyList(),
        val saved: List<OutfitRecord> = emptyList(),
        val generating: Boolean = false,
        /** True once a batch has been asked for, so "none" can differ from "not yet". */
        val hasGenerated: Boolean = false,
        val error: String? = null,
        /**
         * The saved outfit being asked about, if any.
         *
         * The outfit rather than its id, so the prompt can name it -- "delete
         * this?" next to a list of several is a question nobody should have to
         * answer from position alone.
         */
        val deleting: OutfitRecord? = null,
        /**
         * The garment every suggestion is being built around, if any.
         *
         * The record rather than its id, so the screen can name and show the
         * garment it is working from -- "building around something" is not an
         * answer anybody can act on.
         */
        val seed: GarmentRecord? = null,
        /**
         * The rated outfit being asked about, if any.
         *
         * A rating is already recorded and already learned from by the time this
         * appears -- what is being asked is only whether to keep the outfit in the
         * list of things to wear.
         */
        val keeping: Suggestion? = null,
        /** Whether the rated-only outfits are being shown alongside the kept ones. */
        val showingArchived: Boolean = false,
        /** How many are put away, so the toggle can say whether it is worth tapping. */
        val archivedCount: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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
        viewModelScope.launch {
            val garment = try {
                withContext(Dispatchers.IO) { container.garments.garment(garmentId) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                return@launch
            }

            _state.update { it.copy(seed = garment) }
            generate()
        }
    }

    /** Back to suggesting from the whole wardrobe. */
    fun onSeedCleared() {
        _state.update { it.copy(seed = null) }
        generate()
    }

    fun generate() {
        val filters = _state.value.filters
        val seed = _state.value.seed
        _state.update { it.copy(generating = true, error = null) }

        viewModelScope.launch {
            try {
                val suggested = withContext(Dispatchers.IO) {
                    container.suggestions.suggest(
                        // The season the wardrobe is judged against when none is
                        // picked. Read here because this is the layer allowed a
                        // clock; the engine only ever sees the answer.
                        currentSeason = seasonOfMonth(Calendar.getInstance().get(Calendar.MONTH)),
                        random = { Random.nextDouble() },
                        options = GenerateSuggestionsOptions(
                            count = SUGGESTION_COUNT,
                            preferences = SuggestionPreferences(
                                seasons = filters.seasons,
                                occasion = filters.occasion,
                            ),
                        ),
                        seedGarmentId = seed?.id,
                    )
                }

                _state.update { current ->
                    current.copy(
                        generating = false,
                        hasGenerated = true,
                        suggestions = suggested.map {
                            Suggestion(id = UUID.randomUUID().toString(), outfit = it)
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        generating = false,
                        hasGenerated = true,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun onSaveRequested(suggestion: Suggestion) {
        viewModelScope.launch {
            // Marked saved only if it was: saying so after a failed write would
            // hide the outfit that is not there.
            val written = runWrite {
                save(suggestion)
                // And un-archived, which is not the same as inserting it: rating
                // this suggestion already wrote the row, archived, so the insert
                // above does nothing and without this the outfit would stay hidden
                // while the card said "Saved". Idempotent either way.
                container.outfitWrites.setArchived(suggestion.id, false)
            }
            if (written) markSaved(suggestion.id)
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
                suggestions = current.suggestions.map {
                    if (it.id == suggestion.id) it.copy(rating = rating) else it
                }
            )
        }

        viewModelScope.launch {
            val written = runWrite {
                // A rating is a rating *of* an outfit, so it has to exist first.
                save(suggestion, archived = true)
                container.outfitWrites.rate(
                    ratingId = UUID.randomUUID().toString(),
                    outfitId = suggestion.id,
                    rating = rating,
                    now = now(),
                )
            }

            // Only asked once the rating is safely down, and only if it is: a
            // prompt offering to keep an outfit whose rating failed to write would
            // be offering to keep nothing.
            if (written) _state.update { it.copy(keeping = suggestion.copy(rating = rating)) }
            loadSaved()
        }
    }

    /** Keep the rated outfit in the list of outfits to wear. */
    fun onKeepRequested() {
        val suggestion = _state.value.keeping ?: return
        _state.update { it.copy(keeping = null) }

        viewModelScope.launch {
            if (runWrite { container.outfitWrites.setArchived(suggestion.id, false) }) {
                markSaved(suggestion.id)
            }
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
        viewModelScope.launch {
            runWrite { container.outfitWrites.setPinned(outfit.id, !outfit.isPinned) }
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

    /**
     * Delete the outfit being asked about.
     *
     * The garments are untouched: an outfit is a grouping of them, not a thing
     * that owns them. `OutfitWrites.delete` takes its ratings with it, in one
     * transaction, so a failure cannot leave ratings for an outfit that is gone.
     */
    fun onDeleteConfirmed() {
        val outfit = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }

        viewModelScope.launch {
            runWrite { container.outfitWrites.delete(outfit.id) }
            loadSaved()
        }
    }

    private fun save(suggestion: Suggestion, archived: Boolean = false) {
        container.outfitWrites.insertIfAbsent(
            id = suggestion.id,
            name = suggestion.outfit.name,
            garmentIds = suggestion.outfit.garments.map { it.id },
            isSuggested = true,
            isArchived = archived,
            now = now(),
        )
    }

    private fun markSaved(id: String) {
        _state.update { current ->
            current.copy(
                suggestions = current.suggestions.map {
                    if (it.id == id) it.copy(saved = true) else it
                }
            )
        }
    }

    private fun loadSaved() {
        viewModelScope.launch {
            try {
                val showArchived = _state.value.showingArchived
                val (outfits, archived) = withContext(Dispatchers.IO) {
                    container.outfits.all(includeArchived = showArchived) to
                        container.outfits.archivedCount()
                }
                _state.update { it.copy(saved = outfits, archivedCount = archived, error = null) }
            } catch (e: Exception) {
                // Reported, not swallowed: the React Native screen logged this
                // and left the list at its previous value, so a failed read was
                // indistinguishable from having saved nothing.
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * Run a write off the main thread, surfacing a failure rather than logging
     * it. Returns whether it got through, so callers do not report a change
     * that did not happen.
     */
    private suspend fun runWrite(block: () -> Unit): Boolean = try {
        withContext(Dispatchers.IO) { block() }
        true
    } catch (e: Exception) {
        _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        false
    }

    private fun now() = isoTimestamp(System.currentTimeMillis())

    private companion object {
        const val SUGGESTION_COUNT = 3
    }
}
