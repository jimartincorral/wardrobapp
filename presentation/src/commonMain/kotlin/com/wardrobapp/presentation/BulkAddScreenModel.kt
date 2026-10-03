package com.wardrobapp.presentation

import com.wardrobapp.domain.seasonsForSubcategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where bulk add's garments go. Its photos are PhotoWork's. */
fun interface BulkAddSource {
    /**
     * Store the draft as a garment, then delete the photos it no longer needs --
     * only after the row is written, since deleting sooner would break a garment
     * whose write then failed.
     */
    suspend fun save(draft: BulkAddState.Draft)
}

/**
 * Adding many garments from many photos: what BulkAddViewModel did, in common
 * code. See ScreenModels.kt, and PhotoWork for why it is generic over [Picked].
 */
class BulkAddScreenModel<Picked>(
    private val scope: CoroutineScope,
    private val photos: PhotoWork<Picked>,
    private val source: BulkAddSource,
) {
    private val _state = MutableStateFlow(BulkAddScreenState())
    val state: StateFlow<BulkAddScreenState> = _state.asStateFlow()

    /**
     * Store the photos and queue them up.
     *
     * Stored one at a time rather than in parallel: each one decodes a full-size
     * bitmap, and a dozen at once on a mid-range phone is how a screen gets
     * killed for memory rather than how it gets fast. Each photo joins the queue
     * as it lands, so the first garment can be filled in while the rest are still
     * being copied.
     *
     * A photo that cannot be stored is skipped rather than failing the batch --
     * one unreadable file out of twenty must not cost the other nineteen -- and
     * the screen says so once at the end.
     */
    fun onPhotosPicked(picked: List<Picked>) {
        if (picked.isEmpty()) return

        _state.update { it.copy(importing = true, error = null, errorFallback = null) }

        scope.launch {
            var failed = 0

            for (photo in picked.take(BulkAddState.MAX_PHOTOS)) {
                val stored = attempt { photos.store(photo) }.getOrNull()
                if (stored == null) {
                    failed++
                } else {
                    _state.update { it.copy(queue = it.queue.withDraftsAdded(listOf(stored))) }
                    detectColors(stored)
                }
            }

            _state.update {
                it.copy(
                    importing = false,
                    errorFallback = if (failed > 0) ErrorFallback.PHOTO_NOT_IMPORTED else null,
                )
            }
        }
    }

    /**
     * Read a photo's colours in the background.
     *
     * Fire-and-forget per photo, and the result is applied by photo rather than to
     * whatever is on screen when it lands -- see
     * [BulkAddState.withDetectedColors]. A failure leaves the default: a garment
     * whose colour was not read is still a garment, and this screen's whole point
     * is not stopping to ask.
     */
    private fun detectColors(readFrom: String) {
        scope.launch {
            val detected = attempt { photos.colors(readFrom) }.getOrNull()
            if (detected != null) {
                _state.update { it.copy(queue = it.queue.withDetectedColors(readFrom, detected)) }
            }
        }
    }

    fun onCategorySelected(category: String) =
        _state.update { it.copy(queue = it.queue.withCategory(category)) }

    fun onSubcategoryToggled(subcategory: String) = _state.update {
        it.copy(queue = it.queue.withSubcategoryToggled(subcategory, ::seasonsForSubcategories))
    }

    fun onBrandChanged(brand: String) =
        _state.update { it.copy(queue = it.queue.withBrand(brand)) }

    /**
     * Store a re-cropped photo in place of the one it was cropped from.
     *
     * The crop screen writes to a scratch file that the next crop overwrites, so
     * the result is copied into the app's own storage before it is pointed at --
     * the same journey a picked photo makes. The photo it replaces is deleted
     * because nothing else refers to it: the draft is the only thing that did.
     */
    fun onPhotoCropped(cropped: Picked) {
        val draft = _state.value.queue.current ?: return

        _state.update { it.copy(saving = true, error = null, errorFallback = null) }

        scope.launch {
            attempt {
                val stored = photos.store(cropped)
                // Only after the new file exists: a delete first and a failure
                // second would leave the draft pointing at nothing.
                photos.delete(draft.imageUri)
                draft.cutoutUri.takeIf { it.isNotEmpty() }?.let { photos.delete(it) }
                stored
            }
                .onSuccess { stored ->
                    _state.update { it.copy(saving = false, queue = it.queue.withPhotoReplaced(draft.imageUri, stored)) }
                    // A crop is a different set of pixels, so the colours read off
                    // the old framing are an answer about a photo that no longer
                    // exists.
                    detectColors(stored)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = e.message, errorFallback = ErrorFallback.PHOTO_NOT_IMPORTED)
                    }
                }
        }
    }

    /**
     * The crop screen failed.
     *
     * Backing out of it is not a failure -- it means the photo is fine as it is --
     * so only a real error says anything, and the draft keeps the photo it had.
     */
    fun onCropFailed() = _state.update { it.copy(errorFallback = ErrorFallback.PHOTO_NOT_IMPORTED) }

    /** Cut the garment on screen out of its background. */
    fun onRemoveBackground() {
        val draft = _state.value.queue.current ?: return
        if (_state.value.removingBackground) return

        _state.update { it.copy(removingBackground = true, error = null, errorFallback = null) }

        scope.launch {
            attempt { photos.cutOut(draft.imageUri) }
                .onSuccess { cutout ->
                    _state.update {
                        it.copy(removingBackground = false, queue = it.queue.withCutout(draft.imageUri, cutout))
                    }
                    // The cut-out is a better photo of the same garment: only the
                    // garment's own pixels are left in it, so its colours are worth
                    // reading again. The form does this for the same reason.
                    detectColors(cutout)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            removingBackground = false,
                            error = e.message,
                            errorFallback = ErrorFallback.BACKGROUND_NOT_REMOVED,
                        )
                    }
                }
        }
    }

    /**
     * Put the original photo back.
     *
     * The cut-out file goes with it. Unlike the form, there is never a question of
     * whose it is: a draft's cut-out was written for this queue and nothing has a
     * row pointing at it yet.
     */
    fun onUndoBackground() {
        val draft = _state.value.queue.current ?: return
        if (draft.cutoutUri.isEmpty()) return

        _state.update { it.copy(queue = it.queue.withCutoutCleared(draft.imageUri)) }

        scope.launch {
            attempt { photos.delete(draft.cutoutUri) }
            // Back to the photo's own colours, which are not the cut-out's.
            detectColors(draft.imageUri)
        }
    }

    /** Store the garment on screen, then move on. */
    fun onSaveRequested() {
        val draft = _state.value.queue.current ?: return
        if (_state.value.saving) return

        _state.update { it.copy(saving = true, error = null, errorFallback = null) }

        scope.launch {
            attempt { source.save(draft) }
                .onSuccess { _state.update { it.copy(saving = false, queue = it.queue.advanced()) } }
                .onFailure { e ->
                    // The draft stays at the head of the queue, so the answer to a
                    // failed write is to try again rather than to find out later
                    // that one garment out of twenty never arrived.
                    _state.update {
                        it.copy(saving = false, error = e.message, errorFallback = ErrorFallback.GARMENT_NOT_SAVED)
                    }
                }
        }
    }

    /**
     * Throw the garment on screen away, photo and all.
     *
     * The file is deleted because nothing else will: it was copied into the app's
     * own storage to be queued, and a skipped draft is the one case where that
     * copy ends up referenced by no garment at all.
     */
    fun onSkipRequested() {
        val draft = _state.value.queue.current ?: return

        scope.launch {
            // A file left behind is not worth stopping for, and Optimize storage
            // sweeps photos nothing points at.
            attempt {
                photos.delete(draft.imageUri)
                draft.cutoutUri.takeIf { it.isNotEmpty() }?.let { photos.delete(it) }
            }

            _state.update { it.copy(queue = it.queue.skipped()) }
        }
    }

    fun onErrorDismissed() = _state.update { it.copy(error = null, errorFallback = null) }
}
