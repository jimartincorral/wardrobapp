package com.wardrobapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A training session: rounds of outfits, rated one at a time. See
 * TasteTraining.kt for why it exists and why it is built on [OutfitsSource].
 *
 * A round is one request for [TRAINING_ROUND_SIZE] outfits, exploring (see
 * SuggestionRequest.explore), with everything shown this session as already
 * seen, so the second round carries on from the first rather than starting
 * over. One request rather than one per outfit: the engine ranks a batch
 * against itself, and ten asked for one at a time would each be the best of
 * their own batch, which is the opposite of breadth.
 *
 * Each rating is written as it is given, through the same write the outfits
 * tab makes -- the suggestion is stored, archived, and rated -- so a session
 * abandoned at six has still taught six outfits' worth. The card advances
 * only once the write has succeeded; a write that fails leaves the card where
 * it is with the error under it, the rule bulk add follows for a failed save,
 * since advancing past something that was not recorded would tell the reader
 * it was. There is no "keep?" prompt after each rating, as the tab has: ten
 * prompts in a row would be the session. The card's bookmark keeps an outfit
 * instead, without moving on.
 */
class TasteTrainingScreenModel(
    private val scope: CoroutineScope,
    private val source: OutfitsSource,
) {
    private val _state = MutableStateFlow(TasteTrainingScreenState())
    val state: StateFlow<TasteTrainingScreenState> = _state.asStateFlow()

    init {
        startRound()
    }

    /** Rate the outfit on screen, and move on once it is recorded. */
    fun onRated(rating: Int) {
        val state = _state.value
        val current = state.current ?: return
        if (state.writing) return

        // Shown at once: the stars are the reader's own input and should not
        // wait on a write to appear.
        _state.update { it.copy(writing = true, error = null, round = it.round.withRating(current.id, rating)) }

        scope.launch {
            attempt { source.rate(current, rating) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            writing = false,
                            position = it.position + 1,
                            rated = it.rated + 1,
                            liked = it.liked + if (rating >= LIKED_FROM) 1 else 0,
                            disliked = it.disliked + if (rating <= DISLIKED_TO) 1 else 0,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(writing = false, error = e.readableMessage(), round = it.round.withRating(current.id, null))
                    }
                }
        }
    }

    /** Pass on the outfit on screen. Nothing is written: a skip teaches nothing, by design. */
    fun onSkipped() {
        val state = _state.value
        if (state.current == null || state.writing) return
        _state.update { it.copy(position = it.position + 1, skipped = it.skipped + 1, error = null) }
    }

    /** Keep the outfit on screen as a saved outfit, and stay on it. */
    fun onSaveRequested() {
        val current = _state.value.current ?: return
        if (current.saved) return

        scope.launch {
            attempt { source.keep(current) }
                .onSuccess { _state.update { it.copy(round = it.round.map { s -> if (s.id == current.id) s.copy(saved = true) else s }) } }
                .onFailure { e -> _state.update { it.copy(error = e.readableMessage()) } }
        }
    }

    /** Another round, past everything shown so far. */
    fun onKeepGoing() {
        if (_state.value.loading) return
        startRound()
    }

    /** Ask again after a round could not be fetched. */
    fun onRetry() {
        if (_state.value.loading) return
        startRound()
    }

    private fun startRound() {
        val request = SuggestionRequest(
            filters = OutfitFilters(),
            alreadySeen = _state.value.shown,
            seedGarmentId = null,
            count = TRAINING_ROUND_SIZE,
            explore = true,
        )
        _state.update { it.copy(loading = true, error = null, round = emptyList(), position = 0) }

        scope.launch {
            attempt { source.suggest(request) }
                .onSuccess { round ->
                    _state.update {
                        it.copy(
                            loading = false,
                            hasFetched = true,
                            round = round,
                            position = 0,
                            shown = it.shown + round.map { s -> s.outfit.garments.map { g -> g.id } },
                            // Nothing more after a round that had something: the
                            // wardrobe is spent, and Keep going would come back
                            // empty too.
                            exhausted = round.isEmpty() && it.shown.isNotEmpty(),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, hasFetched = true, error = e.readableMessage()) } }
        }
    }

    private fun List<OutfitsScreenState.Suggestion>.withRating(id: String, rating: Int?) =
        map { if (it.id == id) it.copy(rating = rating) else it }

    companion object {
        /** A rating this high counts as liked in the round's summary. */
        const val LIKED_FROM = 4

        /** A rating this low counts as not for them. Three is neither, as the learning treats it. */
        const val DISLIKED_TO = 2
    }
}
