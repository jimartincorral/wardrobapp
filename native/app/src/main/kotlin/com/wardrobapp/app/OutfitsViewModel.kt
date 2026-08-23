package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun generate() {
        val filters = _state.value.filters
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
            if (runWrite { save(suggestion) }) markSaved(suggestion.id)
            loadSaved()
        }
    }

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
                save(suggestion)
                container.outfitWrites.rate(
                    ratingId = UUID.randomUUID().toString(),
                    outfitId = suggestion.id,
                    rating = rating,
                    now = now(),
                )
            }
            if (written) markSaved(suggestion.id)
            loadSaved()
        }
    }

    fun onPinToggled(outfit: OutfitRecord) {
        viewModelScope.launch {
            runWrite { container.outfitWrites.setPinned(outfit.id, !outfit.isPinned) }
            loadSaved()
        }
    }

    fun refresh() = loadSaved()

    private fun save(suggestion: Suggestion) {
        container.outfitWrites.insertIfAbsent(
            id = suggestion.id,
            name = suggestion.outfit.name,
            garmentIds = suggestion.outfit.garments.map { it.id },
            isSuggested = true,
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
                val outfits = withContext(Dispatchers.IO) { container.outfits.all() }
                _state.update { it.copy(saved = outfits, error = null) }
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
