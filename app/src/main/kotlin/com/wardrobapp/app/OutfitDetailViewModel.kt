package com.wardrobapp.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.presentation.ErrorFallback
import com.wardrobapp.presentation.OutfitDetailScreenState
import com.wardrobapp.presentation.RatingSummary
import com.wardrobapp.presentation.ratingSummary
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One saved outfit.
 *
 * Reachable at all now: saved outfits could be pinned and rated from the list,
 * but not opened, so `OutfitQueries.outfit` and `OutfitQueries.rating` were both
 * written and unused.
 */
class OutfitDetailViewModel(
    private val container: AppContainer,
    private val outfitId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(OutfitDetailScreenState())
    val state: StateFlow<OutfitDetailScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val outfit = container.outfits.outfit(outfitId)
                        ?: return@withContext null

                    Triple(
                        outfit,
                        outfit.garmentIds.mapNotNull { container.garments.garment(it) },
                        container.outfits.rating(outfitId),
                    )
                }

                _state.update {
                    if (loaded == null) {
                        it.copy(loading = false, outfit = null, missing = true)
                    } else {
                        val (outfit, garments, rating) = loaded
                        it.copy(
                            loading = false,
                            outfit = outfit,
                            garments = garments,
                            rating = ratingSummary(listOfNotNull(rating?.rating)),
                            missing = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * Rate it, which also teaches the app which garments go together.
     *
     * `rate` folds the change into the learned pair scores, including undoing the
     * previous rating's contribution -- which is why it takes the outfit rather
     * than just the number.
     */
    fun onRated(rating: Int) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.outfitWrites.rate(
                        ratingId = UUID.randomUUID().toString(),
                        outfitId = outfitId,
                        rating = rating,
                        now = isoTimestamp(System.currentTimeMillis()),
                    )
                }
                _state.update { it.copy(working = false) }
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        error = e.message,
                        errorFallback = ErrorFallback.RATING_NOT_SAVED,
                    )
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

    /** Delete the outfit. The garments in it are untouched. */
    fun onDeleteConfirmed() {
        _state.update {
            it.copy(confirmingDelete = false, working = true, error = null, errorFallback = null)
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.outfitWrites.delete(outfitId) }
                _state.update { it.copy(working = false, deleted = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        error = e.message,
                        errorFallback = ErrorFallback.OUTFIT_NOT_DELETED,
                    )
                }
            }
        }
    }
}
