package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentDetailScreenState.Confirm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the garment screen's garment comes from, and where its changes go. */
interface GarmentDetailSource {
    /** Null when there is no such garment -- deleted, or a link to nothing. */
    suspend fun garment(id: String): GarmentRecord?

    /** Retire it, or put it back in use. */
    suspend fun setInUse(id: String, inUse: Boolean)

    /**
     * Delete the garment, keeping its photos and what it took with it for a
     * while, so [undoDelete] can put it back.
     *
     * The write is atomic and says which files the garment referred to; they
     * are not deleted here but when [discardDeleted] says the chance to undo
     * has passed. See RecentlyDeleted in :data for the whole of this.
     */
    suspend fun delete(id: String)

    /**
     * Put a deleted garment back, photos and all; false when it is too late
     * -- the window closed, the process restarted -- and nothing was done.
     * A source with no memory of deletes answers false, which is what the
     * default does.
     */
    suspend fun undoDelete(id: String): Boolean = false

    /** The chance to undo has passed: delete the files the garment's delete kept. */
    suspend fun discardDeleted(id: String) {}

    /**
     * Cut the garment in [photo] out of its background, store the result, and
     * say where it is -- in the same form the record's own photo slots use.
     */
    suspend fun cutOut(photo: String): String

    /**
     * Write the garment's new photo slots, then drop the file nothing points at.
     *
     * That order matters: the row is the record of what exists, so a failure
     * before it is written leaves both the old slots and their files intact.
     * [alsoImages] is false for an undo, which changes only the cut-outs -- the
     * images already hold the original being gone back to.
     */
    suspend fun savePhotos(id: String, edit: BackgroundEdit, alsoImages: Boolean)
}

/** One garment: what GarmentDetailViewModel did, in common code. See ScreenModels.kt. */
class GarmentDetailScreenModel(
    private val scope: CoroutineScope,
    private val source: GarmentDetailSource,
    private val garmentId: String,
    /** Where a delete is offered back; null where there is nothing to draw the offer, and a delete is final. */
    private val undo: UndoHost? = null,
) {
    private val _state = MutableStateFlow(GarmentDetailScreenState(garmentId = garmentId))
    val state: StateFlow<GarmentDetailScreenState> = _state.asStateFlow()

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

        scope.launch {
            attempt { source.garment(garmentId) }
                .onSuccess { loaded ->
                    record = loaded
                    _state.update {
                        if (loaded == null) {
                            it.copy(loading = false, view = null, missing = true)
                        } else {
                            it.copy(loading = false, view = garmentDetail(loaded, selectedIndex), missing = false)
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }

    fun onPhotoSelected(index: Int) {
        selectedIndex = index
        // Recomputed, not re-read: the row has not changed, only which of its
        // photos is being looked at. No reading, and no second opinion about what
        // that photo shows.
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
            Confirm.RETIRE -> write { source.setInUse(garmentId, inUse = false) }
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
        write { source.setInUse(garmentId, inUse = true) }
    }

    private fun delete() {
        _state.update {
            it.copy(confirming = null, working = true, actionError = null, actionErrorFallback = null)
        }

        scope.launch {
            attempt { source.delete(garmentId) }
                .onSuccess {
                    _state.update { it.copy(working = false, deleted = true) }
                    // Offered after the screen is told, so the line appears
                    // over the screen this one closes into, not under a
                    // screen about to go.
                    undo?.offer(
                        Deleted.GARMENT,
                        undo = { source.undoDelete(garmentId) },
                        expire = { source.discardDeleted(garmentId) },
                    )
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(working = false, actionError = e.message, actionErrorFallback = ErrorFallback.GARMENT_NOT_DELETED)
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
     *
     * The slot is the one selected when the button was pressed, held for the
     * whole job. The ViewModel this came from read the selection again once the
     * cut-out came back, and cutting out takes long enough to tap another photo:
     * the cut-out of one photo then landed in another's slot.
     */
    fun onRemoveBackground() {
        val loaded = record ?: return
        if (_state.value.working) return

        val index = selectedIndex
        val original = loaded.displayImageUris.getOrNull(index) ?: return
        if (original.isEmpty()) return

        _state.update { it.copy(working = true, actionError = null, actionErrorFallback = null) }

        scope.launch {
            attempt {
                val cutout = source.cutOut(original)
                val edit = withBackgroundRemovedAt(
                    images = loaded.displayImageUris,
                    cutouts = loaded.displayNoBgImageUris,
                    index = index,
                    cutout = cutout,
                )
                if (edit != null) source.savePhotos(garmentId, edit, alsoImages = true)
            }
                .onSuccess {
                    _state.update { it.copy(working = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(working = false, actionError = e.message, actionErrorFallback = ErrorFallback.BACKGROUND_NOT_REMOVED)
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

        val edit = withBackgroundRestoredAt(
            images = loaded.displayImageUris,
            cutouts = loaded.displayNoBgImageUris,
            index = selectedIndex,
        )

        _state.update { it.copy(working = true, actionError = null, actionErrorFallback = null) }

        scope.launch {
            attempt { if (edit != null) source.savePhotos(garmentId, edit, alsoImages = false) }
                .onSuccess {
                    _state.update { it.copy(working = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(working = false, actionError = e.message, actionErrorFallback = ErrorFallback.NOT_UNDONE)
                    }
                }
        }
    }

    /** Run a write, then re-read: what the screen shows comes from the row. */
    private fun write(action: suspend () -> Unit) {
        _state.update {
            it.copy(confirming = null, working = true, actionError = null, actionErrorFallback = null)
        }

        scope.launch {
            attempt { action() }
                .onSuccess {
                    _state.update { it.copy(working = false) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(working = false, actionError = e.readableMessage()) } }
        }
    }
}
