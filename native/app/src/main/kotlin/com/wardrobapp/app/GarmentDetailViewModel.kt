package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentDetailView
import com.wardrobapp.presentation.garmentDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One garment's detail.
 *
 * Decides nothing about what is shown: :presentation turns the record into a
 * [GarmentDetailView], and this holds which photo is selected and keeps the read
 * off the main thread.
 */
class GarmentDetailViewModel(
    private val container: AppContainer,
    private val garmentId: String,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val view: GarmentDetailView? = null,
        /**
         * Set when the garment is not in the wardrobe -- deleted, or a link to
         * one that never existed. Distinct from an error: there is nothing to
         * retry.
         */
        val missing: Boolean = false,
        /** Set when the read failed, which is not the same as finding nothing. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The record, and which of its photos is selected.
     *
     * Both are kept so that selecting a photo can go back through
     * [garmentDetail] rather than editing the view it produced. Recomputing is a
     * pure call over data already in memory; patching the view would mean this
     * class deciding what a photo shows, which is the one thing it is not
     * supposed to know.
     *
     * The index survives a reload on purpose: removing a background reloads the
     * garment, and jumping back to the first photo would lose the reader's place.
     */
    private var record: GarmentRecord? = null
    private var selectedIndex = 0

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { container.garments.garment(garmentId) }
                record = loaded
                _state.update {
                    if (loaded == null) {
                        it.copy(loading = false, view = null, missing = true)
                    } else {
                        it.copy(
                            loading = false,
                            view = garmentDetail(loaded, selectedIndex),
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

    fun onPhotoSelected(index: Int) {
        selectedIndex = index
        // Recomputed, not re-read: the row has not changed, only which of its
        // photos is being looked at. No database work, and no second opinion
        // about what that photo shows.
        val loaded = record ?: return
        _state.update { it.copy(view = garmentDetail(loaded, index)) }
    }
}
