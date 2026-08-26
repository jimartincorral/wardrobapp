package com.wardrobapp.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.presentation.RatingSummary
import com.wardrobapp.presentation.ratingSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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

    data class State(
        val loading: Boolean = true,
        val outfit: OutfitRecord? = null,
        /**
         * The garments in it, in the order the outfit lists them.
         *
         * One that has since been deleted is simply absent: `GarmentWrites.delete`
         * drops a garment from every outfit it belongs to, so this only happens
         * for a row that predates that or came from a restored backup.
         */
        val garments: List<GarmentRecord> = emptyList(),
        /**
         * What the rating adds up to.
         *
         * Over at most one rating, because rating an outfit replaces any previous
         * one. So this is really "the rating", with the clamping
         * and the star rounding that a value from a restored backup needs.
         */
        val rating: RatingSummary = ratingSummary(emptyList()),
        /** Set when the outfit is not there -- deleted, or a link to nothing. */
        val missing: Boolean = false,
        /** What the exception said, which is not translated and may be null. */
        val error: String? = null,
        /**
         * What the app was doing when it failed, for when the exception says
         * nothing useful -- which is the case this used to cover with an English
         * sentence written into the model.
         *
         * A resource id rather than a string because the model has no Context and
         * should not acquire one for this: an id is a number until a screen looks
         * it up, and the screen is where the reader's language is known.
         */
        @StringRes val errorFallback: Int? = null,
        val working: Boolean = false,
        /** A card being composed into a file, which takes a moment. */
        val composingCard: Boolean = false,
        /**
         * The written card, waiting to be handed to a share sheet.
         *
         * Held rather than acted on because starting an activity is the screen's
         * business, not a model's -- and cleared as soon as the screen has done it,
         * so coming back to this outfit does not re-open the sheet.
         */
        val cardToShare: String? = null,
        val confirmingDelete: Boolean = false,
        /** Set once it is gone, so the screen showing it can leave. */
        val deleted: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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
                        errorFallback = R.string.error_rating_not_saved,
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
                        errorFallback = R.string.error_outfit_not_deleted,
                    )
                }
            }
        }
    }

    /**
     * Compose this outfit into an image and hand back where it was written.
     *
     * The drawing and the writing are both slow enough to matter -- several photos
     * decoded and a 1080-wide canvas encoded -- so both happen off the main
     * thread, and the screen shows that it is working meanwhile.
     *
     * The background colour is handed in because it belongs to the theme the app
     * is drawing with, which a model cannot see: a card shared out of the dark
     * theme should not arrive on a light ground.
     */
    fun onShareRequested(background: Int) {
        if (_state.value.composingCard) return

        val garments = _state.value.garments
        if (garments.isEmpty()) return

        _state.update { it.copy(composingCard = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    container.cards.write(garments, background)
                }

                _state.update {
                    it.copy(
                        composingCard = false,
                        // Null when no garment in the outfit belongs anywhere on a
                        // card. Nothing to share, and nothing worth an error either.
                        cardToShare = written,
                        errorFallback = if (written == null) R.string.error_card_empty else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        composingCard = false,
                        error = e.message,
                        errorFallback = R.string.error_card_not_written,
                    )
                }
            }
        }
    }

    /** The sheet has been offered; the address has served its purpose. */
    fun onShared() = _state.update { it.copy(cardToShare = null) }
}
