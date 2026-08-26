package com.wardrobapp.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.OutfitEditState
import com.wardrobapp.presentation.outfitEditStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Building an outfit by hand, and changing one.
 *
 * One model for both, because they are the same job with a different starting
 * point and a different write at the end -- the garment form is add-or-edit for
 * the same reason. The rules are in :presentation as transitions over
 * [OutfitEditState]; this loads the wardrobe to pick from and writes the row.
 */
class OutfitEditViewModel(
    private val container: AppContainer,
    /** Null when building a new outfit. */
    private val outfitId: String?,
) : ViewModel() {

    val isEditing = outfitId != null

    data class State(
        val edit: OutfitEditState = OutfitEditState(),
        /**
         * The garments that can be picked.
         *
         * Retired garments are left out: an outfit is something to wear, and
         * offering a garment marked unavailable would be offering to build an
         * outfit out of clothes that are gone.
         */
        val garments: List<GarmentRecord> = emptyList(),
        val loading: Boolean = true,
        val saving: Boolean = false,
        /** Set once the row is written, so the screen knows to leave. */
        val saved: Boolean = false,
        /** Set when the outfit being edited is not there. */
        val missing: Boolean = false,
        val error: String? = null,
        @StringRes val errorFallback: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                // The default filters are available-only, which is what this
                // wants: an outfit is something to wear, and offering a retired
                // garment would be offering to build one out of clothes that are
                // gone.
                val garments = withContext(Dispatchers.IO) { container.garments.allGarments() }

                val outfit = outfitId?.let {
                    withContext(Dispatchers.IO) { container.outfits.outfit(it) }
                }

                if (outfitId != null && outfit == null) {
                    _state.update { it.copy(loading = false, missing = true) }
                    return@launch
                }

                _state.update { state ->
                    state.copy(
                        loading = false,
                        garments = garments,
                        edit = outfit?.let {
                            outfitEditStateOf(
                                name = it.name,
                                garmentIds = it.garmentIds,
                                occasion = it.occasion,
                                season = it.season,
                            )
                        } ?: state.edit,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message,
                        errorFallback = R.string.error_wardrobe_unreadable,
                    )
                }
            }
        }
    }

    fun onNameChanged(name: String) = edit { it.withName(name) }

    fun onGarmentToggled(garmentId: String) = edit { it.withGarmentToggled(garmentId) }

    fun onOccasionTapped(occasion: Occasion) = edit { it.withOccasion(occasion) }

    fun onSeasonTapped(season: Season) = edit { it.withSeason(season) }

    /**
     * Write the outfit.
     *
     * An outfit with nothing in it is not saved rather than saved empty: an empty
     * outfit is a row nothing can draw and nothing can suggest from.
     */
    fun onSaveRequested() {
        val state = _state.value
        if (state.saving || !state.edit.canSave) return

        _state.update { it.copy(saving = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { write(state.edit, state.garments) }
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = e.message,
                        errorFallback = R.string.error_outfit_not_saved,
                    )
                }
            }
        }
    }

    fun onErrorDismissed() = _state.update { it.copy(error = null, errorFallback = null) }

    /**
     * Insert or update, which is the only place the two jobs differ.
     *
     * Internal so a test can call it with a real database: what a name falls back
     * to and what order the garments are stored in are decided in :presentation and
     * tested there, but that they reach a row is only true here.
     */
    internal fun write(edit: OutfitEditState, garments: List<GarmentRecord>) {
        val name = edit.nameFor(garments)

        if (outfitId == null) {
            container.outfitWrites.insert(
                id = UUID.randomUUID().toString(),
                name = name,
                garmentIds = edit.garmentIds,
                occasion = edit.occasion?.id,
                season = edit.season?.tag,
                // Built by hand, so not the engine's idea -- which is what the
                // statistics count when they count suggestions.
                isSuggested = false,
                now = isoTimestamp(System.currentTimeMillis()),
            )
        } else {
            container.outfitWrites.update(
                id = outfitId,
                name = name,
                garmentIds = edit.garmentIds,
                occasion = edit.occasion?.id,
                season = edit.season?.tag,
            )
        }
    }

    private fun edit(transform: (OutfitEditState) -> OutfitEditState) =
        _state.update { it.copy(edit = transform(it.edit)) }
}
