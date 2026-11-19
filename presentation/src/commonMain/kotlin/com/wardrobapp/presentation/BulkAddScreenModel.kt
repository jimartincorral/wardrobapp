package com.wardrobapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Adding many garments from many photos: a queue of photos, and the ordinary
 * garment form opened on each in turn.
 *
 * The form is [GarmentFormScreenModel] itself, one per photo, rather than
 * anything shaped like it. A fresh one per garment is what makes "the next
 * garment" mean an empty form: no brand typed for the last one, no half-finished
 * reading of its photo landing on this one, no files of its own that this one
 * might delete. And it is the same model the single form uses, so a field added
 * there is a field here, with nothing to remember.
 *
 * Written as each garment is confirmed rather than all at once at the end. A
 * batch of twenty rows written on a final tap is a batch that can be lost whole
 * -- to a phone call, a low-memory kill, or somebody pressing back -- and having
 * to re-enter nineteen garments because of the twentieth is exactly the tedium
 * this screen exists to remove.
 *
 * See ScreenModels.kt, and PhotoWork for why it is generic over [Picked].
 */
class BulkAddScreenModel<Picked>(
    private val scope: CoroutineScope,
    photos: PhotoWork<Picked>,
    private val source: GarmentFormSource,
    private val importer: GarmentImporter,
) {
    /** Every queued photo is read as it arrives; see [ReadAhead]. Each form is handed this, not the bare one. */
    private val photos = ReadAhead(photos, scope)

    private val _state = MutableStateFlow(BulkAddScreenState())
    val state: StateFlow<BulkAddScreenState> = _state.asStateFlow()

    private val _form = MutableStateFlow<GarmentFormScreenModel<Picked>?>(null)

    /**
     * The garment on screen: the ordinary form, opened on the photo at the head
     * of the queue. Null before any photo has arrived and once the queue has
     * drained, which is when the screen shows its start or its summary instead.
     *
     * A model rather than its state, because the screen drives the form exactly
     * as the single form's screen does -- every tap goes straight to it -- and a
     * second list of the form's callbacks, forwarded through here, would be a
     * second list to keep in step with the first.
     */
    val form: StateFlow<GarmentFormScreenModel<Picked>?> = _form.asStateFlow()

    /**
     * Store the photos and queue them up.
     *
     * Stored one at a time rather than in parallel: each one decodes a full-size
     * bitmap, and a dozen at once on a mid-range phone is how a screen gets
     * killed for memory rather than how it gets fast. Each photo joins the queue
     * as it lands, and the first one opens its form straight away, so the first
     * garment can be filled in while the rest are still being copied.
     *
     * A photo that cannot be stored is skipped rather than failing the batch --
     * one unreadable file out of twenty must not cost the other nineteen -- and
     * the screen says so once at the end.
     */
    fun onPhotosPicked(picked: List<Picked>) {
        if (picked.isEmpty()) return

        _state.update { it.copy(importing = true, errorFallback = null) }

        scope.launch {
            var failed = 0

            for (photo in picked.take(BulkAddState.MAX_PHOTOS)) {
                val stored = attempt { photos.store(photo) }.getOrNull()
                if (stored == null) {
                    failed++
                    continue
                }
                photos.readAhead(stored)
                _state.update { it.copy(queue = it.queue.withPhotosAdded(listOf(stored))) }
                // Only when nothing is on screen: before the first photo, or
                // after a finished batch took on more. Otherwise it waits.
                if (_form.value == null) openNext()
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
     * Open a form on the next photo in the queue, or none if nothing is waiting.
     *
     * Read and written in two steps rather than inside an update, because taking
     * the photo off the queue and opening a form on it are one move and an
     * update's lambda may run twice; the scope is single-threaded, so nothing
     * can come between them.
     */
    private fun openNext() {
        val next = _state.value.queue.next()
        if (next == null) {
            _form.value = null
            return
        }

        val (photo, rest) = next
        _state.update { it.copy(queue = rest) }

        lateinit var form: GarmentFormScreenModel<Picked>
        form = GarmentFormScreenModel(
            scope = scope,
            photos = photos,
            source = source,
            importer = importer,
            garmentId = null,
            photo = photo,
            onSaved = { onSaved(form) },
        )
        _form.value = form
    }

    /**
     * A garment was written: count it, and on to the next.
     *
     * Only for the form on screen. One that has been moved on from cannot get
     * here in practice -- skipping is refused while a save is running -- but a
     * save that did land late must not move the queue past a garment nobody has
     * seen.
     */
    private fun onSaved(form: GarmentFormScreenModel<Picked>) {
        if (_form.value !== form) return
        _state.update { it.copy(queue = it.queue.advanced()) }
        openNext()
    }

    /**
     * Throw the garment on screen away, its photos with it, and move on.
     *
     * Refused while it is saving. A save that lands after the skip has written a
     * garment whose photos the skip has just deleted -- a row pointing at
     * nothing, which is worse than either outcome on its own. The screen
     * disables the button for the same wait; this is the rule, and that is the
     * courtesy.
     */
    fun onSkipRequested() {
        val form = _form.value ?: return
        if (form.state.value.saving) return

        _state.update { it.copy(queue = it.queue.skipped()) }
        openNext()

        // On this scope, after the next form is open: the files were copied in
        // to be queued, a skipped garment is the one case where nothing will
        // ever point at them, and deleting them is no reason to keep the next
        // garment waiting.
        scope.launch { form.discard() }
    }

    fun onErrorDismissed() = _state.update { it.copy(errorFallback = null) }
}
