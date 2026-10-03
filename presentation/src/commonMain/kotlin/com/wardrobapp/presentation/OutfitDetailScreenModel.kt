package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** One saved outfit, with the garments it holds and its rating if it has one. */
@Serializable
data class OutfitDetailContent(
    val outfit: OutfitRecord,
    /**
     * In the order the outfit lists them, without any that no longer exist --
     * see OutfitDetailScreenState.garments for when that happens.
     */
    val garments: List<GarmentRecord>,
    /** The stars given, or null if it has never been rated. */
    val rating: Int?,
)

/** Where the outfit screen's outfit comes from, and where its changes go. */
interface OutfitDetailSource {
    /** Null when there is no such outfit -- deleted, or a link to nothing. */
    suspend fun outfit(id: String): OutfitDetailContent?

    /**
     * Rate it, which also teaches the app which garments go together.
     *
     * The rating folds into the learned pair scores, undoing the previous
     * rating's contribution first -- which is why it is a write of its own
     * rather than a field on the outfit.
     */
    suspend fun rate(outfitId: String, rating: Int)

    /** Delete it. The garments in it are untouched. */
    suspend fun delete(outfitId: String)
}

/**
 * One saved outfit: what OutfitDetailViewModel did, in common code. See
 * ScreenModels.kt.
 *
 * Reachable at all since saved outfits became openable: they could be pinned and
 * rated from the list, but not opened, so the queries behind this were written
 * and unused.
 */
class OutfitDetailScreenModel(
    private val scope: CoroutineScope,
    private val source: OutfitDetailSource,
    private val outfitId: String,
) {
    private val _state = MutableStateFlow(OutfitDetailScreenState())
    val state: StateFlow<OutfitDetailScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null, errorFallback = null) }

        scope.launch {
            attempt { source.outfit(outfitId) }
                .onSuccess { loaded ->
                    _state.update {
                        if (loaded == null) {
                            it.copy(loading = false, outfit = null, missing = true)
                        } else {
                            it.copy(
                                loading = false,
                                outfit = loaded.outfit,
                                garments = loaded.garments,
                                rating = ratingSummary(listOfNotNull(loaded.rating)),
                                missing = false,
                            )
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }

    fun onRated(rating: Int) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null, errorFallback = null) }

        scope.launch {
            attempt { source.rate(outfitId, rating) }
                .onSuccess {
                    _state.update { it.copy(working = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(working = false, error = e.message, errorFallback = ErrorFallback.RATING_NOT_SAVED)
                    }
                }
        }
    }

    fun onDeleteRequested() {
        _state.update { it.copy(confirmingDelete = true) }
    }

    fun onDeleteDismissed() {
        _state.update { it.copy(confirmingDelete = false) }
    }

    fun onDeleteConfirmed() {
        _state.update {
            it.copy(confirmingDelete = false, working = true, error = null, errorFallback = null)
        }

        scope.launch {
            attempt { source.delete(outfitId) }
                .onSuccess { _state.update { it.copy(working = false, deleted = true) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(working = false, error = e.message, errorFallback = ErrorFallback.OUTFIT_NOT_DELETED)
                    }
                }
        }
    }
}
