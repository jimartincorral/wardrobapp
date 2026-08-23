package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.isoTimestamp
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
        /** True while a write is in flight, so the actions cannot be double-tapped. */
        val working: Boolean = false,
        /** Non-null while a destructive action is being confirmed. */
        val confirming: Confirm? = null,
        /**
         * Set once the garment is gone, so the screen showing it can leave.
         *
         * Distinct from [missing], which means it was never found. This one says
         * *this* screen deleted it, which is the difference between "that garment
         * does not exist" and closing quietly on the wardrobe behind.
         */
        val deleted: Boolean = false,
        /** Set when an action failed. The read is fine; the write was not. */
        val actionError: String? = null,
    )

    /**
     * The two actions worth asking about first.
     *
     * Retiring is reversible and would not need a prompt on its own, but it is
     * what the React Native app asks about, and it does change what the wardrobe
     * shows. Deleting is not reversible at all.
     */
    enum class Confirm { RETIRE, DELETE }

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

    // ---- retiring, returning, deleting --------------------------------------

    fun onRetireRequested() {
        _state.update { it.copy(confirming = Confirm.RETIRE) }
    }

    fun onDeleteRequested() {
        _state.update { it.copy(confirming = Confirm.DELETE) }
    }

    fun onConfirmationDismissed() {
        _state.update { it.copy(confirming = null) }
    }

    fun onActionErrorDismissed() {
        _state.update { it.copy(actionError = null) }
    }

    /** Carry out whatever is being confirmed. */
    fun onConfirmed() {
        when (_state.value.confirming) {
            Confirm.RETIRE -> retire()
            Confirm.DELETE -> delete()
            null -> Unit
        }
    }

    /**
     * Put a retired garment back in use.
     *
     * No confirmation: it undoes something rather than doing something, and the
     * React Native app does not ask either.
     */
    fun onReturnedToWardrobe() {
        write { container.garmentWrites.markAvailable(garmentId, isoTimestamp(System.currentTimeMillis())) }
    }

    private fun retire() {
        write { container.garmentWrites.markUnavailable(garmentId, isoTimestamp(System.currentTimeMillis())) }
    }

    /**
     * Delete the garment, then its photos.
     *
     * In that order, and deliberately: the write is atomic and tells us which
     * files are now unreferenced, so a failure leaves the garment and its photos
     * both intact. Doing it the other way round would risk a row pointing at
     * files that are gone -- which is the state the whole restore path exists to
     * avoid.
     *
     * A file that fails to delete is not worth failing the action over. The
     * garment is gone either way, and what is left is a few bytes nothing
     * references -- while reporting failure would suggest the deletion had not
     * happened.
     */
    private fun delete() {
        _state.update { it.copy(confirming = null, working = true, actionError = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val photos = container.garmentWrites.delete(garmentId)
                    for (photo in photos) {
                        runCatching { container.photos.delete(photo) }
                    }
                }
                _state.update { it.copy(working = false, deleted = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        actionError = e.message ?: "That garment could not be deleted.",
                    )
                }
            }
        }
    }

    /** Run a write, then re-read: what the screen shows comes from the row. */
    private fun write(action: () -> Unit) {
        _state.update { it.copy(confirming = null, working = true, actionError = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                _state.update { it.copy(working = false) }
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        actionError = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }
}
