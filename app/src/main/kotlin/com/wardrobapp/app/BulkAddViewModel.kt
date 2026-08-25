package com.wardrobapp.app

import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.seasonsForSubcategories
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.dominantGarmentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Cataloguing several garments from several photos.
 *
 * The queue's rules are in :presentation as pure transitions over
 * [BulkAddState]; this stores the photos, reads their colours off the main
 * thread, and writes a row per garment as the queue advances.
 *
 * Written as each garment is confirmed rather than all at once at the end. A
 * batch of twenty rows written on a final tap is a batch that can be lost whole
 * -- to a phone call, a low-memory kill, or somebody pressing back -- and having
 * to re-enter nineteen garments because of the twentieth is exactly the tedium
 * this screen exists to remove.
 */
class BulkAddViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val queue: BulkAddState = BulkAddState(),
        /**
         * Photos still being copied in.
         *
         * Kept apart from [saving] because they mean opposite things for the
         * buttons: a garment cannot be confirmed twice, so writing one disables
         * them, but a batch still arriving must not -- the first garment is meant
         * to be fillable while the twentieth photo is still being copied, which is
         * the whole difference between a queue and a wait.
         */
        val importing: Boolean = false,
        /** A garment being written. */
        val saving: Boolean = false,
        val error: String? = null,
        @StringRes val errorFallback: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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
    fun onPhotosPicked(sources: List<Uri>) {
        if (sources.isEmpty()) return

        _state.update { it.copy(importing = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            var failed = 0

            for (source in sources.take(BulkAddState.MAX_PHOTOS)) {
                val stored = try {
                    withContext(Dispatchers.IO) {
                        resolveImageRef(
                            container.photos.store(source, UUID.randomUUID().toString()),
                            container.imageDirectory,
                        )
                    }
                } catch (_: Exception) {
                    failed++
                    null
                }

                if (stored != null) {
                    _state.update { it.copy(queue = it.queue.withDraftsAdded(listOf(stored))) }
                    detectColors(stored)
                }
            }

            _state.update {
                it.copy(
                    importing = false,
                    errorFallback = if (failed > 0) R.string.error_photo_not_imported else null,
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
    private fun detectColors(imageUri: String) {
        viewModelScope.launch {
            val detected = try {
                withContext(Dispatchers.IO) {
                    container.photos
                        .pixelsFor(imageUri.toUri(), COLOR_SAMPLE_WIDTH)
                        ?.let { dominantGarmentColors(it) }
                }
            } catch (_: Exception) {
                null
            }

            if (detected != null) {
                _state.update { it.copy(queue = it.queue.withDetectedColors(imageUri, detected)) }
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

    /** Write the garment on screen, then move on. */
    fun onSaveRequested() {
        val draft = _state.value.queue.current ?: return
        if (_state.value.saving) return

        _state.update { it.copy(saving = true, error = null, errorFallback = null) }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { write(draft) }
                _state.update { it.copy(saving = false, queue = it.queue.advanced()) }
            } catch (e: Exception) {
                // The draft stays at the head of the queue, so the answer to a
                // failed write is to try again rather than to find out later that
                // one garment out of twenty never arrived.
                _state.update {
                    it.copy(
                        saving = false,
                        error = e.message,
                        errorFallback = R.string.error_garment_not_saved,
                    )
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

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.photos.delete(draft.imageUri) }
            } catch (_: Exception) {
                // A file left behind is not worth stopping for, and Optimize
                // storage sweeps photos nothing points at.
            }

            _state.update { it.copy(queue = it.queue.skipped()) }
        }
    }

    fun onErrorDismissed() = _state.update { it.copy(error = null, errorFallback = null) }

    internal fun write(draft: BulkAddState.Draft) {
        val now = isoTimestamp(System.currentTimeMillis())

        container.garmentWrites.insert(
            GarmentWrites.NewGarment(
                id = UUID.randomUUID().toString(),
                imageUri = draft.imageUri,
                imageUriNoBg = null,
                imageUris = listOf(draft.imageUri),
                imageUrisNoBg = listOf(""),
                category = draft.category,
                subcategories = draft.subcategories,
                // Seasons are stored as tags, the way the form stores them and the
                // way every reader downstream expects to find them.
                tags = mergeStructuredTags(emptyList(), draft.seasons),
                brand = draft.brand.ifBlank { null },
                colorPrimary = draft.colorPalette.first(),
                colorSecondary = draft.colorPalette.getOrNull(1),
                colorPalette = draft.colorPalette,
                size = null,
                now = now,
            )
        )
    }
}
