package com.wardrobapp.app

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.presentation.BackgroundEdit
import com.wardrobapp.presentation.GarmentDetailView
import com.wardrobapp.presentation.garmentDetail
import com.wardrobapp.presentation.withBackgroundRemovedAt
import com.wardrobapp.presentation.withBackgroundRestoredAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
        /** What the exception said, which is not translated and may be null. */
        val actionError: String? = null,
        /**
         * What the app was doing, for when the exception says nothing useful.
         *
         * A resource id rather than a sentence: the model has no Context, and the
         * screen is where the reader's language is known.
         */
        @StringRes val actionErrorFallback: Int? = null,
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
        _state.update { it.copy(actionError = null, actionErrorFallback = null) }
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
        _state.update {
            it.copy(confirming = null, working = true, actionError = null, actionErrorFallback = null)
        }

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
                        actionError = e.message,
                        actionErrorFallback = R.string.error_garment_not_deleted,
                    )
                }
            }
        }
    }

    // ---- the selected photo's background -----------------------------------

    /**
     * Cut the garment out of its background, and keep the result.
     *
     * Unlike the form, there is no save button here, so this writes as it goes.
     * What the slots become is decided by [withBackgroundRemovedAt] rather than
     * here -- the alignment and what becomes discardable are the parts that are
     * quietly wrong when they are wrong, and they are tested where they live.
     */
    fun onRemoveBackground() {
        val loaded = record ?: return
        if (_state.value.working) return

        val original = loaded.displayImageUris.getOrNull(selectedIndex) ?: return
        if (original.isEmpty()) return

        _state.update { it.copy(working = true, actionError = null, actionErrorFallback = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cutout = resolveImageRef(
                        container.backgrounds.removeBackground(
                            Uri.parse(original),
                            UUID.randomUUID().toString(),
                        ),
                        container.imageDirectory,
                    )

                    val edit = withBackgroundRemovedAt(
                        images = loaded.displayImageUris,
                        cutouts = loaded.displayNoBgImageUris,
                        index = selectedIndex,
                        cutout = cutout,
                    ) ?: return@withContext

                    applyPhotos(edit, alsoImages = true)
                }
                _state.update { it.copy(working = false) }
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        actionError = e.message,
                        actionErrorFallback = R.string.error_background_not_removed,
                    )
                }
            }
        }
    }

    /**
     * Put the original photo back.
     *
     * Only offered where there is an original to go back to, which is the same
     * condition [withBackgroundRestoredAt] enforces -- so a stale tap on a slot
     * that has since collapsed does nothing rather than something wrong.
     */
    fun onUndoBackground() {
        val loaded = record ?: return
        if (_state.value.working) return

        _state.update { it.copy(working = true, actionError = null, actionErrorFallback = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val edit = withBackgroundRestoredAt(
                        images = loaded.displayImageUris,
                        cutouts = loaded.displayNoBgImageUris,
                        index = selectedIndex,
                    ) ?: return@withContext

                    applyPhotos(edit, alsoImages = false)
                }
                _state.update { it.copy(working = false) }
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        working = false,
                        actionError = e.message,
                        actionErrorFallback = R.string.error_not_undone,
                    )
                }
            }
        }
    }

    /**
     * Write the new slots, then drop the file nothing points at.
     *
     * That order matters: the row is the record of what exists, so a failure
     * before it is written leaves both the old slots and their files intact. The
     * file is deleted only after the row has stopped referring to it.
     *
     * [alsoImages] is false for an undo, which changes only the cut-out column --
     * the image column already holds the original it is going back to.
     */
    private fun applyPhotos(edit: BackgroundEdit, alsoImages: Boolean) {
        container.garmentWrites.update(
            garmentId,
            GarmentWrites.GarmentEdit(
                imageUri = if (alsoImages) edit.images.firstOrNull() ?: "" else null,
                imageUris = if (alsoImages) edit.images else null,
                // Written as an empty string rather than NULL when a slot is
                // cleared; every reader treats the two the same.
                imageUriNoBg = edit.cutouts.firstOrNull() ?: "",
                imageUrisNoBg = edit.cutouts,
            ),
            isoTimestamp(System.currentTimeMillis()),
        )

        edit.discardable?.let { runCatching { container.photos.delete(it) } }
    }

    /** Run a write, then re-read: what the screen shows comes from the row. */
    private fun write(action: () -> Unit) {
        _state.update {
            it.copy(confirming = null, working = true, actionError = null, actionErrorFallback = null)
        }

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
